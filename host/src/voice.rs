//! "Hey LEVI" hands-free control.
//!
//! Audio: the LEVI DOCK app streams the dock microphone (16 kHz mono PCM16,
//! token-protected, through `adb forward`). levi-host keeps the last 20 s in a
//! ring buffer and relays the stream to `assets/levi-voice.ps1`, which uses
//! Windows' offline recogniser only to spot "hey levi". After the wake word,
//! levi-host captures what follows until the speaker stops (max 8 s) and has
//! it transcribed by a speech-capable model through the owner's OpenRouter key
//! (`ai::transcribe`). Nothing leaves the laptop before "hey levi".
//! Replies are spoken on the dock speaker. Disable with LEVI_VOICE=0.

use serde_json::{Value, json};
use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::os::windows::process::CommandExt;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::mpsc::{Sender, channel};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const SCRIPT: &str = include_str!("../assets/levi-voice.ps1");
pub const MIC_PORT: u16 = 47801;
pub const SPEAK_PORT: u16 = 47802;
const RATE: u64 = 16_000;
const RING_SECONDS: usize = 20;

/// What the session loop should do with what was heard.
#[derive(Debug, Clone)]
pub enum Action {
    Screen(u8),
    Media(&'static str),
    DockVolume(&'static str),
    Quick(&'static str),
    Input(bool),
    Ask(String),
    Listening,
    NotUnderstood,
}

pub struct Heard {
    pub text: String,
    pub action: Action,
}

struct Ring {
    buf: VecDeque<i16>,
    /// Samples received since the dock audio connected (index of buf.back() + 1).
    total: u64,
}

static QUEUE: Mutex<VecDeque<Heard>> = Mutex::new(VecDeque::new());
static RING: Mutex<Ring> = Mutex::new(Ring { buf: VecDeque::new(), total: 0 });
static RELAY_TX: Mutex<Option<Sender<Vec<u8>>>> = Mutex::new(None);
static RELAY_PORT: AtomicU16 = AtomicU16::new(0);
static CAPTURING: AtomicBool = AtomicBool::new(false);
static SPEAKING: AtomicBool = AtomicBool::new(false);
static QUIET_UNTIL: Mutex<Option<Instant>> = Mutex::new(None);
static TOKEN: OnceLock<String> = OnceLock::new();
pub static READY: AtomicBool = AtomicBool::new(false);

pub fn start(token: &str) {
    if std::env::var("LEVI_VOICE").as_deref() == Ok("0") {
        crate::log("voice: disabled (LEVI_VOICE=0)");
        return;
    }
    let _ = TOKEN.set(token.to_string());
    thread::spawn(dock_audio);
    thread::spawn(relay_server);
    thread::spawn(|| loop {
        if RELAY_PORT.load(Ordering::Relaxed) != 0 {
            run_listener();
            READY.store(false, Ordering::Relaxed);
        }
        thread::sleep(Duration::from_secs(5));
    });
}

pub fn drain() -> Vec<Heard> {
    QUEUE.lock().map(|mut q| q.drain(..).collect()).unwrap_or_default()
}

fn push(text: String, action: Action) {
    if let Ok(mut q) = QUEUE.lock() {
        q.push_back(Heard { text, action });
    }
}

fn token() -> String {
    TOKEN.get().cloned().unwrap_or_default()
}

/// Reads the dock microphone into the ring buffer and relays it to the recogniser.
fn dock_audio() {
    loop {
        if let Ok(mut s) = TcpStream::connect(("127.0.0.1", MIC_PORT)) {
            let _ = s.write_all(format!("{}\n", token()).as_bytes());
            let mut buf = [0u8; 3200];
            let mut carry: Option<u8> = None;
            loop {
                let Ok(n) = s.read(&mut buf) else { break };
                if n == 0 {
                    break;
                }
                let mut bytes = Vec::with_capacity(n + 1);
                if let Some(c) = carry.take() {
                    bytes.push(c);
                }
                bytes.extend_from_slice(&buf[..n]);
                if bytes.len() % 2 == 1 {
                    carry = bytes.pop();
                }
                if let Ok(mut r) = RING.lock() {
                    for p in bytes.chunks_exact(2) {
                        r.buf.push_back(i16::from_le_bytes([p[0], p[1]]));
                    }
                    r.total += (bytes.len() / 2) as u64;
                    let cap = RATE as usize * RING_SECONDS;
                    while r.buf.len() > cap {
                        r.buf.pop_front();
                    }
                }
                if let Ok(tx) = RELAY_TX.lock() {
                    if let Some(tx) = tx.as_ref() {
                        let _ = tx.send(bytes);
                    }
                }
            }
        }
        thread::sleep(Duration::from_secs(3));
    }
}

/// Loopback relay for the wake-word script (token-protected).
fn relay_server() {
    let Ok(listener) = TcpListener::bind(("127.0.0.1", 0)) else { return };
    if let Ok(addr) = listener.local_addr() {
        RELAY_PORT.store(addr.port(), Ordering::Relaxed);
    }
    for conn in listener.incoming() {
        let Ok(mut s) = conn else { continue };
        let mut line = String::new();
        let _ = s.set_read_timeout(Some(Duration::from_secs(4)));
        if BufReader::new(s.try_clone().unwrap()).read_line(&mut line).is_err() || line.trim() != token() {
            continue;
        }
        let (tx, rx) = channel::<Vec<u8>>();
        *RELAY_TX.lock().unwrap() = Some(tx);
        thread::spawn(move || {
            for chunk in rx {
                if s.write_all(&chunk).is_err() {
                    break;
                }
            }
            *RELAY_TX.lock().unwrap() = None;
        });
    }
}

fn run_listener() {
    let dir = std::path::PathBuf::from(std::env::var("LOCALAPPDATA").unwrap_or_else(|_| ".".into())).join("LEVI").join("dock");
    let _ = std::fs::create_dir_all(&dir);
    let path = dir.join("levi-voice.ps1");
    if std::fs::write(&path, SCRIPT).is_err() {
        return;
    }
    let child = Command::new("powershell.exe")
        .args(["-NoProfile", "-ExecutionPolicy", "Bypass", "-File"])
        .arg(&path)
        .env("LEVI_VOICE_PORT", RELAY_PORT.load(Ordering::Relaxed).to_string())
        .env("LEVI_TOKEN", token())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .stdin(Stdio::null())
        .creation_flags(CREATE_NO_WINDOW)
        .spawn();
    let Ok(mut child) = child else {
        crate::log("voice: could not start the Windows speech recogniser");
        return;
    };
    let out = BufReader::new(child.stdout.take().unwrap());
    for line in out.lines() {
        let Ok(line) = line else { break };
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        match v["kind"].as_str() {
            Some("ready") => {
                READY.store(true, Ordering::Relaxed);
                crate::log("voice: listening for \"Hey LEVI\" on the dock microphone");
            }
            Some("wake") => {
                if SPEAKING.load(Ordering::Relaxed)
                    || QUIET_UNTIL.lock().ok().and_then(|q| *q).is_some_and(|t| Instant::now() < t)
                    || CAPTURING.swap(true, Ordering::Relaxed)
                {
                    continue;
                }
                // Count back from the newest audio: what followed "levi" in the phrase, plus
                // ~1.2 s for the recogniser's end-of-speech delay. Starting a little early is
                // safe: a leftover "levi" is trimmed from the transcript.
                let after_ms = v["after"].as_u64().unwrap_or(0).min(8000);
                let total = RING.lock().map(|r| r.total).unwrap_or(0);
                let abs = total.saturating_sub((after_ms + 1200) * RATE / 1000);
                crate::log("voice: \"hey levi\" — listening for the command");
                push(String::new(), Action::Listening);
                thread::spawn(move || {
                    capture_and_understand(abs);
                    CAPTURING.store(false, Ordering::Relaxed);
                });
            }
            _ => {}
        }
    }
    let _ = child.kill();
    crate::log("voice: recogniser stopped; retrying");
}

/// Collects audio from `abs` until ~0.9 s of silence after speech (max 8 s), then transcribes it.
fn capture_and_understand(abs: u64) {
    const FRAME: usize = 1600; // 100 ms
    let t0 = Instant::now();
    // Noise floor: quietest 100 ms in the 2 s before the wake phrase ended.
    let noise = {
        let r = RING.lock().unwrap();
        let first = r.total - r.buf.len() as u64;
        let from = abs.saturating_sub(RATE * 2).max(first);
        let to = abs.min(r.total);
        let slice: Vec<i16> = r.buf.iter().skip((from - first) as usize).take((to.saturating_sub(from)) as usize).copied().collect();
        let quietest = slice.chunks(FRAME).map(rms).fold(f64::MAX, f64::min);
        if quietest == f64::MAX { 200.0 } else { quietest.min(1200.0) }
    };
    let threshold = (noise * 2.5).clamp(350.0, 3000.0);

    // Audio from `abs` may already hold a one-breath command; also wait for speech that
    // starts after the wake word (two-step use) and ends with ~0.9 s of silence.
    let wake_at = RING.lock().map(|r| r.total).unwrap_or(abs);
    let after_frame = ((wake_at.saturating_sub(abs)) as usize) / FRAME;
    let mut speech;
    let mut end;
    loop {
        thread::sleep(Duration::from_millis(100));
        let (pcm, _) = slice_from(abs);
        end = pcm.len().min(RATE as usize * 14);
        let frames: Vec<f64> = pcm.chunks(FRAME).map(rms).collect();
        speech = frames.iter().any(|f| *f > threshold);
        let speech_after = frames.iter().skip(after_frame).any(|f| *f > threshold);
        let silent_frames = frames.iter().rev().take_while(|f| **f <= threshold).count();
        let waited = t0.elapsed();
        if speech_after && silent_frames >= 9 {
            break;
        }
        if (!speech_after && waited > Duration::from_secs(4)) || waited > Duration::from_secs(8) {
            break;
        }
    }
    {
        let (pcm, total) = slice_from(abs);
        let loudest = pcm.chunks(1600).map(rms).fold(0.0, f64::max);
        crate::log(&format!(
            "voice: capture abs={abs} total={total} len={:.1}s noise={noise:.0} threshold={threshold:.0} loudest={loudest:.0}",
            pcm.len() as f64 / RATE as f64
        ));
    }
    if !speech {
        crate::log("voice: no command after the wake word");
        push("Didn't catch that".into(), Action::NotUnderstood);
        return;
    }
    let (pcm, _) = slice_from(abs);
    let pcm = &pcm[..end.min(pcm.len())];
    match crate::ai::transcribe(pcm, RATE as u32) {
        Ok(text) => {
            let command = strip_wake_words(&text);
            if normalize(&command).is_empty() {
                crate::log(&format!("voice: heard \"{}\" but no command", text.trim()));
                push("Didn't catch the command".into(), Action::NotUnderstood);
                return;
            }
            let action = map_command(&command).unwrap_or_else(|| {
                if normalize(&command).split_whitespace().count() >= 2 { Action::Ask(command.clone()) } else { Action::NotUnderstood }
            });
            crate::log(&format!("voice: heard \"{}\" → {action:?}", text.trim()));
            push(command, action);
        }
        Err(e) => {
            crate::log(&format!("voice: transcription failed: {e}"));
            push("Couldn't understand (speech service)".into(), Action::NotUnderstood);
        }
    }
}

fn slice_from(abs: u64) -> (Vec<i16>, u64) {
    let r = RING.lock().unwrap();
    let first = r.total - r.buf.len() as u64;
    let from = abs.max(first);
    let v: Vec<i16> = r.buf.iter().skip((from - first) as usize).copied().collect();
    (v, r.total)
}

fn rms(f: &[i16]) -> f64 {
    if f.is_empty() {
        return 0.0;
    }
    (f.iter().map(|s| (*s as f64) * (*s as f64)).sum::<f64>() / f.len() as f64).sqrt()
}

/// Removes a leading "hey levi" / "hi levi" / "levi" (with punctuation) from a transcript.
fn strip_wake_words(s: &str) -> String {
    let words: Vec<&str> = s.split_whitespace().collect();
    let key = |w: &str| w.trim_matches(|c: char| !c.is_alphanumeric()).to_ascii_lowercase();
    let mut i = 0;
    while i < words.len() && i < 3 && matches!(key(words[i]).as_str(), "hey" | "hi" | "hello" | "ok" | "okay" | "levi" | "levy" | "lev") {
        i += 1;
    }
    words[i..].join(" ").trim_start_matches([',', '.', ' ']).to_string()
}

fn normalize(s: &str) -> String {
    s.to_ascii_lowercase()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == ' ' { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

/// Maps a spoken command (lower-case, no "hey levi") to an action.
pub fn map_command(t: &str) -> Option<Action> {
    let t = normalize(t);
    let t = t.trim_start_matches("hey levi").trim_start_matches("levi").trim();
    let has = |w: &str| t.contains(w);
    // Laptop actions first ("open terminal" is not a screen).
    if has("lock") { return Some(Action::Quick("lock")) }
    if has("screenshot") || has("screen shot") { return Some(Action::Quick("screenshot")) }
    if has("terminal") { return Some(Action::Quick("terminal")) }
    if has("vs code") || has("vscode") || has("visual studio") { return Some(Action::Quick("vscode")) }
    if has("explorer") { return Some(Action::Quick("explorer")) }
    if has("task manager") { return Some(Action::Quick("taskmgr")) }
    if has("switch to") { return Some(Action::Input(has("dock"))) }
    let nav = t.starts_with("open") || t.starts_with("show") || t.starts_with("go to") || t.starts_with("switch");
    if nav {
        let screen = if has("dashboard") || has("home") { 0 } else if has("dev") { 1 } else if has("media") || has("music") && !has("play") { 2 }
            else if has("assistant") || has("chat") { 3 } else if has("control") { 4 } else if has("ecosystem") || has("apps") { 5 } else { return None };
        return Some(Action::Screen(screen));
    }
    if t.split_whitespace().count() <= 4 {
        if has("unmute") || has("mute") { return Some(Action::DockVolume("mute")) }
        if has("volume up") || has("louder") || has("turn it up") { return Some(Action::DockVolume("up")) }
        if has("volume down") || has("quieter") || has("turn it down") { return Some(Action::DockVolume("down")) }
        if has("next") || has("skip") { return Some(Action::Media("next")) }
        if has("previous") || has("go back") || has("last song") { return Some(Action::Media("previous")) }
        if has("pause") || has("stop") { return Some(Action::Media("pause")) }
        if has("play") || has("resume") { return Some(Action::Media("play")) }
    }
    None
}

/// Message for the dock describing what was heard (shown as a banner).
pub fn banner(h: &Heard) -> Value {
    let text = match &h.action {
        Action::Listening => "Listening…".to_string(),
        _ => h.text.clone(),
    };
    json!({"type": "voice", "text": text})
}

/// Speaks `text` on the dock speaker (16 kHz PCM synthesised on the laptop).
/// Text goes in on stdin, never on the command line.
pub fn speak(text: String) {
    let text: String = text.chars().take(600).collect();
    if text.trim().is_empty() {
        return;
    }
    thread::spawn(move || {
        SPEAKING.store(true, Ordering::Relaxed);
        const DOCK: &str = "Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; \
            $ms = New-Object System.IO.MemoryStream; \
            $fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono); \
            $s.SetOutputToAudioStream($ms, $fmt); $s.Speak([Console]::In.ReadToEnd()); \
            $c = New-Object System.Net.Sockets.TcpClient('127.0.0.1', [int]$env:LEVI_SPEAK_PORT); $n = $c.GetStream(); \
            $h = [Text.Encoding]::ASCII.GetBytes($env:LEVI_TOKEN + [char]10); $n.Write($h, 0, $h.Length); \
            $b = $ms.ToArray(); $n.Write($b, 0, $b.Length); $n.Flush(); Start-Sleep -Milliseconds 200; $c.Close()";
        let child = Command::new("powershell.exe")
            .args(["-NoProfile", "-Command", DOCK])
            .env("LEVI_SPEAK_PORT", SPEAK_PORT.to_string())
            .env("LEVI_TOKEN", token())
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(CREATE_NO_WINDOW)
            .spawn();
        if let Ok(mut c) = child {
            if let Some(mut stdin) = c.stdin.take() {
                let _ = stdin.write_all(text.as_bytes());
            }
            let _ = c.wait();
        }
        // Rough playback time on the dock (~14 characters per second) before listening again.
        let secs = (text.len() as f64 / 14.0).min(40.0);
        thread::sleep(Duration::from_secs_f64(secs));
        SPEAKING.store(false, Ordering::Relaxed);
        if let Ok(mut q) = QUIET_UNTIL.lock() {
            *q = Some(Instant::now() + Duration::from_millis(800));
        }
    });
}

/// First two sentences of an answer, without markdown, for speaking aloud.
pub fn spoken_summary(answer: &str) -> String {
    let clean: String = answer.replace(['*', '#', '`'], "");
    let mut out = String::new();
    let mut sentences = 0;
    for ch in clean.chars() {
        out.push(ch);
        if matches!(ch, '.' | '!' | '?') {
            sentences += 1;
            if sentences >= 2 {
                break;
            }
        }
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(t: &str) -> String {
        format!("{:?}", map_command(t))
    }

    #[test]
    fn screens() {
        assert_eq!(m("Open media."), "Some(Screen(2))");
        assert_eq!(m("show the dashboard"), "Some(Screen(0))");
        assert_eq!(m("go to dev center"), "Some(Screen(1))");
        assert_eq!(m("open quick controls"), "Some(Screen(4))");
        assert_eq!(m("open ecosystem"), "Some(Screen(5))");
        assert_eq!(m("open the assistant"), "Some(Screen(3))");
    }

    #[test]
    fn laptop_actions_win_over_screens() {
        assert_eq!(m("open terminal"), "Some(Quick(\"terminal\"))");
        assert_eq!(m("Open VS Code"), "Some(Quick(\"vscode\"))");
        assert_eq!(m("lock the laptop"), "Some(Quick(\"lock\"))");
        assert_eq!(m("take a screenshot"), "Some(Quick(\"screenshot\"))");
    }

    #[test]
    fn music_and_volume() {
        assert_eq!(m("Play some music"), "Some(Media(\"play\"))");
        assert_eq!(m("pause music"), "Some(Media(\"pause\"))");
        assert_eq!(m("next song"), "Some(Media(\"next\"))");
        assert_eq!(m("previous song"), "Some(Media(\"previous\"))");
        assert_eq!(m("volume up"), "Some(DockVolume(\"up\"))");
        assert_eq!(m("switch to dock"), "Some(Input(true))");
        assert_eq!(m("switch to the laptop"), "Some(Input(false))");
    }

    #[test]
    fn questions_are_not_commands() {
        assert_eq!(m("what should I work on next in LEVI Browser"), "None");
        assert_eq!(m("how do I stop a docker container"), "None");
    }

    #[test]
    fn wake_words_are_stripped() {
        assert_eq!(strip_wake_words("Hey Levi, open media."), "open media.");
        assert_eq!(strip_wake_words("Hey Levi."), "");
        assert_eq!(strip_wake_words("Levi what is my git status"), "what is my git status");
        assert_eq!(strip_wake_words("Play some music"), "Play some music");
    }

    #[test]
    fn summary() {
        assert_eq!(spoken_summary("**Two** projects. One is clean. Third sentence."), "Two projects. One is clean.");
    }
}
