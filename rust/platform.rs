//! Goes to crates/horizon-client/src/platform.rs. The one place that builds the event loop.
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
