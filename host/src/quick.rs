//! Quick Controls: a fixed allow-list of laptop actions the dock may trigger.
//! Nothing here takes free-form input; every action is a known id.

use std::path::PathBuf;
use std::process::Command;
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{KEYEVENTF_KEYUP, keybd_event};

pub fn run(id: &str) -> Result<&'static str, String> {
    match id {
        "lock" => {
            let ok = unsafe { windows_sys::Win32::System::Shutdown::LockWorkStation() } != 0;
            if ok { Ok("locked Windows") } else { Err("lock failed".into()) }
        }
        "screenshot" => {
            // Win + Print Screen: Windows saves to Pictures\Screenshots.
            tap(&[0x5B, 0x2C]);
            Ok("screenshot saved to Pictures\\Screenshots")
        }
        "terminal" => {
            let wt = local_app("Microsoft\\WindowsApps\\wt.exe");
            let exe = if wt.is_file() { wt } else { PathBuf::from("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe") };
            spawn(&exe).map(|_| "opened a terminal")
        }
        "vscode" => {
            let candidates = [
                local_app("Programs\\Microsoft VS Code\\Code.exe"),
                PathBuf::from("C:\\Program Files\\Microsoft VS Code\\Code.exe"),
            ];
            let exe = candidates.into_iter().find(|p| p.is_file()).ok_or("VS Code not found")?;
            spawn(&exe).map(|_| "opened VS Code")
        }
        "explorer" => spawn(&PathBuf::from("C:\\Windows\\explorer.exe")).map(|_| "opened File Explorer"),
        "taskmgr" => spawn(&PathBuf::from("C:\\Windows\\System32\\Taskmgr.exe")).map(|_| "opened Task Manager"),
        "mute" => { tap(&[0xAD]); Ok("toggled mute") }
        "vol-up" => { tap(&[0xAF]); tap(&[0xAF]); Ok("volume up") }
        "vol-down" => { tap(&[0xAE]); tap(&[0xAE]); Ok("volume down") }
        "media-play" => { tap(&[0xB3]); Ok("play/pause on the laptop") }
        "media-next" => { tap(&[0xB0]); Ok("next track on the laptop") }
        "media-prev" => { tap(&[0xB1]); Ok("previous track on the laptop") }
        // Confirmed on the dock before it is sent.
        "sleep" => {
            let ok = unsafe { windows_sys::Win32::System::Power::SetSuspendState(false, false, false) };
            if ok { Ok("laptop going to sleep") } else { Err("sleep failed".into()) }
        }
        other => Err(format!("unknown quick action: {other}")),
    }
}

fn local_app(rel: &str) -> PathBuf {
    PathBuf::from(std::env::var("LOCALAPPDATA").unwrap_or_default()).join(rel)
}

fn spawn(exe: &PathBuf) -> Result<(), String> {
    Command::new(exe)
        .current_dir(std::env::var("USERPROFILE").unwrap_or_else(|_| "C:\\".into()))
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// Presses the keys in order, then releases them in reverse (synthetic, so the
/// shared-input hook ignores them).
fn tap(vks: &[u8]) {
    unsafe {
        for vk in vks {
            keybd_event(*vk, 0, 0, 0);
        }
        for vk in vks.iter().rev() {
            keybd_event(*vk, 0, KEYEVENTF_KEYUP, 0);
        }
    }
}
