//! LEVI DOCK host.
//!
//! Streams laptop metrics, dev and ecosystem status to the LEVI DOCK app on
//! the Mi 3W, runs the shared keyboard & trackpad, and accepts a small set of
//! token-authenticated commands from the dock. The phone app listens on its own
//! loopback 127.0.0.1:47800; this host reaches it through
//! `adb forward tcp:47800 tcp:47800`, so nothing listens on the network.
//! Messages are newline-delimited JSON (see docs/protocol-v1.md).

mod ai;
mod dev;
mod input;
mod quick;
mod voice;

use serde_json::{Value, json};
use std::io::{BufRead, BufReader, Write};
use std::net::{SocketAddr, TcpStream};
use std::os::windows::process::CommandExt;
use std::process::Command;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use sysinfo::{Components, Disks, Networks, System};

const PORT: u16 = 47800;
const APP_ACTIVITY: &str = "com.levi.dock/.MainActivity";
const TOKEN_RECEIVER: &str = "com.levi.dock/.TokenReceiver";
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

fn main() {
    if std::env::args().any(|a| a == "--dump-dev") {
        println!("{:#}", dev::DevSampler::new().sample());
        return;
    }
    if std::env::args().any(|a| a == "--set-ai-key") {
        ai::set_key_interactive(false);
        return;
    }
    if std::env::args().any(|a| a == "--set-voice-key") {
        ai::set_key_interactive(true);
        return;
    }
    if let Some(pos) = std::env::args().position(|a| a == "--voice-check") {
        ai::voice_check(std::env::args().nth(pos + 1).as_deref());
        return;
    }
    // levi-host --transcribe clip.wav  (16 kHz mono PCM16 WAV): tests the speech model.
    if let Some(pos) = std::env::args().position(|a| a == "--transcribe") {
        let path = std::env::args().nth(pos + 1).unwrap_or_default();
        let bytes = std::fs::read(&path).unwrap_or_default();
        let pcm: Vec<i16> = bytes.get(44..).unwrap_or(&[]).chunks_exact(2).map(|p| i16::from_le_bytes([p[0], p[1]])).collect();
        match ai::transcribe(&pcm, 16_000) {
            Ok(t) => println!("transcript: {t:?}\naction: {:?}", voice::map_command(&t)),
            Err(e) => println!("error: {e}"),
        }
        return;
    }
    if std::env::args().any(|a| a == "--check-ai-key") {
        ai::check_key();
        return;
    }
    log(&format!("LEVI DOCK host {} starting", env!("CARGO_PKG_VERSION")));
    let token = load_token();
    let mut sampler = Sampler::new();
    input::start(pick_serial, adb, adb_path);
    voice::start(&token);

    // Dev/ecosystem data is slower to gather (git, netstat): refresh it on its own thread.
    let dev_latest: Arc<Mutex<(u64, Value)>> = Arc::new(Mutex::new((0, json!(null))));
    {
        let dev_latest = dev_latest.clone();
        thread::spawn(move || {
            let mut dev = dev::DevSampler::new();
            loop {
                let v = dev.sample();
                if let Ok(mut slot) = dev_latest.lock() {
                    slot.0 += 1;
                    slot.1 = v;
                }
                thread::sleep(Duration::from_secs(5));
            }
        });
    }

    loop {
        let Some(serial) = pick_serial() else {
            log("no authorized Mi 3W found by adb; retrying");
            thread::sleep(Duration::from_secs(3));
            continue;
        };
        let port = format!("tcp:{PORT}");
        if adb(&["-s", &serial, "forward", &port, &port]).is_none() {
            log("adb forward failed; retrying");
            thread::sleep(Duration::from_secs(3));
            continue;
        }
        // "Hey LEVI" audio: dock mic → laptop, laptop voice → dock speaker.
        for p in [voice::MIC_PORT, voice::SPEAK_PORT] {
            let spec = format!("tcp:{p}");
            let _ = adb(&["-s", &serial, "forward", &spec, &spec]);
        }
        provision_token(&serial, &token);
        let started = Instant::now();
        match run_session(&mut sampler, dev_latest.clone(), &token) {
            Ok(()) => log("link closed"),
            Err(e) => log(&format!("link ended: {e}")),
        }
        // A link that dies at once usually means the app is not running: start it.
        if started.elapsed() < Duration::from_secs(5) {
            log("starting LEVI DOCK app on the phone");
            let _ = adb(&["-s", &serial, "shell", "am", "start", "-n", APP_ACTIVITY]);
        }
        thread::sleep(Duration::from_secs(2));
    }
}

type Writer = Arc<Mutex<TcpStream>>;

fn run_session(sampler: &mut Sampler, dev_latest: Arc<Mutex<(u64, Value)>>, token: &str) -> std::io::Result<()> {
    let mut dev_sent = 0u64;
    let mut input_sent = 0u64;
    let addr = SocketAddr::from(([127, 0, 0, 1], PORT));
    let stream = TcpStream::connect_timeout(&addr, Duration::from_secs(3))?;
    stream.set_nodelay(true)?;
    let reader = stream.try_clone()?;
    let stream: Writer = Arc::new(Mutex::new(stream));
    send(&stream, &ai::status())?;

    let rtt = Arc::new(AtomicI64::new(-1));
    let alive = Arc::new(AtomicBool::new(true));
    {
        let (rtt, alive, token) = (rtt.clone(), alive.clone(), token.to_string());
        let (writer, dev_latest) = (stream.clone(), dev_latest.clone());
        thread::spawn(move || {
            for line in BufReader::new(reader).lines() {
                let Ok(line) = line else { break };
                if line.len() > 70_000 {
                    continue;
                }
                let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
                if v["type"] == "pong" {
                    if let Some(t) = v["t"].as_i64() {
                        rtt.store(now_ms() - t, Ordering::Relaxed);
                    }
                } else if v["type"] == "cmd" {
                    handle_command(&v, &token, &writer, &dev_latest);
                }
            }
            alive.store(false, Ordering::Relaxed);
        });
    }

    log("linked to the dock");
    let eng = input::engine();
    let _ = voice::drain(); // nothing heard while unlinked should fire now
    let mut tick: u64 = 0;
    loop {
        if !alive.load(Ordering::Relaxed) {
            return Err(std::io::Error::other("dock closed the link"));
        }
        if tick % 2 == 0 {
            send(&stream, &json!({"type": "ping", "t": now_ms()}))?;
        }
        let mut m = sampler.sample();
        m["rtt"] = json!(rtt.load(Ordering::Relaxed));
        send(&stream, &m)?;

        let dev_msg = dev_latest.lock().ok().and_then(|slot| (slot.0 != dev_sent).then(|| (slot.0, slot.1.clone())));
        if let Some((version, v)) = dev_msg {
            if !v.is_null() {
                send(&stream, &v)?;
            }
            dev_sent = version;
        }

        // Input status + clipboard hand-off, checked every 100 ms within the 1 s tick.
        for _ in 0..10 {
            let v = eng.version.load(Ordering::Relaxed);
            if v != input_sent {
                input_sent = v;
                send(&stream, &json!({
                    "type": "input",
                    "mode": if eng.dock.load(Ordering::Relaxed) { "dock" } else { "laptop" },
                    "ready": eng.ready.load(Ordering::Relaxed),
                    "clipboard": eng.clipboard_share.load(Ordering::Relaxed),
                }))?;
            }
            if eng.clip_to_phone.swap(false, Ordering::Relaxed) && eng.clipboard_share.load(Ordering::Relaxed) {
                if let Some(text) = input::clipboard_get().filter(|t| !t.is_empty()) {
                    send(&stream, &json!({"type": "clipboard", "text": text}))?;
                }
            }
            if eng.clip_from_phone.swap(false, Ordering::Relaxed) && eng.clipboard_share.load(Ordering::Relaxed) {
                send(&stream, &json!({"type": "clipboard-request"}))?;
            }
            for heard in voice::drain() {
                send(&stream, &voice::banner(&heard))?;
                handle_voice(heard.action, &stream, &dev_latest);
            }
            thread::sleep(Duration::from_millis(100));
        }
        tick += 1;
    }
}

/// Commands from the dock. Every command must carry the pairing token that this
/// host gave the LEVI DOCK app over ADB; anything else is ignored.
fn handle_command(v: &Value, token: &str, writer: &Writer, dev_latest: &Arc<Mutex<(u64, Value)>>) {
    if v["token"].as_str() != Some(token) {
        log("ignored a dock command without a valid token");
        return;
    }
    let eng = input::engine();
    match v["action"].as_str().unwrap_or("") {
        "ai-ask" => {
            let id = v["id"].as_i64().unwrap_or(0);
            let question = v["text"].as_str().unwrap_or("").to_string();
            if question.trim().is_empty() {
                return;
            }
            let context = dev_latest.lock().map(|s| s.1.clone()).unwrap_or(Value::Null);
            let writer = writer.clone();
            thread::spawn(move || {
                ai::ask(id, &question, &context, &mut |msg| {
                    let _ = send(&writer, &msg);
                });
            });
        }
        "ai-clear" => ai::clear(),
        "quick" => {
            let id = v["id"].as_str().unwrap_or("");
            let result = quick::run(id);
            match &result {
                Ok(msg) => log(&format!("quick: {msg}")),
                Err(e) => log(&format!("quick: {e}")),
            }
            let text = match &result {
                Ok(m) => m.to_string(),
                Err(e) => e.clone(),
            };
            let _ = send(writer, &json!({"type": "quick-result", "id": id, "ok": result.is_ok(), "text": text}));
        }
        "ai-status" => {
            let _ = send(writer, &ai::status());
        }
        "open-app" => match dev::launch_app(v["app"].as_str().unwrap_or("")) {
            Ok(msg) => log(&msg),
            Err(e) => log(&format!("open-app: {e}")),
        },
        "input-mode" => match v["mode"].as_str() {
            Some("dock") => eng.enter(),
            Some("laptop") => eng.exit(),
            _ => {}
        },
        "clipboard-share" => {
            eng.clipboard_share.store(v["on"].as_bool().unwrap_or(true), Ordering::Relaxed);
            eng.version.fetch_add(1, Ordering::Relaxed);
        }
        "clipboard" => {
            if eng.clipboard_share.load(Ordering::Relaxed) {
                if let Some(text) = v["text"].as_str().filter(|t| !t.is_empty() && t.len() <= 65_536) {
                    input::clipboard_set(text);
                }
            }
        }
        other => log(&format!("unknown dock command: {other}")),
    }
}

/// Carries out a "Hey LEVI" action. Dock-side actions become messages to the app.
fn handle_voice(action: voice::Action, writer: &Writer, dev_latest: &Arc<Mutex<(u64, Value)>>) {
    use voice::Action;
    match action {
        Action::Listening | Action::NotUnderstood => {}
        Action::Screen(n) => { let _ = send(writer, &json!({"type": "ui", "screen": n})); }
        Action::Media(a) => { let _ = send(writer, &json!({"type": "media", "action": a})); }
        Action::DockVolume(d) => { let _ = send(writer, &json!({"type": "dock-volume", "dir": d})); }
        Action::Input(dock) => if dock { input::engine().enter() } else { input::engine().exit() },
        Action::Quick(id) => {
            let r = quick::run(id);
            let text = match &r { Ok(m) => m.to_string(), Err(e) => e.clone() };
            log(&format!("voice quick: {text}"));
            let _ = send(writer, &json!({"type": "quick-result", "id": id, "ok": r.is_ok(), "text": text}));
        }
        Action::Ask(question) => {
            let id = now_ms();
            let _ = send(writer, &json!({"type": "ui", "screen": 3}));
            let _ = send(writer, &json!({"type": "ai-user", "id": id, "text": question}));
            let context = dev_latest.lock().map(|s| s.1.clone()).unwrap_or(Value::Null);
            let writer = writer.clone();
            thread::spawn(move || {
                let mut answer = String::new();
                let mut error = None;
                ai::ask(id, &question, &context, &mut |msg| {
                    if msg["type"] == "ai-delta" {
                        answer.push_str(msg["text"].as_str().unwrap_or(""));
                    } else if let Some(e) = msg["error"].as_str() {
                        error = Some(e.to_string());
                    }
                    let _ = send(&writer, &msg);
                });
                match error {
                    Some(_) => voice::speak("Sorry, I couldn't get an answer.".into()),
                    None => voice::speak(voice::spoken_summary(&answer)),
                }
            });
        }
    }
}

/// Random pairing token, created once and kept in %LOCALAPPDATA%\LEVI\dock\token.
fn load_token() -> String {
    let dir = std::path::PathBuf::from(std::env::var("LOCALAPPDATA").unwrap_or_else(|_| ".".into()))
        .join("LEVI")
        .join("dock");
    let path = dir.join("token");
    if let Ok(t) = std::fs::read_to_string(&path) {
        let t = t.trim().to_string();
        if t.len() == 64 && t.chars().all(|c| c.is_ascii_hexdigit()) {
            return t;
        }
    }
    let mut bytes = [0u8; 32];
    unsafe {
        use windows_sys::Win32::Security::Cryptography::{BCRYPT_USE_SYSTEM_PREFERRED_RNG, BCryptGenRandom};
        BCryptGenRandom(std::ptr::null_mut(), bytes.as_mut_ptr(), 32, BCRYPT_USE_SYSTEM_PREFERRED_RNG);
    }
    let t: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    let _ = std::fs::create_dir_all(&dir);
    let _ = std::fs::write(&path, &t);
    t
}

/// Hands the token to the app through an explicit broadcast. The receiver
/// requires android.permission.DUMP, which only the ADB shell / system hold.
fn provision_token(serial: &str, token: &str) {
    let _ = adb(&["-s", serial, "shell", "am", "broadcast", "-n", TOKEN_RECEIVER, "--es", "token", token]);
    // The owner asked for "Hey LEVI" on the dock mic: grant it (touch is broken, no dialog possible).
    let _ = adb(&["-s", serial, "shell", "pm", "grant", "com.levi.dock", "android.permission.RECORD_AUDIO"]);
}

fn send(stream: &Writer, v: &Value) -> std::io::Result<()> {
    let mut line = v.to_string();
    line.push('\n');
    let mut s = stream.lock().map_err(|_| std::io::Error::other("link writer poisoned"))?;
    s.write_all(line.as_bytes())
}

struct Sampler {
    sys: System,
    disks: Disks,
    nets: Networks,
    comps: Option<Components>,
    last_net: Instant,
    host: String,
}

impl Sampler {
    fn new() -> Self {
        let mut sys = System::new().expect("system info unavailable");
        sys.refresh_cpu_all();
        Sampler {
            sys,
            disks: Disks::new_with_refreshed_list().expect("disk info unavailable"),
            nets: Networks::new_with_refreshed_list().expect("network info unavailable"),
            // Temperature sensors are often unavailable on Windows; that's fine.
            comps: Components::new_with_refreshed_list().ok(),
            last_net: Instant::now(),
            host: System::host_name().unwrap_or_else(|_| "Laptop".into()),
        }
    }

    fn sample(&mut self) -> Value {
        self.sys.refresh_cpu_all();
        self.sys.refresh_memory();
        self.disks.refresh(true);
        self.nets.refresh(true);
        if let Some(c) = self.comps.as_mut() {
            c.refresh(true);
        }

        let secs = self.last_net.elapsed().as_secs_f64().max(0.001);
        self.last_net = Instant::now();
        let (mut rx, mut tx) = (0u64, 0u64);
        for (_, data) in self.nets.iter() {
            rx += data.received();
            tx += data.transmitted();
        }

        let (free, total) = self
            .disks
            .iter()
            .find(|d| d.mount_point().to_string_lossy().eq_ignore_ascii_case("C:\\"))
            .map(|d| (d.available_space(), d.total_space()))
            .unwrap_or((0, 0));

        let cpu_temp = self
            .comps
            .iter()
            .flat_map(|cs| cs.iter())
            .filter(|c| {
                let l = c.label().to_ascii_lowercase();
                l.contains("cpu") || l.contains("package") || l.contains("core")
            })
            .filter_map(|c| c.temperature())
            .filter(|t| t.is_finite() && *t > 0.0)
            .fold(None, |acc: Option<f32>, t| Some(acc.map_or(t, |a| a.max(t))));

        let mhz = self.sys.cpus().first().map(|c| c.frequency()).unwrap_or(0);
        let (pct, charging, ac) = battery();

        json!({
            "type": "metrics",
            "ts": now_ms(),
            "host": self.host,
            "cpu": (self.sys.global_cpu_usage() * 10.0).round() / 10.0,
            "cpuMhz": mhz,
            "mem": {"used": self.sys.used_memory(), "total": self.sys.total_memory()},
            "disk": {"free": free, "total": total},
            "net": {"rx": rx as f64 / secs, "tx": tx as f64 / secs},
            "battery": {"pct": pct, "charging": charging, "ac": ac},
            "cpuTemp": cpu_temp,
        })
    }
}

/// (percent or -1, charging, on AC power)
fn battery() -> (i64, bool, bool) {
    use windows_sys::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};
    let mut s: SYSTEM_POWER_STATUS = unsafe { std::mem::zeroed() };
    if unsafe { GetSystemPowerStatus(&mut s) } == 0 {
        return (-1, false, false);
    }
    let no_battery = s.BatteryFlag == 128 || s.BatteryLifePercent == 255;
    let pct = if no_battery { -1 } else { s.BatteryLifePercent as i64 };
    (pct, s.BatteryFlag & 8 != 0, s.ACLineStatus == 1)
}

fn adb_path() -> String {
    std::env::var("LEVI_ADB").unwrap_or_else(|_| r"C:\platform-tools\adb.exe".into())
}

fn adb(args: &[&str]) -> Option<String> {
    let out = Command::new(adb_path())
        .args(args)
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .ok()?;
    out.status
        .success()
        .then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

/// LEVI_SERIAL if set, else the first authorized device, preferring USB over Wi-Fi.
fn pick_serial() -> Option<String> {
    if let Ok(s) = std::env::var("LEVI_SERIAL") {
        return Some(s);
    }
    let list = adb(&["devices"])?;
    let mut devices: Vec<String> = list
        .lines()
        .skip(1)
        .filter_map(|l| {
            let mut it = l.split_whitespace();
            match (it.next(), it.next()) {
                (Some(serial), Some("device")) => Some(serial.to_string()),
                _ => None,
            }
        })
        .collect();
    devices.sort_by_key(|s| s.contains(':'));
    devices.into_iter().next()
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub(crate) fn log(msg: &str) {
    let secs = now_ms() / 1000;
    println!("[{:02}:{:02}:{:02} UTC] {msg}", (secs / 3600) % 24, (secs / 60) % 60, secs % 60);
}
