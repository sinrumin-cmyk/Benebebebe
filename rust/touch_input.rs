//! Touch input from a platform overlay (the Android shell). Safe code only; the JNI side is android.rs.
//! The overlay draws the sticks and buttons and calls these functions; the game polls them once per loop.
use crate::gamepad::{Button, PadState};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum PointerEvent {
    Down(f32, f32),
    Move(f32, f32),
    Up(f32, f32),
}

/// A finger resting this long without moving holds attack (break a block).
const HOLD: Duration = Duration::from_millis(350);
/// A touch shorter than this that did not move is a tap (one attack / place).
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
    finger: Option<Finger>,
    tap_until: Option<Instant>,
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

/// The overlay's pad as of now (a tap or a held finger shows as the right trigger, attack).
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

/// Call from the game loop: promotes a still finger to a hold.
pub fn tick() {
    let mut s = lock();
    if let Some(f) = s.finger.as_mut()
        && !f.moved
        && !f.holding
        && f.down_at.elapsed() >= HOLD
    {
        f.holding = true;
    }
}

/// The free finger (the one not on a stick or button): `phase` 0 down, 1 move, 2 up; pixels.
pub fn touch(phase: i32, x: f32, y: f32) {
    let mut s = lock();
    match phase {
        0 => {
            s.finger = Some(Finger {
                start: (x, y),
                last: (x, y),
                down_at: Instant::now(),
                moved: false,
                holding: false,
            });
            s.events.push(PointerEvent::Down(x, y));
        }
        1 => {
            let mut delta = (0.0, 0.0);
            if let Some(f) = s.finger.as_mut() {
                delta = (f64::from(x - f.last.0), f64::from(y - f.last.1));
                f.last = (x, y);
                if (x - f.start.0).hypot(y - f.start.1) > SLOP {
                    f.moved = true;
                }
            }
            s.look.0 += delta.0;
            s.look.1 += delta.1;
            s.events.push(PointerEvent::Move(x, y));
        }
        _ => {
            if let Some(f) = s.finger.take()
                && !f.moved
                && !f.holding
                && f.down_at.elapsed() < TAP
            {
                s.tap_until = Some(Instant::now() + TAP_PRESS);
            }
            s.events.push(PointerEvent::Up(x, y));
        }
    }
}

pub fn take_look() -> (f64, f64) {
    std::mem::take(&mut lock().look)
}

pub fn take_pointer() -> Vec<PointerEvent> {
    std::mem::take(&mut lock().events)
}
