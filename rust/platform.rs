//! Goes to crates/horizon-client/src/platform.rs. The one place that builds the event loop and reads the arguments.
use winit::event_loop::EventLoop;

#[cfg(target_os = "android")]
pub fn event_loop() -> crate::Result<EventLoop<()>> {
    use winit::platform::android::EventLoopBuilderExtAndroid;
    Ok(EventLoop::builder().with_android_app(crate::android::app()).build()?)
}

#[cfg(not(target_os = "android"))]
pub fn event_loop() -> crate::Result<EventLoop<()>> {
    Ok(EventLoop::new()?)
}

/// The game's arguments (without the program name). Android has none: the launcher writes them to game-args.txt.
pub fn args() -> Vec<String> {
    #[cfg(target_os = "android")]
    {
        crate::android::game_args()
    }
    #[cfg(not(target_os = "android"))]
    {
        std::env::args().skip(1).collect()
    }
}
