//! Goes to crates/horizon-client/src/game/touch.rs. Applies the overlay's input each loop.
use super::{Game, Screen};
use horizon_client::touch_input::{self, PointerEvent};
use std::time::Instant;
use winit::{
    application::ApplicationHandler,
    dpi::PhysicalPosition,
    event::{DeviceId, ElementState, MouseButton, WindowEvent},
    event_loop::ActiveEventLoop,
};

impl Game<'_> {
    pub(super) fn apply_touch_input(&mut self, event_loop: &ActiveEventLoop) {
        touch_input::tick();
        let look = touch_input::take_look();
        let events = touch_input::take_pointer();
        if look == (0.0, 0.0) && events.is_empty() {
            return;
        }
        // Playing with the view grabbed: finger drags turn the camera (raw pixels, as a grabbed mouse).
        if self.captured && self.focused && self.screen == Screen::Playing {
            if look != (0.0, 0.0) {
                self.mouse.on_move(0.0, 0.0, look.0, look.1, true, true);
                self.last_input = Instant::now();
            }
            return;
        }
        // Menus, inventory, chat: the finger is a mouse.
        let Some(id) = self.window.as_ref().map(|w| w.id()) else {
            return;
        };
        let device_id = DeviceId::dummy();
        for event in events {
            let (x, y, state) = match event {
                PointerEvent::Down(x, y) => (x, y, Some(ElementState::Pressed)),
                PointerEvent::Move(x, y) => (x, y, None),
                PointerEvent::Up(x, y) => (x, y, Some(ElementState::Released)),
            };
            let position = PhysicalPosition::new(f64::from(x), f64::from(y));
            self.window_event(event_loop, id, WindowEvent::CursorMoved { device_id, position });
            if let Some(state) = state {
                self.window_event(
                    event_loop,
                    id,
                    WindowEvent::MouseInput { device_id, state, button: MouseButton::Left },
                );
            }
        }
    }
}
