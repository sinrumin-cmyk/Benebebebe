#!/usr/bin/env python3
"""Usage: python3 apply_patch.py /path/to/horizon [--linux-cfg]
Patches a Horizon checkout for Android. Every edit must match exactly once, or nothing is written for that file."""
import pathlib, re, shutil, sys

root = pathlib.Path(sys.argv[1]).resolve()
here = pathlib.Path(__file__).parent
client = root / "crates/horizon-client"

def edit(path, pairs):
    text = path.read_text()
    for old, new in pairs:
        n = text.count(old)
        if n != 1:
            sys.exit(f"{path}: anchor matched {n}x (need 1):\n{old}")
        text = text.replace(old, new)
    path.write_text(text)
    print("patched", path.relative_to(root))

shutil.copy(here / "touch_input.rs", client / "src/touch_input.rs")
shutil.copy(here / "platform.rs", client / "src/platform.rs")
shutil.copy(here / "android.rs", client / "src/android.rs")
shutil.copy(here / "game_touch.rs", client / "src/game/touch.rs")

edit(client / "src/lib.rs", [(
    "#![forbid(unsafe_code)]\n",
    '#![cfg_attr(not(target_os = "android"), forbid(unsafe_code))]\n'
    '#![cfg_attr(target_os = "android", deny(unsafe_code))]\n'
    "pub mod platform;\npub mod touch_input;\n"
    '#[cfg(target_os = "android")]\nextern crate self as horizon_client;\n'
    '#[cfg(target_os = "android")]\npub mod android;\n'
    '#[cfg(target_os = "android")]\nmod game; // on Android the game is part of the library (the .so)\n',
)])

edit(client / "src/game.rs", [
    ("mod clipboard;\n", "mod clipboard;\nmod touch;\n"),
    ("let mut args = std::env::args().skip(1);", "let mut args = horizon_client::platform::args().into_iter();"),
    ("EventLoop::new()?.run_app(&mut game)?;", "horizon_client::platform::event_loop()?.run_app(&mut game)?;"),
    ("EventLoop::new()?.run_app(&mut app)?;", "horizon_client::platform::event_loop()?.run_app(&mut app)?;"),
    ("                .or_else(|_| window.set_cursor_grab(CursorGrabMode::Confined))\n                .is_ok();",
     "                .or_else(|_| window.set_cursor_grab(CursorGrabMode::Confined))\n                .is_ok()\n                || cfg!(target_os = \"android\"); // winit cannot grab on Android; touch look needs `captured`"),
    ("        if self.pending_save.is_some() {\n            match self.poll_closing_save() {",
     "        self.apply_touch_input(event_loop);\n        if self.pending_save.is_some() {\n            match self.poll_closing_save() {"),
    ("                WindowEvent::RedrawRequested => {\n                    self.observe_fullscreen();",
     "                WindowEvent::RedrawRequested => {\n                    horizon_client::touch_input::count_frame();\n                    self.observe_fullscreen();"),
])

edit(client / "src/game/pad.rs", [(
    "let state = hardware.merge(&self.pad.injected);",
    "let state = hardware\n            .merge(&self.pad.injected)\n            .merge(&horizon_client::touch_input::pad());",
)])

# Ely.by (and other Yggdrasil servers): session files of type "ely", and HORIZON_JOIN_URL as the join endpoint.
edit(client / "src/auth.rs", [
    ('if field("user_type").is_some_and(|t| t != "msa") {',
     'if field("user_type").is_some_and(|t| !matches!(t, "msa" | "ely")) {'),
    ('    let doc = discovery()?;\n    let url = endpoint(&doc, "session", "join").ok_or(JoinError::Unavailable)?;\n    match agent()',
     '    let url = match std::env::var("HORIZON_JOIN_URL").ok().filter(|u| !u.is_empty()) {\n'
     '        Some(url) => url,\n'
     '        None => {\n'
     '            let doc = discovery()?;\n'
     '            endpoint(&doc, "session", "join").ok_or(JoinError::Unavailable)?\n'
     '        }\n'
     '    };\n    match agent()'),
])

# arboard has no Android backend: an always-empty stand-in with the same calls.
# The file is found by content (upstream moves files around); the shim is appended, so no anchor text is needed.
SHIM = '''
#[cfg(target_os = "android")]
#[allow(dead_code)]
mod arboard {
    #[derive(Debug)]
    pub enum Error { ContentNotAvailable, #[allow(dead_code)] Unknown }
    pub struct Clipboard;
    impl Clipboard {
        pub fn new() -> Result<Self, Error> { Err(Error::ContentNotAvailable) }
        pub fn get_text(&mut self) -> Result<String, Error> { Err(Error::ContentNotAvailable) }
        pub fn set_text(&mut self, _: String) -> Result<(), Error> { Err(Error::ContentNotAvailable) }
    }
}
'''
users = [f for f in sorted(root.rglob("*.rs"))
         if "target" not in f.relative_to(root).parts and "arboard::" in f.read_text() and "mod arboard" not in f.read_text()]
if not users:
    print("note: no source file uses arboard::, clipboard shim skipped")
for f in users:
    f.write_text(f.read_text().rstrip("\n") + "\n" + SHIM)
    print("patched (arboard shim)", f.relative_to(root))

# ---------------------------------------------------------------- shaders the Adreno driver cannot build
# Qualcomm's Vulkan driver fails "Failed to link shaders" on a large constant array indexed by a runtime value in a
# vertex shader (cull.wgsl's box corners, panorama.wgsl's cube), after which wgpu panics. The tables are packed into
# integer constants and read with shifts instead: same values, no array. The numbers are parsed from the source, so a
# changed table is repacked rather than silently kept stale.
def find_wgsl(marker):
    for f in sorted(root.rglob("*.wgsl")):
        if "target" in f.relative_to(root).parts:
            continue
        if marker in f.read_text():
            return f
    return None

def pack3(values, per_word=10):
    words = [0] * ((len(values) + per_word - 1) // per_word)
    for i, v in enumerate(values):
        assert 0 <= v < 8, v
        words[i // per_word] |= v << ((i % per_word) * 3)
    return words

def hexu(w):
    return "0x%08xu" % w

def pick4(var, words):
    return f"select(select(select({hexu(words[0])}, {hexu(words[1])}, {var} >= 10u), {hexu(words[2])}, {var} >= 20u), {hexu(words[3])}, {var} >= 30u)"

def shader_fix(path, fn):
    text = path.read_text()
    new = fn(text)
    if new == text:
        print("WARNING: shader fix changed nothing in", path.relative_to(root))
    path.write_text(new)
    print("patched shader", path.relative_to(root))

def fix_cull(text):
    m = re.search(r"const CORNERS = array<u32, 36>\((.*?)\);\n", text, re.S)
    if not m or text.count("CORNERS[v]") != 1:
        sys.exit("cull.wgsl: CORNERS table not in the expected form")
    nums = [int(x) for x in re.findall(r"\b(\d+)u?\b", m.group(1))]
    if len(nums) != 36:
        sys.exit(f"cull.wgsl: CORNERS has {len(nums)} entries, expected 36")
    w = pack3(nums)
    func = ("// The corner table, packed 3 bits per entry (10 to a word): Adreno cannot index a big constant array.\n"
            "fn box_corner(v: u32) -> u32 {\n"
            f"    let word = {pick4('v', w)};\n"
            "    return (word >> ((v % 10u) * 3u)) & 7u;\n}\n")
    text = text.replace(m.group(0), func)
    return text.replace("CORNERS[v]", "box_corner(v)")

def fix_panorama(text):
    m = re.search(r"    let corners = array<vec3<f32>, 8>\((.*?)\);\n    let indices = array<u32, 36>\((.*?)\);\n"
                  r"    let position = corners\[indices\[index\]\];\n", text, re.S)
    if not m:
        sys.exit("panorama.wgsl: cube tables not in the expected form")
    comps = [float(x) for x in re.findall(r"-?\d+\.\d+", m.group(1))]
    idx = [int(x) for x in re.findall(r"\b(\d+)u?\b", m.group(2))]
    if len(comps) != 24 or any(abs(c) != 1.0 for c in comps) or len(idx) != 36:
        sys.exit("panorama.wgsl: expected 8 corners of +-1 and 36 indices")
    bits = 0
    for i, c in enumerate(comps):
        if c > 0:
            bits |= 1 << i
    w = pack3(idx)
    code = (f"    let word = {pick4('index', w)};\n"
            "    let id = (word >> ((index % 10u) * 3u)) & 7u;\n"
            f"    let signs = {hexu(bits)};   // 3 bits per corner: x, y, z positive\n"
            "    let position = vec3(\n"
            "        select(-1.0, 1.0, ((signs >> (id * 3u)) & 1u) == 1u),\n"
            "        select(-1.0, 1.0, ((signs >> (id * 3u + 1u)) & 1u) == 1u),\n"
            "        select(-1.0, 1.0, ((signs >> (id * 3u + 2u)) & 1u) == 1u));\n")
    return text.replace(m.group(0), code)

def fix_portal(text):
    m = re.search(r"const PORTAL_COLORS = array<vec3<f32>, 16>\((.*?)\);\n", text, re.S)
    if not m:
        sys.exit("PORTAL_COLORS table not in the expected form")
    colors = re.findall(r"vec3\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)", m.group(1))
    if len(colors) != 16:
        sys.exit(f"PORTAL_COLORS has {len(colors)} colours, expected 16")
    lines = [f"    var c = vec3({', '.join(colors[0])});"]
    for i in range(1, 16):
        lines.append(f"    c = select(c, vec3({', '.join(colors[i])}), i == {i});")
    func = ("fn portal_color(i: i32) -> vec3<f32> {\n" + "\n".join(lines) + "\n    return c;\n}\n")
    return (text.replace(m.group(0), m.group(0) + func)
                .replace("PORTAL_COLORS[0]", "portal_color(0)")
                .replace("PORTAL_COLORS[i]", "portal_color(i)"))

for marker, fn in (("const CORNERS = array<u32, 36>", fix_cull),
                   ("let corners = array<vec3<f32>, 8>", fix_panorama),
                   ("const PORTAL_COLORS = array<vec3<f32>, 16>", fix_portal)):
    fixed = 0
    for f in sorted(root.rglob("*.wgsl")):
        if "target" not in f.relative_to(root).parts and marker in f.read_text():
            shader_fix(f, fn)
            fixed += 1
    if fixed == 0 and fn is not fix_portal:
        sys.exit(f"no shader contains {marker!r}: the Adreno fix needs updating")

NOT_ANDROID = "[target.'cfg(not(target_os = \"android\"))'.dependencies]"

def move_arboard(cargo):
    """Moves a plain `arboard` dependency of this crate to the not-android target table. Returns True if it changed anything."""
    lines = cargo.read_text().splitlines(keepends=True)
    out, moved, section = [], [], None
    for ln in lines:
        s = ln.strip()
        if s.startswith("["):
            section = s
        if section == "[dependencies]" and re.match(r"arboard\s*(=|\.)", s):
            moved.append(ln if ln.endswith("\n") else ln + "\n")
            continue
        out.append(ln)
    if not moved:
        return False
    text = "".join(out)
    if NOT_ANDROID in text:
        text = text.replace(NOT_ANDROID + "\n", NOT_ANDROID + "\n" + "".join(moved), 1)
    else:
        text = text.rstrip("\n") + "\n\n" + NOT_ANDROID + "\n" + "".join(moved)
    cargo.write_text(text)
    print("arboard moved to not-android in", cargo.relative_to(root))
    return True

manifests = [c for c in sorted(root.rglob("Cargo.toml")) if c != root / "Cargo.toml" and "target" not in c.relative_to(root).parts]
found = [c for c in manifests if move_arboard(c)]
for c in manifests:
    if c not in found and re.search(r"arboard", c.read_text()):
        print("WARNING: arboard mentioned in", c.relative_to(root), "in a form this script does not handle")

cargo = client / "Cargo.toml"
ctext = cargo.read_text()
if "\n[dependencies]\n" not in ctext:
    sys.exit(f"{cargo}: no [dependencies] section")
if "crate-type" not in ctext:
    ctext = ctext.replace("\n[dependencies]\n", '\n[lib]\ncrate-type = ["rlib", "cdylib"]\n\n[dependencies]\n', 1)
cargo.write_text(ctext)
with open(cargo, "a") as f:
    f.write('''
[target.'cfg(target_os = "android")'.dependencies]
winit = { version = "0.30.13", features = ["android-native-activity"] }
jni = "0.21"
android_log-sys = "0.3"
''')
print("patched crates/horizon-client/Cargo.toml")

if "--linux-cfg" in sys.argv:
    files = ["horizon-bench/src/hardware.rs", "horizon-client/src/crash.rs", "horizon-client/src/game.rs",
             "horizon-client/src/telemetry.rs", "horizon-friends/src/portmap.rs",
             "horizon-telemetry/src/system.rs", "horizon-threads/src/lib.rs"]
    for rel in files:
        p = root / "crates" / rel
        t = p.read_text()
        p.write_text(t.replace('target_os = "linux"', 'any(target_os = "linux", target_os = "android")'))
        print("linux->linux|android", rel)
