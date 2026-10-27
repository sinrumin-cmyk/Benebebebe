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
