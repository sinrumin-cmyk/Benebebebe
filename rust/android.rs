//! Goes to crates/horizon-client/src/android.rs. Entry point and JNI for the Android shell.
#![allow(unsafe_code)]
use crate::{crash, gamepad::Button, logging, touch_input};
use jni::{
    JNIEnv,
    objects::JClass,
    sys::{jboolean, jfloat, jint},
};
use std::{ffi::CString, path::PathBuf, sync::OnceLock};
use winit::platform::android::activity::AndroidApp;

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
