//! Goes to crates/horizon-client-render/src/gfx/vr.rs. Phone VR for a Cardboard-style viewer, 3DoF.
//!
//! The renderer draws the frame twice, once per eye, into two textures the size of one eye (half the
//! screen's width): every size the game reads from `Renderer::config` is then one eye's, so the HUD and the
//! menus lay out per eye on their own. `Vr::composite` puts both images on the swapchain with lens
//! distortion. The head turn comes from the phone's rotation sensor (`set_head`, called by the shell).
//! Turned on by `HORIZON_VR=1` (the launcher writes it to game-env.txt); the rest is tuned the same way:
//! `HORIZON_VR_IPD` (metres between the eyes, 0.064), `HORIZON_VR_FOV` (vertical degrees, 90),
//! `HORIZON_VR_K1`, `HORIZON_VR_K2` (lens distortion, 0.441 and 0.156; 0 and 0 turns it off).
use super::view::View;
use glam::{Mat4, Quat, Vec3};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::Instant;

#[derive(Clone, Copy)]
struct Head {
    yaw: f32,
    pitch: f32,
    roll: f32,
    /// Yaw and pitch at the last `take_head_delta`.
    seen: Option<(f32, f32)>,
}

static HEAD: Mutex<Head> = Mutex::new(Head {
    yaw: 0.0,
    pitch: 0.0,
    roll: 0.0,
    seen: None,
});

fn head() -> MutexGuard<'static, Head> {
    HEAD.lock().unwrap_or_else(|e| e.into_inner())
}

/// The head's orientation in degrees, from the phone's rotation sensor: yaw grows turning right, pitch is
/// positive looking down (both as the game counts them) and roll is positive tilting the head to the right.
pub fn set_head(yaw: f32, pitch: f32, roll: f32) {
    if yaw.is_finite() && pitch.is_finite() && roll.is_finite() {
        let mut h = head();
        h.yaw = yaw;
        h.pitch = pitch;
        h.roll = roll;
    }
}

/// The head's tilt in degrees; the game's own yaw and pitch follow the rest.
pub fn roll() -> f32 {
    head().roll
}

fn wrap_degrees(a: f32) -> f32 {
    (a + 180.0).rem_euclid(360.0) - 180.0
}

/// How far the head turned (yaw, pitch; degrees) since the last call. The first call is 0: the sensor's own
/// zero is arbitrary, only the change matters, so the game's yaw and pitch keep any other way of turning.
pub fn take_head_delta() -> (f32, f32) {
    let mut h = head();
    let now = (h.yaw, h.pitch);
    let delta = match h.seen {
        Some((yaw, pitch)) => (wrap_degrees(now.0 - yaw), now.1 - pitch),
        None => (0.0, 0.0),
    };
    h.seen = Some(now);
    delta
}

// ---------------------------------------------------------------------------------------------------------------
// The hand, Vivecraft style. The shell (HandTracker.java) tracks a hand with the phone's camera and says where it is
// in the head's frame: metres, x to the right, y up, -z ahead. The first-person arm and the item in it are then
// drawn there (`hand_pose`), not at their fixed place at the bottom right of the view.

#[derive(Clone, Copy)]
struct HandState {
    /// Where the camera last saw it, and when.
    target: Vec3,
    fed: Option<Instant>,
    /// Where it is drawn: eased toward `target` on every frame.
    shown: Vec3,
    tracking: bool,
    drawn: Option<Instant>,
}

static HAND: Mutex<HandState> = Mutex::new(HandState {
    target: Vec3::ZERO,
    fed: None,
    shown: Vec3::ZERO,
    tracking: false,
    drawn: None,
});

/// `valid` false when the hand is out of the camera's view.
pub fn set_hand(valid: bool, x: f32, y: f32, z: f32) {
    let mut h = HAND.lock().unwrap_or_else(|e| e.into_inner());
    if valid {
        h.target = Vec3::new(x, y, z);
        h.fed = Some(Instant::now());
    } else {
        h.fed = None;
    }
}

/// Where vanilla draws the right arm in the view (`ItemInHandRenderer`: translate 0.64, -0.6, -0.72).
const DEFAULT_HAND: Vec3 = Vec3::new(0.64, -0.6, -0.72);
/// The right shoulder in the head's frame: the arm points from here to the hand.
const SHOULDER: Vec3 = Vec3::new(0.25, -0.5, 0.05);

/// The extra pose of the first-person hand, to put between the hand's projection and its own pose. Identity (the
/// vanilla place) when no hand is tracked. `eye` and `ipd` shift it so each eye sees the hand at its own depth:
/// the eyes' views already contain their sideways offset, this undoes it around the hand's placement.
pub fn hand_pose(ipd: Option<f32>, eye: Option<usize>) -> Mat4 {
    let mut h = HAND.lock().unwrap_or_else(|e| e.into_inner());
    let now = Instant::now();
    let fresh = h.fed.is_some_and(|t| now.duration_since(t).as_millis() < 400);
    if !fresh {
        h.tracking = false;
        return Mat4::IDENTITY;
    }
    if !h.tracking {
        h.shown = h.target;   // it just came into view: no sliding in from wherever it was last
        h.tracking = true;
    } else if let Some(last) = h.drawn {
        let dt = now.duration_since(last).as_secs_f32().min(0.1);
        let ease = 1.0 - (-dt * 20.0).exp();
        h.shown = h.shown.lerp(h.target, ease);
    }
    h.drawn = Some(now);
    let p = h.shown;
    let aim = (p - SHOULDER).normalize_or_zero();
    if aim == Vec3::ZERO {
        return Mat4::IDENTITY;
    }
    let rotation = Quat::from_rotation_arc(Vec3::NEG_Z, aim);
    let pose = Mat4::from_translation(p) * Mat4::from_quat(rotation) * Mat4::from_translation(-DEFAULT_HAND);
    match (ipd, eye) {
        (Some(ipd), Some(eye)) => {
            let side = if eye == 0 { 1.0 } else { -1.0 };
            let e = Mat4::from_translation(Vec3::new(side * ipd / 2.0, 0.0, 0.0));
            e * pose * e.inverse()
        }
        _ => pose,
    }
}

// ---------------------------------------------------------------------------------------------------------------
// The screen floating in the world. While a screen is open (a menu, the inventory, chat) the HUD batch, which is
// the screen, is not stretched over each eye: it is drawn once into `Vr::gui` and the compositor puts it in front
// of the player, fixed where the player was looking when it opened, so the head can turn away from it. The cursor
// is moved by the hand's ray (`set_gui_pointer`) where the hand is tracked.

#[derive(Clone, Copy)]
struct GuiState {
    open: bool,
    /// Where the screen was put: the head's yaw and pitch when it opened.
    anchor: (f32, f32),
    /// The game's cursor over the screen, 0..1.
    cursor: (f32, f32),
    /// The hand's ray relative to the head (degrees: yaw to the right, pitch up) and when it was last seen.
    ray: Option<(f32, f32, Instant)>,
}

static GUI: Mutex<GuiState> = Mutex::new(GuiState {
    open: false,
    anchor: (0.0, 0.0),
    cursor: (0.5, 0.5),
    ray: None,
});

fn gui() -> MutexGuard<'static, GuiState> {
    GUI.lock().unwrap_or_else(|e| e.into_inner())
}

/// The game says every tick whether a screen with a cursor is open and where its cursor is (0..1 over the screen).
pub fn set_screen(open: bool, cursor_u: f32, cursor_v: f32) {
    let (yaw, pitch) = {
        let h = head();
        (h.yaw, h.pitch)
    };
    let mut g = gui();
    if open && !g.open {
        g.anchor = (yaw, pitch);
    }
    g.open = open;
    g.cursor = (cursor_u, cursor_v);
}

pub fn screen_open() -> bool {
    gui().open
}

/// The hand's pointing ray in the head's frame; `valid` false when no hand is seen.
pub fn set_gui_pointer(valid: bool, yaw_right: f32, pitch_up: f32) {
    let mut g = gui();
    g.ray = valid.then(|| (yaw_right, pitch_up, Instant::now()));
}

/// The place on the floating screen the hand points at, 0..1 from its top left corner, if a hand points at it.
pub fn gui_pointer_uv() -> Option<(f32, f32)> {
    let g = gui();
    if !g.open || !floating_enabled() {
        return None;
    }
    let (ray_yaw, ray_pitch, at) = g.ray?;
    if at.elapsed().as_millis() > 400 {
        return None;
    }
    let h = head();
    // Panel and ray in the same terms, tangents of the angles from where the head looks (see `Vr::gui_rect`).
    let panel_x = wrap_degrees(g.anchor.0 - h.yaw).to_radians().tan();
    let panel_y = (h.pitch - g.anchor.1).to_radians().tan();
    let size = gui_scale();
    // The screen's half width in tangents: `size` of the half view, whose half width is tan(fov/2) * aspect.
    let (half_x, half_y) = (size * view_tangents().0, size * view_tangents().1);
    let nx = (ray_yaw.to_radians().tan() - panel_x) / half_x;
    let ny = (ray_pitch.to_radians().tan() - panel_y) / half_y;
    Some((0.5 + 0.5 * nx, 0.5 - 0.5 * ny))
}

/// Where the eye's field of view ends, as tangents (horizontal, vertical); set by the compositor each frame.
static TANGENTS: Mutex<(f32, f32)> = Mutex::new((1.0, 1.0));

fn view_tangents() -> (f32, f32) {
    *TANGENTS.lock().unwrap_or_else(|e| e.into_inner())
}

/// One eye's size (what `Renderer::config` says), for the shell's cursor.
static EYE_SIZE: Mutex<[u32; 2]> = Mutex::new([0, 0]);

pub fn eye_size() -> [u32; 2] {
    *EYE_SIZE.lock().unwrap_or_else(|e| e.into_inner())
}

fn floating_enabled() -> bool {
    static ON: OnceLock<bool> = OnceLock::new();
    *ON.get_or_init(|| std::env::var("HORIZON_VR_GUI").map_or(true, |v| v != "0"))
}

/// The floating screen's size: this much of an eye's view.
fn gui_scale() -> f32 {
    static SCALE: OnceLock<f32> = OnceLock::new();
    *SCALE.get_or_init(|| env_f32("HORIZON_VR_GUI_SCALE", 0.8, 0.2, 1.5))
}

/// How far in front the screen is, for the difference between the eyes.
const GUI_DISTANCE: f32 = 2.0;

/// Whether the launcher asked for VR.
pub fn enabled() -> bool {
    static ON: OnceLock<bool> = OnceLock::new();
    *ON.get_or_init(|| std::env::var("HORIZON_VR").is_ok_and(|v| v == "1"))
}

fn env_f32(name: &str, default: f32, min: f32, max: f32) -> f32 {
    std::env::var(name)
        .ok()
        .and_then(|v| v.trim().parse::<f32>().ok())
        .filter(|v| v.is_finite())
        .map_or(default, |v| v.clamp(min, max))
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct Params {
    k: [f32; 4],
    /// The floating screen in each eye's image, 0..1: centre x, centre y, half width, half height.
    gui_left: [f32; 4],
    gui_right: [f32; 4],
    /// On (1 or 0), the cursor's x and y over the screen, cursor shown (1 or 0).
    gui: [f32; 4],
}

pub struct Vr {
    /// The swapchain's size; the renderer's `config` is one eye's.
    pub surface: [u32; 2],
    /// Metres between the eyes (a block is a metre).
    pub ipd: f32,
    /// Vertical field of view of one eye, degrees.
    pub fov: f32,
    k: [f32; 2],
    format: wgpu::TextureFormat,
    eyes: Option<([u32; 2], [wgpu::Texture; 2])>,
    /// The screen drawn once, the size of one eye; see `floating`.
    gui_texture: Option<wgpu::Texture>,
    pipeline: wgpu::RenderPipeline,
    layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    uniform: wgpu::Buffer,
}

impl Vr {
    /// Takes the swapchain's `config` and leaves it describing one eye: half the width, the same format.
    pub fn new(device: &wgpu::Device, config: &mut wgpu::SurfaceConfiguration) -> Self {
        let format = config.format.remove_srgb_suffix();
        let surface = [config.width, config.height];
        config.width = (config.width / 2).max(1);
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Horizon VR"),
            source: wgpu::ShaderSource::Wgsl(include_str!("vr.wgsl").into()),
        });
        let texture = |binding| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: true },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        };
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Horizon VR bindings"),
            entries: &[
                texture(0),
                texture(1),
                texture(4),
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 3,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                },
            ],
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Horizon VR"),
            bind_group_layouts: &[Some(&layout)],
            ..Default::default()
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("Horizon VR composite"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("vs_main"),
                buffers: &[],
                compilation_options: Default::default(),
            },
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: Some("fs_main"),
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: None,
                    write_mask: wgpu::ColorWrites::ALL,
                })],
                compilation_options: Default::default(),
            }),
            primitive: Default::default(),
            depth_stencil: None,
            multisample: Default::default(),
            multiview_mask: None,
            cache: None,
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("Horizon VR"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            ..Default::default()
        });
        let uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("Horizon VR"),
            size: std::mem::size_of::<Params>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        Self {
            surface,
            ipd: env_f32("HORIZON_VR_IPD", 0.064, 0.0, 0.2),
            fov: env_f32("HORIZON_VR_FOV", 90.0, 40.0, 130.0),
            k: [
                env_f32("HORIZON_VR_K1", 0.441, -1.0, 2.0),
                env_f32("HORIZON_VR_K2", 0.156, -1.0, 2.0),
            ],
            format,
            eyes: None,
            gui_texture: None,
            pipeline,
            layout,
            sampler,
            uniform,
        }
    }

    /// The two eye textures at `size` (one eye's), made again when the size changes.
    pub fn ensure_eyes(&mut self, device: &wgpu::Device, size: [u32; 2]) {
        if self.eyes.as_ref().is_some_and(|e| e.0 == size) {
            return;
        }
        let format = self.format;
        let make = |label| {
            device.create_texture(&wgpu::TextureDescriptor {
                label: Some(label),
                size: wgpu::Extent3d {
                    width: size[0],
                    height: size[1],
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format,
                // COPY_SRC: the menu blur and screenshots copy the frame drawn so far.
                usage: wgpu::TextureUsages::RENDER_ATTACHMENT
                    | wgpu::TextureUsages::TEXTURE_BINDING
                    | wgpu::TextureUsages::COPY_SRC,
                view_formats: &[],
            })
        };
        self.eyes = Some((size, [make("Horizon left eye"), make("Horizon right eye")]));
        self.gui_texture = Some(make("Horizon floating screen"));
        *EYE_SIZE.lock().unwrap_or_else(|e| e.into_inner()) = size;
    }

    /// What `eye` (0 left, 1 right) is drawn into.
    pub fn eye_texture(&self, eye: usize) -> Option<wgpu::Texture> {
        self.eyes.as_ref().map(|e| e.1[eye.min(1)].clone())
    }

    /// `view` (the head's view) as seen by `eye`: tilted with the head and moved half the eye distance
    /// sideways. The camera moves left for the left eye, so the world moves right in eye space.
    pub fn eye_view(&self, view: View, eye: usize) -> View {
        let side = if eye == 0 { 1.0 } else { -1.0 };
        let shift = Mat4::from_translation(Vec3::new(side * self.ipd / 2.0, 0.0, 0.0));
        View {
            view: shift * Mat4::from_rotation_z(roll().to_radians()) * view.view,
            ..view
        }
    }

    /// Whether the screen is floating in the world now: a screen is open and the renderer has what it needs.
    pub fn floating(&self) -> bool {
        self.gui_texture.is_some() && screen_open() && floating_enabled()
    }

    /// The texture the HUD batch is drawn into while the screen floats.
    pub fn gui_view(&self) -> Option<wgpu::TextureView> {
        self.gui_texture.as_ref().map(|t| t.create_view(&Default::default()))
    }

    /// Empties the floating screen's texture, transparent, before the HUD is drawn into it.
    pub fn clear_gui(&self, encoder: &mut wgpu::CommandEncoder) {
        let Some(view) = self.gui_view() else {
            return;
        };
        let _pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("Horizon floating screen clear"),
            color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                view: &view,
                resolve_target: None,
                depth_slice: None,
                ops: wgpu::Operations {
                    load: wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                    store: wgpu::StoreOp::Store,
                },
            })],
            ..Default::default()
        });
    }

    /// Where the floating screen is in `eye`'s image (centre and half size, 0..1 from the top left): fixed in the
    /// world where it was put, so it moves across the view as the head turns, and a little differently for each
    /// eye because it is `GUI_DISTANCE` metres away.
    fn gui_rect(&self, eye: usize, aspect: f32) -> [f32; 4] {
        let g = *gui();
        let h = *head();
        let t = (self.fov.to_radians() / 2.0).tan();
        let side = if eye == 0 { 1.0 } else { -1.0 };
        let x = wrap_degrees(g.anchor.0 - h.yaw).to_radians().tan() / (t * aspect)
            + side * (self.ipd / 2.0) / GUI_DISTANCE / (t * aspect);
        let y = (h.pitch - g.anchor.1).to_radians().tan() / t;
        let size = gui_scale();
        [0.5 + 0.5 * x, 0.5 - 0.5 * y, 0.5 * size, 0.5 * size]
    }

    /// Draws both eyes side by side into `target` (the swapchain image's non-sRGB view).
    pub fn composite(
        &self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        encoder: &mut wgpu::CommandEncoder,
        target: &wgpu::TextureView,
    ) {
        let Some((size, eyes)) = &self.eyes else {
            return;
        };
        let aspect = size[0] as f32 / size[1].max(1) as f32;
        let [k1, k2] = self.k;
        // The vertical edge of the lens circle maps onto the vertical edge of the image.
        let scale = 1.0 / (1.0 + k1 + k2).max(0.1);
        let t = (self.fov.to_radians() / 2.0).tan();
        *TANGENTS.lock().unwrap_or_else(|e| e.into_inner()) = (t * aspect, t);
        let floating = self.floating();
        let cursor = gui().cursor;
        let params = Params {
            k: [k1, k2, scale, aspect],
            gui_left: self.gui_rect(0, aspect),
            gui_right: self.gui_rect(1, aspect),
            gui: [if floating { 1.0 } else { 0.0 }, cursor.0, cursor.1, 1.0],
        };
        queue.write_buffer(&self.uniform, 0, bytemuck::bytes_of(&params));
        let left = eyes[0].create_view(&Default::default());
        let right = eyes[1].create_view(&Default::default());
        let Some(gui_texture) = &self.gui_texture else {
            return;
        };
        let gui_view = gui_texture.create_view(&Default::default());
        let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("Horizon VR"),
            layout: &self.layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(&left),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::TextureView(&right),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: wgpu::BindingResource::Sampler(&self.sampler),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: self.uniform.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 4,
                    resource: wgpu::BindingResource::TextureView(&gui_view),
                },
            ],
        });
        let attachments = [Some(wgpu::RenderPassColorAttachment {
            view: target,
            resolve_target: None,
            depth_slice: None,
            ops: wgpu::Operations {
                load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                store: wgpu::StoreOp::Store,
            },
        })];
        let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("Horizon VR composite"),
            color_attachments: &attachments,
            ..Default::default()
        });
        pass.set_pipeline(&self.pipeline);
        pass.set_bind_group(0, &group, &[]);
        pass.draw(0..3, 0..1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_composite_shader_validates() {
        let module = naga::front::wgsl::parse_str(include_str!("vr.wgsl")).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::all(),
        )
        .validate(&module)
        .unwrap();
    }

    #[test]
    fn yaw_deltas_wrap_the_short_way() {
        assert!((wrap_degrees(350.0) + 10.0).abs() < 1e-4);
        assert!((wrap_degrees(-190.0) - 170.0).abs() < 1e-4);
        assert!(wrap_degrees(0.0).abs() < 1e-6);
    }

    #[test]
    fn the_head_delta_starts_at_zero_and_tracks_changes() {
        // One test owns the shared head state.
        set_head(10.0, 5.0, 0.0);
        let _ = take_head_delta();
        set_head(20.0, 2.0, 0.0);
        let (yaw, pitch) = take_head_delta();
        assert!((yaw - 10.0).abs() < 1e-4 && (pitch + 3.0).abs() < 1e-4);
        set_head(-170.0, 2.0, 0.0);
        let (yaw, _) = take_head_delta();
        assert!((yaw + 170.0).abs() < 1e-3 || (yaw - 170.0).abs() < 1e-3, "{yaw}");
    }
}
