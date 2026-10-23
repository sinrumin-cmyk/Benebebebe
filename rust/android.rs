//! Goes to crates/horizon-client/src/android.rs. Entry point and JNI for the Android shell.
#![allow(unsafe_code)]
use crate::{crash, gamepad::Button, logging, touch_input};
use jni::{
    JNIEnv,
    objects::JClass,
    sys::{jboolean, jfloat, jint},
};
use std::sync::OnceLock;
use winit::platform::android::activity::AndroidApp;

static APP: OnceLock<AndroidApp> = OnceLock::new();

pub fn app() -> AndroidApp {
    APP.get().expect("android_main sets the app first").clone()
}

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    let home = app
        .external_data_path()
        .or_else(|| app.internal_data_path())
        .expect("no app data folder");
    // SAFETY: nothing else runs yet.
    unsafe { std::env::set_var("HORIZON_HOME", &home) };
    let _ = std::env::set_current_dir(&home); // options.txt is read from the working folder
    let _ = APP.set(app);
    logging::init(&home, "Render thread");
    crash::install(home.clone(), "client");
    if let Err(error) = crate::game::run() {
        log::error!("Horizon: {error}");
        crash::report_error(&home, "client", &*error);
    }
    logging::flush();
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
