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
use glam::{Mat4, Vec3};
use std::sync::{Mutex, MutexGuard, OnceLock};

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
        let params = Params {
            k: [k1, k2, scale, aspect],
        };
        queue.write_buffer(&self.uniform, 0, bytemuck::bytes_of(&params));
        let left = eyes[0].create_view(&Default::default());
        let right = eyes[1].create_view(&Default::default());
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
