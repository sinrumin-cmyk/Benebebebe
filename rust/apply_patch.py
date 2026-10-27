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
     '        None => endpoint(&discovery()?, "session", "join").ok_or(JoinError::Unavailable)?,\n'
     '    };\n    match agent()'),
])

# arboard has no Android backend: an always-empty stand-in with the same calls.
edit(client / "src/clipboard.rs", [(
    "/// A clipboard: the system's, or an in-memory one in tests.",
    '''#[cfg(target_os = "android")]
mod arboard {
    pub enum Error { ContentNotAvailable }
    pub struct Clipboard;
    impl Clipboard {
        pub fn new() -> Result<Self, Error> { Err(Error::ContentNotAvailable) }
        pub fn get_text(&mut self) -> Result<String, Error> { Err(Error::ContentNotAvailable) }
        pub fn set_text(&mut self, _: String) -> Result<(), Error> { Err(Error::ContentNotAvailable) }
    }
}

/// A clipboard: the system's, or an in-memory one in tests.''',
)])

arb = 'arboard = { version = "3.6.1", default-features = false, features = ["wayland-data-control"] }\n'
edit(client / "Cargo.toml", [
    ("\n[dependencies]\n", '\n[lib]\ncrate-type = ["rlib", "cdylib"]\n\n[dependencies]\n'),
    (arb, ""),
])
with open(client / "Cargo.toml", "a") as f:
    f.write(f'''
[target.'cfg(not(target_os = "android"))'.dependencies]
{arb}
[target.'cfg(target_os = "android")'.dependencies]
winit = {{ version = "0.30.13", features = ["android-native-activity"] }}
jni = "0.21"
android_log-sys = "0.3"
''')
print("patched crates/horizon-client/Cargo.toml (tail)")

if "--linux-cfg" in sys.argv:
    files = ["horizon-bench/src/hardware.rs", "horizon-client/src/crash.rs", "horizon-client/src/game.rs",
             "horizon-client/src/telemetry.rs", "horizon-friends/src/portmap.rs",
             "horizon-telemetry/src/system.rs", "horizon-threads/src/lib.rs"]
    for rel in files:
        p = root / "crates" / rel
        t = p.read_text()
        p.write_text(t.replace('target_os = "linux"', 'any(target_os = "linux", target_os = "android")'))
        print("linux->linux|android", rel)
