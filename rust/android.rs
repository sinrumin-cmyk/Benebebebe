//! Goes to crates/horizon-client/src/android.rs. Entry point and JNI for the Android shell.
#![allow(unsafe_code)]
use crate::{crash, gamepad::Button, logging, touch_input};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jboolean, jfloat, jint, jlong},
};
use std::{ffi::CString, path::PathBuf, sync::OnceLock};
use winit::{
    event::{ElementState, KeyEvent},
    keyboard::{Key, KeyLocation, NamedKey, PhysicalKey, SmolStr},
    platform::android::activity::AndroidApp,
};

static APP: OnceLock<AndroidApp> = OnceLock::new();

pub fn app() -> AndroidApp {
    APP.get().expect("android_main sets the app first").clone()
}

/// A line in logcat under the tag "Horizon" (the launcher's Logs screen shows it). 4 info, 6 error.
fn alog(priority: i32, message: &str) {
    if let (Ok(tag), Ok(text)) = (CString::new("Horizon"), CString::new(message.replace('\0', " "))) {
        // SAFETY: both strings are NUL-terminated and outlive the call.
        unsafe {
            android_log_sys::__android_log_write(priority, tag.as_ptr(), text.as_ptr());
        }
    }
}

/// Panics go to logcat too (stderr goes nowhere on Android), then to whatever hook was there.
fn hook_panics() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        alog(6, &format!("panic: {info}"));
        previous(info);
    }));
}

/// One argument per line in game-args.txt, written by the launcher before it starts the game.
pub fn game_args() -> Vec<String> {
    let Some(home) = std::env::var_os("HORIZON_HOME") else {
        return Vec::new();
    };
    std::fs::read_to_string(PathBuf::from(home).join("game-args.txt"))
        .map(|t| t.lines().filter(|l| !l.is_empty()).map(str::to_owned).collect())
        .unwrap_or_default()
}

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    alog(4, "android_main reached");
    hook_panics();
    let home = app
        .external_data_path()
        .or_else(|| app.internal_data_path())
        .expect("no app data folder");
    alog(4, &format!("home = {}", home.display()));
    // SAFETY: nothing else runs yet.
    unsafe { std::env::set_var("HORIZON_HOME", &home) };
    // The launcher's HORIZON_* settings (an account's session server, say), one KEY=VALUE per line.
    if let Ok(text) = std::fs::read_to_string(home.join("game-env.txt")) {
        for line in text.lines() {
            if let Some((key, value)) = line.split_once('=')
                && key.starts_with("HORIZON_")
            {
                // SAFETY: nothing else runs yet.
                unsafe { std::env::set_var(key, value) };
            }
        }
    }
    let _ = std::env::set_current_dir(&home); // options.txt is read from the working folder
    let _ = APP.set(app);
    logging::init(&home, "Render thread");
    crash::install(home.clone(), "client");
    hook_panics(); // crash::install replaced the hook
    alog(4, &format!("starting the game, args = {:?}", game_args()));
    if let Err(error) = crate::game::run() {
        alog(6, &format!("game stopped with an error: {error}"));
        log::error!("Horizon: {error}");
        crash::report_error(&home, "client", &*error);
    }
    logging::flush();
    alog(4, "game exited");
}

// Button indices are positions in `Button::ALL` (south east west north lb rb lt rt select start ls rs up down left right).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeButton(
    _env: JNIEnv,
    _class: JClass,
    index: jint,
    down: jboolean,
) {
    if let Some(button) = usize::try_from(index).ok().and_then(|i| Button::ALL.get(i)) {
        touch_input::set_button(*button, down != 0);
    }
}

/// The head's orientation for phone VR, degrees: yaw grows turning right, pitch is positive looking down,
/// roll is positive tilting the head right (`VrSensors.java` computes them from the rotation sensor).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeHead(
    _env: JNIEnv,
    _class: JClass,
    yaw: jfloat,
    pitch: jfloat,
    roll: jfloat,
) {
    crate::gfx::vr::set_head(yaw, pitch, roll);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeStick(
    _env: JNIEnv,
    _class: JClass,
    right: jboolean,
    x: jfloat,
    y: jfloat,
) {
    touch_input::set_stick(right != 0, x, y);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeTouch(
    _env: JNIEnv,
    _class: JClass,
    phase: jint,
    x: jfloat,
    y: jfloat,
) {
    touch_input::touch(phase, x, y);
}

// ---------------------------------------------------------------- keys, text and mouse from the shell

/// `scheme` 0: an Android KEYCODE_*, 1: a GLFW key code (old Mojo layouts). Unknown codes do nothing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeKey(
    _env: JNIEnv,
    _class: JClass,
    scheme: jint,
    code: jint,
    down: jboolean,
) {
    if let Some(key) = touch_input::key_from(scheme, code) {
        touch_input::push_key(key, down != 0);
    }
}

/// Characters from the on-screen keyboard (any layout, any language).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeText(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) {
    if let Ok(s) = env.get_string(&text) {
        touch_input::push_text(String::from(s));
    }
}

/// A mouse moved by (dx, dy) pixels.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeMouseMove(
    _env: JNIEnv,
    _class: JClass,
    dx: jfloat,
    dy: jfloat,
) {
    touch_input::push_mouse_move(dx, dy);
}

/// A mouse button: 0 left, 1 right, 2 middle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeMouseButton(
    _env: JNIEnv,
    _class: JClass,
    button: jint,
    down: jboolean,
) {
    if let Ok(b) = u8::try_from(button) {
        touch_input::push_mouse_button(b, down != 0);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeScroll(
    _env: JNIEnv,
    _class: JClass,
    notches: jfloat,
) {
    touch_input::push_wheel(notches);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeVirtualMouse(
    _env: JNIEnv,
    _class: JClass,
    on: jboolean,
) {
    touch_input::set_virtual_mouse(on != 0);
}

/// Game frames drawn since the game started (the shell's FPS counter).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeFrames(_env: JNIEnv, _class: JClass) -> jlong {
    touch_input::frames() as jlong
}

/// True while a text field is in focus in the game.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeWantsText(_env: JNIEnv, _class: JClass) -> jboolean {
    jboolean::from(touch_input::wants_text())
}

/// The mouse cursor to draw: x in the high 32 bits, y in the low ones; -1 when none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_mobile_HorizonInput_nativeCursor(_env: JNIEnv, _class: JClass) -> jlong {
    touch_input::cursor_packed()
}

/// The `logical_key` for a key pressed with no text.
fn named(physical: PhysicalKey) -> Key {
    use winit::keyboard::KeyCode as K;
    let PhysicalKey::Code(code) = physical else {
        return Key::Unidentified(winit::keyboard::NativeKey::Unidentified);
    };
    Key::Named(match code {
        K::Enter | K::NumpadEnter => NamedKey::Enter,
        K::Backspace => NamedKey::Backspace,
        K::Tab => NamedKey::Tab,
        K::Escape => NamedKey::Escape,
        K::Delete => NamedKey::Delete,
        K::ArrowUp => NamedKey::ArrowUp,
        K::ArrowDown => NamedKey::ArrowDown,
        K::ArrowLeft => NamedKey::ArrowLeft,
        K::ArrowRight => NamedKey::ArrowRight,
        K::Home => NamedKey::Home,
        K::End => NamedKey::End,
        K::PageUp => NamedKey::PageUp,
        K::PageDown => NamedKey::PageDown,
        K::Space => NamedKey::Space,
        K::F1 => NamedKey::F1,
        K::F2 => NamedKey::F2,
        K::F3 => NamedKey::F3,
        K::F4 => NamedKey::F4,
        K::F5 => NamedKey::F5,
        K::F6 => NamedKey::F6,
        K::F7 => NamedKey::F7,
        K::F8 => NamedKey::F8,
        K::F9 => NamedKey::F9,
        K::F10 => NamedKey::F10,
        K::F11 => NamedKey::F11,
        K::F12 => NamedKey::F12,
        _ => return Key::Unidentified(winit::keyboard::NativeKey::Unidentified),
    })
}

/// A key event made up by the shell (F3 from a layout button, text from the on-screen keyboard...).
///
/// winit has no constructor for `KeyEvent` (its last field is private), so this lays a struct out exactly as
/// winit 0.30 declares it and reinterprets it. The sizes and alignments are compared when the game is built,
/// and the public fields are read back before the event is used; `None` means that did not hold, and the key
/// is dropped rather than risk a bad value.
pub fn key_event(physical: PhysicalKey, text: Option<&str>, down: bool, repeat: bool) -> Option<KeyEvent> {
    #[allow(dead_code)]
    struct Mirror {
        physical_key: PhysicalKey,
        logical_key: Key,
        text: Option<SmolStr>,
        location: KeyLocation,
        state: ElementState,
        repeat: bool,
        platform_specific: (),
    }
    const _: () = assert!(size_of::<Mirror>() == size_of::<KeyEvent>());
    const _: () = assert!(align_of::<Mirror>() == align_of::<KeyEvent>());

    let state = if down { ElementState::Pressed } else { ElementState::Released };
    let mirror = Mirror {
        physical_key: physical,
        logical_key: match text {
            Some(t) => Key::Character(SmolStr::new(t)),
            None => named(physical),
        },
        text: text.map(SmolStr::new),
        location: KeyLocation::Standard,
        state,
        repeat,
        platform_specific: (),
    };
    // SAFETY: `Mirror` has the fields of winit 0.30's `KeyEvent` in the same order (Android's
    // `KeyEventExtra` is an empty struct, like `()`), so the two have the same layout; size and alignment are
    // asserted above and the result is checked below.
    let event: KeyEvent = unsafe { std::mem::transmute::<Mirror, KeyEvent>(mirror) };
    if event.physical_key == physical && event.state == state && event.repeat == repeat && event.text.as_deref() == text {
        Some(event)
    } else {
        alog(6, "key_event: winit's KeyEvent does not have the expected layout, key dropped");
        None
    }
}
