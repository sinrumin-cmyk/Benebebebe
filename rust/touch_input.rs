//! Touch input from a platform overlay (the Android shell). Safe code only; the JNI side is android.rs.
//! The overlay draws the sticks and buttons and calls these functions; the game polls them once per loop.
use crate::gamepad::{Button, PadState};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use winit::keyboard::KeyCode;

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum PointerEvent {
    Down(f32, f32),
    Move(f32, f32),
    Up(f32, f32),
}

/// Something the game must see as a real keyboard or mouse event (the shell cannot build winit's
/// `KeyEvent` itself, so it queues these and the game turns them into events).
#[derive(Clone, Debug, PartialEq)]
pub enum Input {
    /// A key went down (true) or up.
    Key(KeyCode, bool),
    /// Characters typed on the on-screen keyboard.
    Text(String),
    /// A mouse moved by this many pixels (a real mouse, or the virtual one).
    MouseMove(f32, f32),
    /// A mouse button: 0 left, 1 right, 2 middle.
    MouseButton(u8, bool),
    /// A tap on the virtual mouse's pad: a left click that only counts where a cursor is shown.
    Tap(bool),
    /// The wheel, in notches, up positive.
    Wheel(f32),
}

/// A finger resting this long without moving holds attack (break a block). Only with touch gestures on.
const HOLD: Duration = Duration::from_millis(350);
/// A touch shorter than this that did not move is a tap (one attack / place, or a click on the virtual mouse).
const TAP: Duration = Duration::from_millis(250);
/// How long a tap holds the trigger: two game ticks, so the per-tick key sampling sees it.
const TAP_PRESS: Duration = Duration::from_millis(100);
/// Pixels a finger may drift and still count as still.
const SLOP: f32 = 12.0;

#[derive(Default)]
struct Shared {
    pad: PadState,
    look: (f64, f64),
    events: Vec<PointerEvent>,
    inputs: Vec<Input>,
    finger: Option<Finger>,
    tap_until: Option<Instant>,
    tap_release: Option<Instant>,
    virtual_mouse: bool,
    cursor: Option<(f32, f32)>,
    cursor_shown: bool,
}

struct Finger {
    start: (f32, f32),
    last: (f32, f32),
    down_at: Instant,
    moved: bool,
    holding: bool,
}

fn shared() -> &'static Mutex<Shared> {
    static S: OnceLock<Mutex<Shared>> = OnceLock::new();
    S.get_or_init(Default::default)
}

fn lock() -> std::sync::MutexGuard<'static, Shared> {
    shared().lock().unwrap_or_else(|e| e.into_inner())
}

static FRAMES: AtomicU64 = AtomicU64::new(0);
static WANTS_TEXT: AtomicBool = AtomicBool::new(false);
static HOTBAR_FINGER: AtomicBool = AtomicBool::new(false);

/// Tap = attack / place and a held finger = break (touch gestures). Off unless the launcher turns it on
/// (HORIZON_TOUCH_GESTURES=1): the on-screen buttons and the mouse do those.
pub fn gestures() -> bool {
    static G: OnceLock<bool> = OnceLock::new();
    *G.get_or_init(|| std::env::var("HORIZON_TOUCH_GESTURES").is_ok_and(|v| v == "1"))
}

pub fn set_button(button: Button, down: bool) {
    let mut s = lock();
    s.pad.set(button, down);
    // Triggers are analog on a pad: set both so either reading sees it.
    let v = if down { 1.0 } else { 0.0 };
    match button {
        Button::LeftTrigger => s.pad.triggers[0] = v,
        Button::RightTrigger => s.pad.triggers[1] = v,
        _ => {}
    }
}

pub fn set_stick(right: bool, x: f32, y: f32) {
    let v = [x.clamp(-1.0, 1.0), y.clamp(-1.0, 1.0)];
    let mut s = lock();
    if right {
        s.pad.right = v;
    } else {
        s.pad.left = v;
    }
}

/// The overlay's pad as of now (with touch gestures on, a tap or a held finger shows as the right trigger, attack).
pub fn pad() -> PadState {
    let mut s = lock();
    let mut pad = s.pad;
    let tapping = s.tap_until.is_some_and(|t| Instant::now() < t);
    if !tapping {
        s.tap_until = None;
    }
    let holding = s.finger.as_ref().is_some_and(|f| f.holding);
    if tapping || holding {
        pad.set(Button::RightTrigger, true);
        pad.triggers[1] = 1.0;
    }
    pad
}

/// Call from the game loop: promotes a still finger to a hold, and ends the virtual mouse's click.
pub fn tick() {
    let mut s = lock();
    let pad_mode = s.virtual_mouse;
    if gestures()
        && !pad_mode
        && let Some(f) = s.finger.as_mut()
        && !f.moved
        && !f.holding
        && f.down_at.elapsed() >= HOLD
    {
        f.holding = true;
    }
    if s.tap_release.is_some_and(|t| Instant::now() >= t) {
        s.tap_release = None;
        s.inputs.push(Input::Tap(false));
    }
}

/// Stops the finger on the screen from becoming a tap or a hold (it went on something else).
pub fn cancel_gesture() {
    if let Some(f) = lock().finger.as_mut() {
        f.moved = true;
        f.holding = false;
    }
}

/// The free finger (the one not on a stick or button): `phase` 0 down, 1 move, 2 up; pixels.
pub fn touch(phase: i32, x: f32, y: f32) {
    let mut s = lock();
    let pad_mode = s.virtual_mouse;
    match phase {
        0 => {
            s.finger = Some(Finger {
                start: (x, y),
                last: (x, y),
                down_at: Instant::now(),
                moved: false,
                holding: false,
            });
            if !pad_mode {
                s.events.push(PointerEvent::Down(x, y));
            }
        }
        1 => {
            let mut delta = (0.0f32, 0.0f32);
            if let Some(f) = s.finger.as_mut() {
                delta = (x - f.last.0, y - f.last.1);
                f.last = (x, y);
                if (x - f.start.0).hypot(y - f.start.1) > SLOP {
                    f.moved = true;
                }
            }
            if pad_mode {
                s.inputs.push(Input::MouseMove(delta.0, delta.1));
            } else {
                s.look.0 += f64::from(delta.0);
                s.look.1 += f64::from(delta.1);
                s.events.push(PointerEvent::Move(x, y));
            }
        }
        _ => {
            if let Some(f) = s.finger.take()
                && !f.moved
                && !f.holding
                && f.down_at.elapsed() < TAP
            {
                if pad_mode {
                    s.inputs.push(Input::Tap(true));
                    s.tap_release = Some(Instant::now() + TAP_PRESS);
                } else if gestures() {
                    s.tap_until = Some(Instant::now() + TAP_PRESS);
                }
            }
            if !pad_mode {
                s.events.push(PointerEvent::Up(x, y));
            }
        }
    }
}

pub fn take_look() -> (f64, f64) {
    std::mem::take(&mut lock().look)
}

pub fn take_pointer() -> Vec<PointerEvent> {
    std::mem::take(&mut lock().events)
}

// ---------------------------------------------------------------- keys, text, mouse

pub fn push_key(key: KeyCode, down: bool) {
    lock().inputs.push(Input::Key(key, down));
}

pub fn push_text(text: String) {
    if !text.is_empty() {
        lock().inputs.push(Input::Text(text));
    }
}

pub fn push_mouse_move(dx: f32, dy: f32) {
    if dx != 0.0 || dy != 0.0 {
        lock().inputs.push(Input::MouseMove(dx, dy));
    }
}

pub fn push_mouse_button(button: u8, down: bool) {
    lock().inputs.push(Input::MouseButton(button, down));
}

pub fn push_wheel(notches: f32) {
    if notches != 0.0 {
        lock().inputs.push(Input::Wheel(notches));
    }
}

pub fn take_inputs() -> Vec<Input> {
    std::mem::take(&mut lock().inputs)
}

/// The virtual mouse: the free finger moves a cursor like a touchpad, a tap clicks.
pub fn set_virtual_mouse(on: bool) {
    let mut s = lock();
    s.virtual_mouse = on;
    s.finger = None;
}

pub fn virtual_mouse() -> bool {
    lock().virtual_mouse
}

/// The cursor's place in pixels, starting in the middle of a `w` x `h` window.
pub fn cursor(w: f32, h: f32) -> (f32, f32) {
    lock().cursor.unwrap_or((w / 2.0, h / 2.0))
}

pub fn set_cursor(x: f32, y: f32) {
    lock().cursor = Some((x, y));
}

/// The game says whether a cursor should be drawn (menus, inventory), for the shell to paint.
pub fn set_cursor_shown(shown: bool) {
    lock().cursor_shown = shown;
}

/// The cursor packed for the shell: x in the high 32 bits, y in the low ones; -1 when none is shown.
pub fn cursor_packed() -> i64 {
    let s = lock();
    match (s.cursor_shown, s.cursor) {
        (true, Some((x, y))) => (i64::from(x.max(0.0) as i32) << 32) | i64::from(y.max(0.0) as i32),
        _ => -1,
    }
}

// ---------------------------------------------------------------- small shared facts

/// One game frame was drawn (the shell's FPS counter reads the total).
pub fn count_frame() {
    FRAMES.fetch_add(1, Ordering::Relaxed);
}

pub fn frames() -> u64 {
    FRAMES.load(Ordering::Relaxed)
}

/// The game has a text field in focus, so the shell should show the on-screen keyboard.
pub fn set_wants_text(wants: bool) {
    WANTS_TEXT.store(wants, Ordering::Relaxed);
}

pub fn wants_text() -> bool {
    WANTS_TEXT.load(Ordering::Relaxed)
}

/// The free finger went down on the hotbar: it selects a slot and must not turn the camera.
pub fn set_hotbar_finger(on: bool) {
    HOTBAR_FINGER.store(on, Ordering::Relaxed);
}

pub fn hotbar_finger() -> bool {
    HOTBAR_FINGER.load(Ordering::Relaxed)
}

// ---------------------------------------------------------------- key codes

const LETTERS: [KeyCode; 26] = [
    KeyCode::KeyA, KeyCode::KeyB, KeyCode::KeyC, KeyCode::KeyD, KeyCode::KeyE, KeyCode::KeyF, KeyCode::KeyG,
    KeyCode::KeyH, KeyCode::KeyI, KeyCode::KeyJ, KeyCode::KeyK, KeyCode::KeyL, KeyCode::KeyM, KeyCode::KeyN,
    KeyCode::KeyO, KeyCode::KeyP, KeyCode::KeyQ, KeyCode::KeyR, KeyCode::KeyS, KeyCode::KeyT, KeyCode::KeyU,
    KeyCode::KeyV, KeyCode::KeyW, KeyCode::KeyX, KeyCode::KeyY, KeyCode::KeyZ,
];

const DIGITS: [KeyCode; 10] = [
    KeyCode::Digit0, KeyCode::Digit1, KeyCode::Digit2, KeyCode::Digit3, KeyCode::Digit4,
    KeyCode::Digit5, KeyCode::Digit6, KeyCode::Digit7, KeyCode::Digit8, KeyCode::Digit9,
];

const FUNCTION: [KeyCode; 12] = [
    KeyCode::F1, KeyCode::F2, KeyCode::F3, KeyCode::F4, KeyCode::F5, KeyCode::F6,
    KeyCode::F7, KeyCode::F8, KeyCode::F9, KeyCode::F10, KeyCode::F11, KeyCode::F12,
];

fn index(code: i32, first: i32, len: usize) -> Option<usize> {
    let i = code.checked_sub(first)?;
    usize::try_from(i).ok().filter(|i| *i < len)
}

/// An Android `KeyEvent.KEYCODE_*` as a winit key.
pub fn from_android(code: i32) -> Option<KeyCode> {
    if let Some(i) = index(code, 29, 26) {
        return Some(LETTERS[i]);
    }
    if let Some(i) = index(code, 7, 10) {
        return Some(DIGITS[i]);
    }
    if let Some(i) = index(code, 131, 12) {
        return Some(FUNCTION[i]);
    }
    Some(match code {
        61 => KeyCode::Tab,
        66 => KeyCode::Enter,
        160 => KeyCode::NumpadEnter,
        62 => KeyCode::Space,
        67 => KeyCode::Backspace,
        112 => KeyCode::Delete,
        111 => KeyCode::Escape,
        19 => KeyCode::ArrowUp,
        20 => KeyCode::ArrowDown,
        21 => KeyCode::ArrowLeft,
        22 => KeyCode::ArrowRight,
        122 => KeyCode::Home,
        123 => KeyCode::End,
        92 => KeyCode::PageUp,
        93 => KeyCode::PageDown,
        124 => KeyCode::Insert,
        59 => KeyCode::ShiftLeft,
        60 => KeyCode::ShiftRight,
        113 => KeyCode::ControlLeft,
        114 => KeyCode::ControlRight,
        57 => KeyCode::AltLeft,
        58 => KeyCode::AltRight,
        117 => KeyCode::SuperLeft,
        118 => KeyCode::SuperRight,
        115 => KeyCode::CapsLock,
        55 => KeyCode::Comma,
        56 => KeyCode::Period,
        76 => KeyCode::Slash,
        74 => KeyCode::Semicolon,
        75 => KeyCode::Quote,
        71 => KeyCode::BracketLeft,
        72 => KeyCode::BracketRight,
        73 => KeyCode::Backslash,
        69 => KeyCode::Minus,
        70 => KeyCode::Equal,
        68 => KeyCode::Backquote,
        _ => return None,
    })
}

/// A GLFW key code (the old Mojo layouts, version 8 and below) as a winit key.
pub fn from_glfw(code: i32) -> Option<KeyCode> {
    if let Some(i) = index(code, 65, 26) {
        return Some(LETTERS[i]);
    }
    if let Some(i) = index(code, 48, 10) {
        return Some(DIGITS[i]);
    }
    if let Some(i) = index(code, 290, 12) {
        return Some(FUNCTION[i]);
    }
    Some(match code {
        32 => KeyCode::Space,
        256 => KeyCode::Escape,
        257 => KeyCode::Enter,
        335 => KeyCode::NumpadEnter,
        258 => KeyCode::Tab,
        259 => KeyCode::Backspace,
        260 => KeyCode::Insert,
        261 => KeyCode::Delete,
        262 => KeyCode::ArrowRight,
        263 => KeyCode::ArrowLeft,
        264 => KeyCode::ArrowDown,
        265 => KeyCode::ArrowUp,
        266 => KeyCode::PageUp,
        267 => KeyCode::PageDown,
        268 => KeyCode::Home,
        269 => KeyCode::End,
        280 => KeyCode::CapsLock,
        340 => KeyCode::ShiftLeft,
        341 => KeyCode::ControlLeft,
        342 => KeyCode::AltLeft,
        343 => KeyCode::SuperLeft,
        344 => KeyCode::ShiftRight,
        345 => KeyCode::ControlRight,
        346 => KeyCode::AltRight,
        347 => KeyCode::SuperRight,
        44 => KeyCode::Comma,
        45 => KeyCode::Minus,
        46 => KeyCode::Period,
        47 => KeyCode::Slash,
        59 => KeyCode::Semicolon,
        61 => KeyCode::Equal,
        91 => KeyCode::BracketLeft,
        92 => KeyCode::Backslash,
        93 => KeyCode::BracketRight,
        96 => KeyCode::Backquote,
        39 => KeyCode::Quote,
        _ => return None,
    })
}

/// A key from the shell: `scheme` 0 is an Android key code, 1 a GLFW one.
pub fn key_from(scheme: i32, code: i32) -> Option<KeyCode> {
    if scheme == 1 { from_glfw(code) } else { from_android(code) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn android_keys() {
        assert_eq!(from_android(29), Some(KeyCode::KeyA));
        assert_eq!(from_android(54), Some(KeyCode::KeyZ));
        assert_eq!(from_android(7), Some(KeyCode::Digit0));
        assert_eq!(from_android(16), Some(KeyCode::Digit9));
        assert_eq!(from_android(133), Some(KeyCode::F3));
        assert_eq!(from_android(135), Some(KeyCode::F5));
        assert_eq!(from_android(61), Some(KeyCode::Tab));
        assert_eq!(from_android(-1), None);
        assert_eq!(from_android(9999), None);
    }

    #[test]
    fn glfw_keys() {
        assert_eq!(from_glfw(65), Some(KeyCode::KeyA));
        assert_eq!(from_glfw(292), Some(KeyCode::F3));
        assert_eq!(from_glfw(258), Some(KeyCode::Tab));
        assert_eq!(from_glfw(48), Some(KeyCode::Digit0));
        assert_eq!(from_glfw(0), None);
    }

    #[test]
    fn packed_cursor_round_trips() {
        set_cursor(321.0, 654.0);
        set_cursor_shown(true);
        let p = cursor_packed();
        assert_eq!((p >> 32) as i32, 321);
        assert_eq!((p & 0xFFFF_FFFF) as i32, 654);
        set_cursor_shown(false);
        assert_eq!(cursor_packed(), -1);
    }
}
