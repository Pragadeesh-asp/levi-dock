//! Shared keyboard & trackpad.
//!
//! Low-level Windows hooks watch the laptop's input. Pushing the cursor past
//! the right edge of the screen (or Ctrl+Alt+D) hands input to the dock: events
//! are swallowed on Windows and replayed on the phone through `LeviInput`, a
//! small helper that runs over ADB and exposes a virtual USB keyboard + mouse
//! (/dev/uhid), so Android shows a real pointer. Pushing off the dock's left
//! edge (or Ctrl+Alt+D) gives input back. If the helper or the link dies,
//! input returns to the laptop immediately.

use std::collections::HashSet;
use std::io::{BufRead, BufReader, Write};
use std::os::windows::process::CommandExt;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, AtomicU8, Ordering};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use windows_sys::Win32::Foundation::{LPARAM, LRESULT, POINT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::WindowsAndMessaging::*;

const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const HELPER_JAR: &[u8] = include_bytes!("../../input-helper/build/levi-input.jar");
const REMOTE_JAR: &str = "/data/local/tmp/levi-input.jar";
const VK_D: u32 = 0x44;

pub struct Engine {
    pub dock: AtomicBool,
    pub ready: AtomicBool,
    pub clipboard_share: AtomicBool,
    /// Bumped on every state change so the link can push an `input` status.
    pub version: AtomicU64,
    /// Set when input moves to the dock (send laptop clipboard) / back (ask the dock for its clipboard).
    pub clip_to_phone: AtomicBool,
    pub clip_from_phone: AtomicBool,
    phone_w: AtomicI32,
    phone_h: AtomicI32,
    buttons: AtomicU8,
    tx: Mutex<Option<Sender<Vec<u8>>>>,
    st: Mutex<State>,
}

struct State {
    park: (i32, i32),
    est: (f64, f64),
    armed: bool,
    wheel: i32,
    hwheel: i32,
    edge_hits: u32,
    edge_since: Instant,
    ctrl: bool,
    alt: bool,
    mods: u8,
    keys: Vec<u8>,
    laptop_down: HashSet<u32>,
    phone_down: HashSet<u32>,
}

static ENGINE: OnceLock<Engine> = OnceLock::new();

pub fn engine() -> &'static Engine {
    ENGINE.get_or_init(|| Engine {
        dock: AtomicBool::new(false),
        ready: AtomicBool::new(false),
        clipboard_share: AtomicBool::new(true),
        version: AtomicU64::new(1),
        clip_to_phone: AtomicBool::new(false),
        clip_from_phone: AtomicBool::new(false),
        phone_w: AtomicI32::new(1920),
        phone_h: AtomicI32::new(1080),
        buttons: AtomicU8::new(0),
        tx: Mutex::new(None),
        st: Mutex::new(State {
            park: (0, 0),
            est: (0.0, 0.0),
            armed: false,
            wheel: 0,
            hwheel: 0,
            edge_hits: 0,
            edge_since: Instant::now(),
            ctrl: false,
            alt: false,
            mods: 0,
            keys: Vec::new(),
            laptop_down: HashSet::new(),
            phone_down: HashSet::new(),
        }),
    })
}

/// Starts the hook thread and the helper supervisor.
pub fn start(pick_serial: fn() -> Option<String>, adb: fn(&[&str]) -> Option<String>, adb_path: fn() -> String) {
    let _ = engine();
    thread::spawn(hook_thread);
    thread::spawn(move || helper_supervisor(pick_serial, adb, adb_path));
}

impl Engine {
    fn bump(&self) {
        self.version.fetch_add(1, Ordering::Relaxed);
    }

    fn send(&self, frame: Vec<u8>) {
        if let Ok(tx) = self.tx.lock() {
            if let Some(tx) = tx.as_ref() {
                let _ = tx.send(frame);
            }
        }
    }

    fn send_mouse(&self, dx: i32, dy: i32, wheel: i32, pan: i32) {
        let b = self.buttons.load(Ordering::Relaxed);
        let (x, y) = (dx.clamp(-32767, 32767) as i16, dy.clamp(-32767, 32767) as i16);
        let mut f = vec![1u8, 7, b];
        f.extend_from_slice(&x.to_le_bytes());
        f.extend_from_slice(&y.to_le_bytes());
        f.push(wheel.clamp(-127, 127) as i8 as u8);
        f.push(pan.clamp(-127, 127) as i8 as u8);
        self.send(f);
    }

    fn send_keys(&self, st: &State) {
        let mut f = vec![2u8, 8, st.mods, 0];
        for i in 0..6 {
            f.push(*st.keys.get(i).unwrap_or(&0));
        }
        self.send(f);
    }

    /// Moves input to the dock. Called from the hook thread (or a dock command).
    pub fn enter(&self) {
        if !self.ready.load(Ordering::Relaxed) || self.dock.swap(true, Ordering::Relaxed) {
            return;
        }
        let mut p = POINT { x: 0, y: 0 };
        unsafe { GetCursorPos(&mut p) };
        let (vx, vy, vw, vh) = virtual_screen();
        let (pw, ph) = (self.phone_w.load(Ordering::Relaxed), self.phone_h.load(Ordering::Relaxed));
        let yfrac = ((p.y - vy) as f64 / vh.max(1) as f64).clamp(0.0, 1.0);
        {
            let mut st = self.st.lock().unwrap();
            st.park = (vx + vw - 1, p.y);
            st.est = (0.0, yfrac * ph as f64);
            st.armed = false;
            st.wheel = 0;
            st.hwheel = 0;
        }
        unsafe { SetCursorPos(vx + vw - 1, p.y) };
        // Put the phone pointer at its left edge, at the matching height (small steps avoid acceleration).
        self.send_mouse(-(pw * 3), -(ph * 3), 0, 0);
        let mut left = (yfrac * ph as f64) as i32;
        while left > 0 {
            let s = left.min(24);
            self.send_mouse(0, s, 0, 0);
            left -= s;
        }
        let _ = pw;
        self.clip_to_phone.store(true, Ordering::Relaxed);
        self.bump();
        crate::log("shared input: → dock");
    }

    /// Gives input back to the laptop.
    pub fn exit(&self) {
        if !self.dock.swap(false, Ordering::Relaxed) {
            return;
        }
        self.buttons.store(0, Ordering::Relaxed);
        let (park, est_y) = {
            let mut st = self.st.lock().unwrap();
            st.mods = 0;
            st.keys.clear();
            self.send_keys(&st);
            (st.park, st.est.1)
        };
        self.send_mouse(0, 0, 0, 0);
        let (_, vy, _, vh) = virtual_screen();
        let ph = self.phone_h.load(Ordering::Relaxed).max(1) as f64;
        let y = vy + ((est_y / ph).clamp(0.0, 1.0) * vh as f64) as i32;
        unsafe { SetCursorPos(park.0 - 2, y) };
        let _ = park;
        self.clip_from_phone.store(true, Ordering::Relaxed);
        self.bump();
        crate::log("shared input: → laptop");
    }

    /// Returns true to swallow the event on Windows.
    fn on_mouse(&self, msg: u32, m: &MSLLHOOKSTRUCT) -> bool {
        if m.flags & LLMHF_INJECTED != 0 && !allow_injected() {
            return false;
        }
        if !self.dock.load(Ordering::Relaxed) {
            if msg == WM_MOUSEMOVE && self.ready.load(Ordering::Relaxed) {
                let (vx, _, vw, _) = virtual_screen();
                let right = vx + vw - 1;
                let push = {
                    let mut st = self.st.lock().unwrap();
                    if m.pt.x >= right {
                        if st.edge_since.elapsed() > Duration::from_millis(600) {
                            st.edge_hits = 0;
                            st.edge_since = Instant::now();
                        }
                        st.edge_hits += 1;
                        m.pt.x > right || st.edge_hits >= 4
                    } else {
                        st.edge_hits = 0;
                        false
                    }
                };
                if push {
                    self.enter();
                    return true;
                }
            }
            return false;
        }

        let pw = self.phone_w.load(Ordering::Relaxed) as f64;
        let ph = self.phone_h.load(Ordering::Relaxed) as f64;
        match msg {
            WM_MOUSEMOVE => {
                let (dx, dy, leave) = {
                    let mut st = self.st.lock().unwrap();
                    let (dx, dy) = (m.pt.x - st.park.0, m.pt.y - st.park.1);
                    if dx == 0 && dy == 0 {
                        return true;
                    }
                    let nx = st.est.0 + dx as f64;
                    st.est.0 = nx.clamp(0.0, pw);
                    st.est.1 = (st.est.1 + dy as f64).clamp(0.0, ph);
                    if st.est.0 > 60.0 {
                        st.armed = true;
                    }
                    (dx, dy, st.armed && nx < -24.0)
                };
                if leave {
                    self.exit();
                } else {
                    self.send_mouse(dx, dy, 0, 0);
                }
                true
            }
            WM_LBUTTONDOWN | WM_RBUTTONDOWN | WM_MBUTTONDOWN | WM_XBUTTONDOWN | WM_LBUTTONUP | WM_RBUTTONUP
            | WM_MBUTTONUP | WM_XBUTTONUP => {
                let bit: u8 = match msg {
                    WM_LBUTTONDOWN | WM_LBUTTONUP => 1,
                    WM_RBUTTONDOWN | WM_RBUTTONUP => 2,
                    WM_MBUTTONDOWN | WM_MBUTTONUP => 4,
                    _ => if (m.mouseData >> 16) == 1 { 8 } else { 16 },
                };
                let down = matches!(msg, WM_LBUTTONDOWN | WM_RBUTTONDOWN | WM_MBUTTONDOWN | WM_XBUTTONDOWN);
                let b = self.buttons.load(Ordering::Relaxed);
                if !down && b & bit == 0 {
                    return false; // pressed on the laptop before switching: let Windows see the release
                }
                self.buttons.store(if down { b | bit } else { b & !bit }, Ordering::Relaxed);
                self.send_mouse(0, 0, 0, 0);
                true
            }
            WM_MOUSEWHEEL | WM_MOUSEHWHEEL => {
                let delta = (m.mouseData >> 16) as u16 as i16 as i32;
                let steps = {
                    let mut st = self.st.lock().unwrap();
                    let acc = if msg == WM_MOUSEWHEEL { &mut st.wheel } else { &mut st.hwheel };
                    *acc += delta;
                    let s = *acc / 120;
                    *acc -= s * 120;
                    s
                };
                if steps != 0 {
                    if msg == WM_MOUSEWHEEL { self.send_mouse(0, 0, steps, 0) } else { self.send_mouse(0, 0, 0, steps) }
                }
                true
            }
            _ => true,
        }
    }

    /// Returns true to swallow the key on Windows.
    fn on_key(&self, msg: u32, k: &KBDLLHOOKSTRUCT) -> bool {
        if k.flags & LLKHF_INJECTED != 0 && !allow_injected() {
            return false;
        }
        let vk = k.vkCode;
        let down = msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN;
        let dock = self.dock.load(Ordering::Relaxed);
        let mut st = self.st.lock().unwrap();
        match vk {
            0xA2 | 0xA3 | 0x11 => st.ctrl = down,
            0xA4 | 0xA5 | 0x12 => st.alt = down,
            _ => {}
        }

        // Ctrl+Alt+D toggles where input goes.
        if vk == VK_D && down && st.ctrl && st.alt {
            drop(st);
            if dock { self.exit() } else { self.enter() }
            return true;
        }
        if vk == VK_D && !down && (st.ctrl && st.alt) {
            return true;
        }

        if !dock {
            if down {
                st.laptop_down.insert(vk);
                false
            } else if st.phone_down.remove(&vk) {
                true // released after input came back; Windows never saw it pressed
            } else {
                st.laptop_down.remove(&vk);
                false
            }
        } else {
            if !down && st.laptop_down.remove(&vk) {
                return false; // held on the laptop before switching
            }
            let Some((usage, modbit)) = hid_usage(vk) else {
                return false; // media/volume keys etc. stay with the laptop
            };
            if down { st.phone_down.insert(vk); } else { st.phone_down.remove(&vk); }
            if modbit != 0 {
                if down { st.mods |= modbit } else { st.mods &= !modbit }
            } else if down {
                if !st.keys.contains(&usage) && st.keys.len() < 6 {
                    st.keys.push(usage);
                }
            } else {
                st.keys.retain(|u| *u != usage);
            }
            self.send_keys(&st);
            true
        }
    }
}

/// Test hook: LEVI_ALLOW_INJECTED=1 lets synthetic (SendInput) events through.
fn allow_injected() -> bool {
    static ALLOW: OnceLock<bool> = OnceLock::new();
    *ALLOW.get_or_init(|| std::env::var("LEVI_ALLOW_INJECTED").as_deref() == Ok("1"))
}

fn virtual_screen() -> (i32, i32, i32, i32) {
    unsafe {
        (
            GetSystemMetrics(SM_XVIRTUALSCREEN),
            GetSystemMetrics(SM_YVIRTUALSCREEN),
            GetSystemMetrics(SM_CXVIRTUALSCREEN),
            GetSystemMetrics(SM_CYVIRTUALSCREEN),
        )
    }
}

unsafe extern "system" fn mouse_proc(code: i32, w: WPARAM, l: LPARAM) -> LRESULT {
    if code == HC_ACTION as i32 && l != 0 {
        let m = unsafe { &*(l as *const MSLLHOOKSTRUCT) };
        if engine().on_mouse(w as u32, m) {
            return 1;
        }
    }
    unsafe { CallNextHookEx(std::ptr::null_mut(), code, w, l) }
}

unsafe extern "system" fn key_proc(code: i32, w: WPARAM, l: LPARAM) -> LRESULT {
    if code == HC_ACTION as i32 && l != 0 {
        let k = unsafe { &*(l as *const KBDLLHOOKSTRUCT) };
        if engine().on_key(w as u32, k) {
            return 1;
        }
    }
    unsafe { CallNextHookEx(std::ptr::null_mut(), code, w, l) }
}

fn hook_thread() {
    unsafe {
        let module = GetModuleHandleW(std::ptr::null());
        let mh = SetWindowsHookExW(WH_MOUSE_LL, Some(mouse_proc), module, 0);
        let kh = SetWindowsHookExW(WH_KEYBOARD_LL, Some(key_proc), module, 0);
        if mh.is_null() || kh.is_null() {
            crate::log("shared input: could not install keyboard/mouse hooks");
            return;
        }
        crate::log("shared input: hooks installed (Ctrl+Alt+D or right screen edge → dock)");
        if allow_injected() {
            crate::log("shared input: TEST MODE — synthetic input is accepted");
        }
        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
}

/// Keeps `LeviInput` running on the phone and feeds it HID frames.
fn helper_supervisor(pick_serial: fn() -> Option<String>, adb: fn(&[&str]) -> Option<String>, adb_path: fn() -> String) {
    let jar_local = std::env::temp_dir().join("levi-input.jar");
    loop {
        let Some(serial) = pick_serial() else {
            thread::sleep(Duration::from_secs(3));
            continue;
        };
        if std::fs::write(&jar_local, HELPER_JAR).is_err()
            || adb(&["-s", &serial, "push", &jar_local.to_string_lossy(), REMOTE_JAR]).is_none()
        {
            thread::sleep(Duration::from_secs(3));
            continue;
        }
        let child = Command::new(adb_path())
            .args(["-s", &serial, "shell", &format!("CLASSPATH={REMOTE_JAR} app_process / LeviInput")])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .creation_flags(CREATE_NO_WINDOW)
            .spawn();
        let Ok(mut child) = child else {
            thread::sleep(Duration::from_secs(3));
            continue;
        };
        let mut out = BufReader::new(child.stdout.take().unwrap());
        let mut line = String::new();
        let ready = out.read_line(&mut line).is_ok() && line.trim() == "ready";
        if !ready {
            crate::log("shared input: helper did not start on the phone");
            let _ = child.kill();
            thread::sleep(Duration::from_secs(5));
            continue;
        }
        update_phone_size(&serial, adb);
        let (tx, rx): (Sender<Vec<u8>>, Receiver<Vec<u8>>) = channel();
        *engine().tx.lock().unwrap() = Some(tx.clone());
        engine().ready.store(true, Ordering::Relaxed);
        engine().bump();
        crate::log("shared input: LEVI Keyboard + Mouse ready on the dock");

        // Keep-alive + screen orientation refresh while connected.
        let alive = std::sync::Arc::new(AtomicBool::new(true));
        {
            let (alive, serial) = (alive.clone(), serial.clone());
            thread::spawn(move || {
                while alive.load(Ordering::Relaxed) {
                    thread::sleep(Duration::from_secs(3));
                    if engine().dock.load(Ordering::Relaxed) {
                        update_phone_size(&serial, adb);
                    }
                    let _ = tx.send(vec![9u8, 0]);
                }
            });
        }

        let mut stdin = child.stdin.take().unwrap();
        for frame in rx {
            if stdin.write_all(&frame).and_then(|_| stdin.flush()).is_err() {
                break;
            }
        }
        alive.store(false, Ordering::Relaxed);
        *engine().tx.lock().unwrap() = None;
        engine().ready.store(false, Ordering::Relaxed);
        engine().exit();
        engine().bump();
        let _ = child.kill();
        crate::log("shared input: helper stopped; input is back on the laptop");
        thread::sleep(Duration::from_secs(3));
    }
}

/// Phone screen size in its current orientation (BitChord may be portrait).
fn update_phone_size(serial: &str, adb: fn(&[&str]) -> Option<String>) {
    let Some(out) = adb(&["-s", serial, "shell", "wm size; dumpsys input | grep -m1 SurfaceOrientation"]) else { return };
    let mut w = 1080;
    let mut h = 1920;
    let mut rot = 1;
    for line in out.lines() {
        if let Some(sz) = line.split(':').nth(1).filter(|_| line.contains("size")) {
            if let Some((a, b)) = sz.trim().split_once('x') {
                w = a.trim().parse().unwrap_or(w);
                h = b.trim().parse().unwrap_or(h);
            }
        }
        if line.contains("SurfaceOrientation") {
            rot = line.split(':').nth(1).and_then(|s| s.trim().parse().ok()).unwrap_or(rot);
        }
    }
    let (pw, ph) = if rot % 2 == 1 { (w.max(h), w.min(h)) } else { (w.min(h), w.max(h)) };
    engine().phone_w.store(pw, Ordering::Relaxed);
    engine().phone_h.store(ph, Ordering::Relaxed);
}

/// Windows virtual-key → (HID usage, modifier bit).
fn hid_usage(vk: u32) -> Option<(u8, u8)> {
    let u = match vk {
        0xA2 => return Some((0, 0x01)), // LCtrl
        0xA0 => return Some((0, 0x02)), // LShift
        0xA4 => return Some((0, 0x04)), // LAlt
        0x5B => return Some((0, 0x08)), // LWin → Meta
        0xA3 => return Some((0, 0x10)),
        0xA1 => return Some((0, 0x20)),
        0xA5 => return Some((0, 0x40)),
        0x5C => return Some((0, 0x80)),
        0x41..=0x5A => 0x04 + (vk - 0x41) as u8,
        0x31..=0x39 => 0x1E + (vk - 0x31) as u8,
        0x30 => 0x27,
        0x0D => 0x28,
        0x1B => 0x29,
        0x08 => 0x2A,
        0x09 => 0x2B,
        0x20 => 0x2C,
        0xBD => 0x2D,
        0xBB => 0x2E,
        0xDB => 0x2F,
        0xDD => 0x30,
        0xDC => 0x31,
        0xBA => 0x33,
        0xDE => 0x34,
        0xC0 => 0x35,
        0xBC => 0x36,
        0xBE => 0x37,
        0xBF => 0x38,
        0x14 => 0x39,
        0x70..=0x7B => 0x3A + (vk - 0x70) as u8,
        0x2C => 0x46,
        0x91 => 0x47,
        0x13 => 0x48,
        0x2D => 0x49,
        0x24 => 0x4A,
        0x21 => 0x4B,
        0x2E => 0x4C,
        0x23 => 0x4D,
        0x22 => 0x4E,
        0x27 => 0x4F,
        0x25 => 0x50,
        0x28 => 0x51,
        0x26 => 0x52,
        0x90 => 0x53,
        0x6F => 0x54,
        0x6A => 0x55,
        0x6D => 0x56,
        0x6B => 0x57,
        0x61..=0x69 => 0x59 + (vk - 0x61) as u8,
        0x60 => 0x62,
        0x6E => 0x63,
        0xE2 => 0x64,
        0x5D => 0x65,
        _ => return None,
    };
    Some((u, 0))
}

// ---- Windows clipboard (text) ----

pub fn clipboard_get() -> Option<String> {
    use windows_sys::Win32::System::DataExchange::{CloseClipboard, GetClipboardData, OpenClipboard};
    use windows_sys::Win32::System::Memory::{GlobalLock, GlobalUnlock};
    use windows_sys::Win32::System::Ole::CF_UNICODETEXT;
    unsafe {
        if OpenClipboard(std::ptr::null_mut()) == 0 {
            return None;
        }
        let h = GetClipboardData(CF_UNICODETEXT as u32);
        let mut out = None;
        if !h.is_null() {
            let p = GlobalLock(h) as *const u16;
            if !p.is_null() {
                let mut n = 0;
                while n < 65_536 && *p.add(n) != 0 {
                    n += 1;
                }
                out = Some(String::from_utf16_lossy(std::slice::from_raw_parts(p, n)));
                GlobalUnlock(h);
            }
        }
        CloseClipboard();
        out
    }
}

pub fn clipboard_set(text: &str) -> bool {
    use windows_sys::Win32::System::DataExchange::{CloseClipboard, EmptyClipboard, OpenClipboard, SetClipboardData};
    use windows_sys::Win32::System::Memory::{GMEM_MOVEABLE, GlobalAlloc, GlobalLock, GlobalUnlock};
    use windows_sys::Win32::System::Ole::CF_UNICODETEXT;
    let wide: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        if OpenClipboard(std::ptr::null_mut()) == 0 {
            return false;
        }
        EmptyClipboard();
        let h = GlobalAlloc(GMEM_MOVEABLE, wide.len() * 2);
        let mut ok = false;
        if !h.is_null() {
            let p = GlobalLock(h) as *mut u16;
            if !p.is_null() {
                std::ptr::copy_nonoverlapping(wide.as_ptr(), p, wide.len());
                GlobalUnlock(h);
                ok = !SetClipboardData(CF_UNICODETEXT as u32, h).is_null();
            }
        }
        CloseClipboard();
        ok
    }
}
