//! Goes to crates/horizon-client/src/game/touch.rs. Applies the shell's input each loop: made-up keys and
//! typed text, the mouse (real or virtual), taps on the hotbar, and the finger that turns the camera or
//! points in menus.
use super::{Game, Screen};
use horizon_client::{
    hud::HudBatch,
    touch_input::{self, Input, PointerEvent},
};
use std::{
    sync::atomic::{AtomicU8, Ordering},
    time::Instant,
};
use winit::{
    application::ApplicationHandler,
    dpi::PhysicalPosition,
    event::{DeviceId, ElementState, MouseButton, MouseScrollDelta, TouchPhase, WindowEvent},
    event_loop::ActiveEventLoop,
    keyboard::{KeyCode, PhysicalKey},
    window::WindowId,
};

/// The attack (bit 0) and use (bit 1) triggers as of the last loop, to find their edges.
static TRIGGERS: AtomicU8 = AtomicU8::new(0);

/// The hotbar slot under a pixel, if any. The bar is 182 x 22 GUI pixels at the bottom middle
/// (`hud_layout::hotbar`), 20 per slot; a little room above it makes it easier to hit.
fn hotbar_slot(x: f32, y: f32, w: f32, h: f32) -> Option<usize> {
    let scale = HudBatch::new([w as u32, h as u32]).scale;
    if scale <= 0.0 {
        return None;
    }
    let (gui_w, gui_h) = ((w / scale).ceil() as i32, (h / scale).ceil() as i32);
    let left = (gui_w / 2 - 91) as f32 * scale;
    let top = (gui_h - 22) as f32 * scale;
    if x < left || x >= left + 182.0 * scale || y < top - 6.0 * scale {
        return None;
    }
    Some((((x - left) / (20.0 * scale)) as usize).min(8))
}

impl Game<'_> {
    /// A text field has the focus: the shell then shows the on-screen keyboard.
    fn wants_text(&mut self) -> bool {
        self.screen == Screen::Chat
            || self.screen == Screen::CommandBlock
            || self.sign_edit.is_some()
            || self.book.is_some()
            || self.station_typing()
            || self.recipe_search_focused()
            || (self.screen == Screen::Creative && self.creative_screen.tab().search)
            || self.title.as_mut().is_some_and(|t| t.has_caret_field())
    }

    /// Feeds one key to the game as if the keyboard had sent it, text included.
    fn inject_key(
        &mut self,
        event_loop: &ActiveEventLoop,
        id: WindowId,
        key: KeyCode,
        text: Option<&str>,
        down: bool,
    ) {
        let Some(event) = horizon_client::android::key_event(PhysicalKey::Code(key), text, down, false) else {
            return;
        };
        self.window_event(
            event_loop,
            id,
            WindowEvent::KeyboardInput { device_id: DeviceId::dummy(), event, is_synthetic: false },
        );
    }

    pub(super) fn apply_touch_input(&mut self, event_loop: &ActiveEventLoop) {
        touch_input::tick();
        let Some(window) = self.window.clone() else {
            return;
        };
        let id = window.id();
        let size = window.inner_size();
        let (w, h) = (size.width as f32, size.height as f32);
        let device_id = DeviceId::dummy();

        let wants_text = self.wants_text();
        touch_input::set_wants_text(wants_text);
        let grabbed = self.captured && self.screen == Screen::Playing;
        touch_input::set_cursor_shown(!grabbed);
        // Playing with the view grabbed: moves turn the camera (raw pixels, as a grabbed mouse).
        let playing = grabbed && self.focused;

        // The virtual mouse's buttons: the layout's attack and use buttons click where the cursor is.
        if touch_input::virtual_mouse() && !grabbed {
            let pad = touch_input::pad();
            let now = u8::from(pad.triggers[1] > 0.5) | (u8::from(pad.triggers[0] > 0.5) << 1);
            let before = TRIGGERS.swap(now, Ordering::Relaxed);
            for (bit, button) in [(1u8, MouseButton::Left), (2u8, MouseButton::Right)] {
                if (now ^ before) & bit != 0 {
                    let state = if now & bit != 0 { ElementState::Pressed } else { ElementState::Released };
                    self.window_event(event_loop, id, WindowEvent::MouseInput { device_id, state, button });
                }
            }
        } else {
            TRIGGERS.store(0, Ordering::Relaxed);
        }

        for input in touch_input::take_inputs() {
            match input {
                Input::Key(key, down) => self.inject_key(event_loop, id, key, None, down),
                Input::Text(text) => {
                    // A key no binding uses, carrying the character: text fields read `event.text`.
                    for ch in text.chars() {
                        let typed = ch.to_string();
                        self.inject_key(event_loop, id, KeyCode::F24, Some(&typed), true);
                        self.inject_key(event_loop, id, KeyCode::F24, Some(&typed), false);
                    }
                }
                Input::MouseMove(dx, dy) => {
                    if playing {
                        self.mouse.on_move(0.0, 0.0, f64::from(dx), f64::from(dy), true, true);
                        self.last_input = Instant::now();
                    } else {
                        let (cx, cy) = touch_input::cursor(w, h);
                        let (x, y) = ((cx + dx).clamp(0.0, w), (cy + dy).clamp(0.0, h));
                        touch_input::set_cursor(x, y);
                        let position = PhysicalPosition::new(f64::from(x), f64::from(y));
                        self.window_event(event_loop, id, WindowEvent::CursorMoved { device_id, position });
                    }
                }
                Input::MouseButton(button, down) => {
                    let button = match button {
                        0 => MouseButton::Left,
                        1 => MouseButton::Right,
                        _ => MouseButton::Middle,
                    };
                    let state = if down { ElementState::Pressed } else { ElementState::Released };
                    self.window_event(event_loop, id, WindowEvent::MouseInput { device_id, state, button });
                }
                // A tap on the virtual mouse's pad clicks only where there is a cursor to click with.
                Input::Tap(down) => {
                    if !grabbed {
                        let state = if down { ElementState::Pressed } else { ElementState::Released };
                        self.window_event(
                            event_loop,
                            id,
                            WindowEvent::MouseInput { device_id, state, button: MouseButton::Left },
                        );
                    }
                }
                Input::Wheel(notches) => self.window_event(
                    event_loop,
                    id,
                    WindowEvent::MouseWheel {
                        device_id,
                        delta: MouseScrollDelta::LineDelta(0.0, notches),
                        phase: TouchPhase::Moved,
                    },
                ),
            }
        }

        let look = touch_input::take_look();
        let events = touch_input::take_pointer();
        if look == (0.0, 0.0) && events.is_empty() {
            return;
        }
        if playing {
            // A finger that goes down on the hotbar picks that slot and does not turn the camera.
            let mut on_hotbar = touch_input::hotbar_finger();
            for event in &events {
                match *event {
                    PointerEvent::Down(x, y) => {
                        if let Some(slot) = hotbar_slot(x, y, w, h) {
                            self.selected = slot;
                            on_hotbar = true;
                            touch_input::set_hotbar_finger(true);
                            touch_input::cancel_gesture();
                        }
                    }
                    PointerEvent::Up(..) => touch_input::set_hotbar_finger(false),
                    PointerEvent::Move(..) => {}
                }
            }
            if look != (0.0, 0.0) && !on_hotbar {
                self.mouse.on_move(0.0, 0.0, look.0, look.1, true, true);
                self.last_input = Instant::now();
            }
            return;
        }
        // Menus, inventory, chat: the finger is a mouse.
        touch_input::set_hotbar_finger(false);
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
