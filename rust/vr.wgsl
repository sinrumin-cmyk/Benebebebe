// Goes to crates/horizon-client-render/src/gfx/vr.wgsl. Puts the two eye images side by side on the
// swapchain with the barrel distortion that cancels a Cardboard lens's pincushion.
struct Params {
    // k1, k2, scale, aspect of one eye image (width over height).
    k: vec4<f32>,
    // The floating screen in each eye's image: centre x, centre y, half width, half height (0..1, y down).
    gui_left: vec4<f32>,
    gui_right: vec4<f32>,
    // Screen floating (1 or 0), the cursor's x and y over the screen, cursor shown (1 or 0).
    gui: vec4<f32>,
}
@group(0) @binding(0) var left_eye: texture_2d<f32>;
@group(0) @binding(1) var right_eye: texture_2d<f32>;
@group(0) @binding(2) var samp: sampler;
@group(0) @binding(3) var<uniform> params: Params;
@group(0) @binding(4) var gui_tex: texture_2d<f32>;

struct Out {
    @builtin(position) clip: vec4<f32>,
    @location(0) uv: vec2<f32>,
}

@vertex fn vs_main(@builtin(vertex_index) i: u32) -> Out {
    let p = vec2<f32>(f32((i << 1u) & 2u), f32(i & 2u));
    var out: Out;
    out.clip = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    out.uv = vec2(p.x, 1.0 - p.y);
    return out;
}

@fragment fn fs_main(in: Out) -> @location(0) vec4<f32> {
    let is_right = in.uv.x >= 0.5;
    // 0..1 across the half of the screen this eye owns.
    let half_uv = vec2(select(in.uv.x * 2.0, in.uv.x * 2.0 - 1.0, is_right), in.uv.y);
    // -1..1 from the lens centre, x stretched so the radius is the same in every direction.
    var p = (half_uv - vec2(0.5)) * 2.0;
    p.x *= params.k.w;
    let r2 = dot(p, p);
    let f = (1.0 + params.k.x * r2 + params.k.y * r2 * r2) * params.k.z;
    var q = p * f;
    q.x /= params.k.w;
    let src = q * 0.5 + vec2(0.5);
    let inside = all(src >= vec2(0.0)) && all(src <= vec2(1.0));
    // textureSampleLevel: no derivatives, so no uniform control flow to worry about.
    let a = textureSampleLevel(left_eye, samp, src, 0.0);
    let b = textureSampleLevel(right_eye, samp, src, 0.0);
    var color = select(a, b, is_right);
    if params.gui.x > 0.5 {
        // Where this pixel of the eye image falls on the floating screen, 0..1 over the screen.
        let rect = select(params.gui_left, params.gui_right, is_right);
        let g = (src - rect.xy) / (2.0 * rect.zw) + vec2(0.5);
        if all(g >= vec2(0.0)) && all(g <= vec2(1.0)) {
            // The screen was drawn with alpha blending onto transparent black: its colour is already
            // multiplied by its alpha.
            let s = textureSampleLevel(gui_tex, samp, g, 0.0);
            color = vec4(s.rgb + color.rgb * (1.0 - s.a), 1.0);
            // The cursor: a round dot with a dark rim, round on the screen whatever its proportions.
            if params.gui.w > 0.5 {
                let d = length((g - params.gui.yz) * vec2(params.k.w, 1.0));
                if d < 0.016 {
                    color = vec4(select(vec3(0.0), vec3(1.0), d < 0.011), 1.0);
                }
            }
        }
    }
    return select(vec4(0.0, 0.0, 0.0, 1.0), color, inside);
}
