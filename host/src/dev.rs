//! Dev Command Center data: LEVI projects + git, coding agents, running dev
//! tools and local servers. Sent as a `dev` message every few seconds.

use serde_json::{Value, json};
use std::collections::{BTreeMap, HashMap};
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::SystemTime;
use sysinfo::{ProcessRefreshKind, ProcessesToUpdate, System, UpdateKind};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub struct DevSampler {
    sys: System,
    root: PathBuf,
}

impl DevSampler {
    pub fn new() -> Self {
        let root = std::env::var("LEVI_PROJECTS").unwrap_or_else(|_| r"C:\projects\LEVI".into());
        DevSampler { sys: System::new().expect("system info unavailable"), root: PathBuf::from(root) }
    }

    pub fn sample(&mut self) -> Value {
        self.sys.refresh_processes_specifics(
            ProcessesToUpdate::All,
            true,
            ProcessRefreshKind::nothing()
                .with_cmd(UpdateKind::OnlyIfNotSet)
                .with_cwd(UpdateKind::OnlyIfNotSet)
                .with_exe(UpdateKind::OnlyIfNotSet),
        );
        let names: HashMap<u32, String> = self
            .sys
            .processes()
            .iter()
            .map(|(pid, p)| (pid.as_u32(), p.name().to_string_lossy().to_string()))
            .collect();
        json!({
            "type": "dev",
            "projects": self.projects(),
            "agents": self.agents(),
            "tools": self.tools(),
            "servers": servers(&names),
            "apps": self.apps(),
        })
    }

    /// LEVI ecosystem apps on the laptop (manifests in %LOCALAPPDATA%\LEVI\ecosystem).
    fn apps(&self) -> Vec<Value> {
        let running = |needle: &str| {
            self.sys.processes().values().any(|p| p.name().to_string_lossy().to_ascii_lowercase().starts_with(needle))
        };
        let cc = running("levi-command-center");
        let browser = running("levi-browser");
        let growth_root = self.root.join("LEVI Personal Growth").to_string_lossy().to_ascii_lowercase();
        let growth = self.sys.processes().values().any(|p| {
            let n = p.name().to_string_lossy().to_ascii_lowercase();
            n.starts_with("node")
                && (p.cwd().map(|c| c.to_string_lossy().to_ascii_lowercase().starts_with(&growth_root)).unwrap_or(false)
                    || cmdline(p.cmd()).contains("personal growth"))
        });
        let spaces = manifest("browser")
            .and_then(|m| m["spaces"].as_array().map(|a| a.len()))
            .unwrap_or(0);
        vec![
            json!({"id": "command-center", "name": "LEVI Command Center", "running": cc,
                   "detail": if cc { "Running" } else { "Not running" }, "launchable": launch_path("command-center").is_some()}),
            json!({"id": "browser", "name": "LEVI Browser", "running": browser,
                   "detail": if browser { format!("Running · {spaces} Spaces") } else { format!("Not running · {spaces} Spaces") },
                   "launchable": launch_path("browser").is_some()}),
            json!({"id": "jarvis", "name": "JARVIS", "running": cc,
                   "detail": if cc { "Ready in Command Center" } else { "Opens with Command Center" }, "launchable": launch_path("command-center").is_some()}),
            json!({"id": "growth", "name": "LEVI Personal Growth", "running": growth,
                   "detail": if growth { "LEVI OS dev server running" } else { "LEVI OS · start with npm run dev" }, "launchable": false}),
        ]
    }

    fn projects(&self) -> Vec<Value> {
        let Ok(dir) = std::fs::read_dir(&self.root) else { return vec![] };
        let mut list: Vec<(SystemTime, Value)> = dir
            .flatten()
            .filter(|e| e.path().is_dir())
            .filter(|e| !e.file_name().to_string_lossy().starts_with('.'))
            .map(|e| {
                let path = e.path();
                let name = e.file_name().to_string_lossy().to_string();
                let modified = last_activity(&path);
                let mut v = json!({ "name": name, "git": false, "modifiedAgo": ago(modified) });
                if path.join(".git").exists() {
                    git_info(&path, &mut v);
                }
                (modified, v)
            })
            .collect();
        list.sort_by(|a, b| b.0.cmp(&a.0));
        list.into_iter()
            .take(6)
            .enumerate()
            .map(|(i, (_, mut v))| {
                v["active"] = json!(i == 0);
                v
            })
            .collect()
    }

    fn agents(&self) -> Vec<Value> {
        let mut found: BTreeMap<&'static str, usize> = BTreeMap::new();
        for p in self.sys.processes().values() {
            let n = p.name().to_string_lossy().to_ascii_lowercase();
            let cmd = cmdline(p.cmd());
            let agent = if n.starts_with("claude") || cmd.contains("claude-code") || cmd.contains("@anthropic-ai/claude") {
                Some("Claude")
            } else if n.starts_with("codex") && !n.contains("sandbox") {
                Some("Codex")
            } else if n.starts_with("cursor") {
                Some("Cursor")
            } else if n.starts_with("windsurf") {
                Some("Windsurf")
            } else {
                None
            };
            if let Some(a) = agent {
                *found.entry(a).or_default() += 1;
            }
        }
        found.into_iter().map(|(name, n)| json!({ "name": name, "processes": n })).collect()
    }

    fn tools(&self) -> Vec<Value> {
        let mut found: BTreeMap<String, usize> = BTreeMap::new();
        for p in self.sys.processes().values() {
            let n = p.name().to_string_lossy().to_ascii_lowercase();
            let cmd = cmdline(p.cmd());
            let tool = match n.trim_end_matches(".exe") {
                "cargo" => Some("Cargo"),
                "rustc" => Some("rustc"),
                "java" | "javaw" if cmd.contains("gradledaemon") || cmd.contains("gradle-launcher") => Some("Gradle"),
                "java" | "javaw" if cmd.contains("kotlin") => Some("Kotlin daemon"),
                "node" if cmd.contains("vite") => Some("Vite"),
                "node" if cmd.contains("next") => Some("Next.js"),
                "node" if cmd.contains("tauri") => Some("Tauri"),
                "node" if cmd.contains("claude") || cmd.contains("codex") => None,
                "node" => Some("Node"),
                "python" | "python3" | "py" if cmd.contains("pytest") => Some("pytest"),
                "python" | "python3" | "py" => Some("Python"),
                "dotnet" => Some(".NET"),
                "go" => Some("Go"),
                "docker" | "com.docker.backend" => Some("Docker"),
                _ => None,
            };
            if let Some(t) = tool {
                *found.entry(t.to_string()).or_default() += 1;
            }
        }
        found.into_iter().map(|(name, n)| json!({ "name": name, "processes": n })).collect()
    }
}

fn manifest(app: &str) -> Option<Value> {
    let base = std::env::var("LOCALAPPDATA").ok()?;
    let file = match app {
        "browser" => "browser.json",
        "command-center" => "command-center.json",
        _ => return None,
    };
    let text = std::fs::read_to_string(PathBuf::from(base).join("LEVI").join("ecosystem").join(file)).ok()?;
    serde_json::from_str(&text).ok()
}

/// Executable from a LEVI manifest, only if it exists, is an .exe and is named levi*.
fn launch_path(app: &str) -> Option<PathBuf> {
    let m = manifest(app)?;
    let exe = PathBuf::from(m["executable"].as_str()?);
    let name = exe.file_name()?.to_string_lossy().to_ascii_lowercase();
    (exe.is_absolute() && exe.is_file() && name.starts_with("levi") && name.ends_with(".exe")).then_some(exe)
}

/// Opens a LEVI app on the laptop (no arguments, no shell). JARVIS lives in Command Center.
pub fn launch_app(id: &str) -> Result<String, String> {
    let app = match id {
        "command-center" | "jarvis" => "command-center",
        "browser" => "browser",
        _ => return Err(format!("{id} can't be opened from the dock")),
    };
    let exe = launch_path(app).ok_or_else(|| format!("{app} executable not found"))?;
    Command::new(&exe)
        .current_dir(exe.parent().unwrap_or(Path::new(".")))
        .spawn()
        .map(|_| format!("opened {}", exe.display()))
        .map_err(|e| e.to_string())
}

fn cmdline(parts: &[std::ffi::OsString]) -> String {
    parts.iter().map(|s| s.to_string_lossy()).collect::<Vec<_>>().join(" ").to_ascii_lowercase()
}

/// Most recent of the folder, its .git/index and its top-level entries.
fn last_activity(path: &Path) -> SystemTime {
    let mut best = mtime(path);
    best = best.max(mtime(&path.join(".git").join("index")));
    if let Ok(rd) = std::fs::read_dir(path) {
        for e in rd.flatten().take(200) {
            best = best.max(mtime(&e.path()));
        }
    }
    best
}

fn mtime(p: &Path) -> SystemTime {
    std::fs::metadata(p).and_then(|m| m.modified()).unwrap_or(SystemTime::UNIX_EPOCH)
}

fn ago(t: SystemTime) -> String {
    let secs = SystemTime::now().duration_since(t).map(|d| d.as_secs()).unwrap_or(0);
    match secs {
        0..=59 => "just now".into(),
        60..=3599 => format!("{} min ago", secs / 60),
        3600..=86_399 => format!("{} h ago", secs / 3600),
        _ => format!("{} d ago", secs / 86_400),
    }
}

fn git(path: &Path, args: &[&str]) -> Option<String> {
    let out = Command::new("git")
        .arg("-C")
        .arg(path)
        .args(args)
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

fn git_info(path: &Path, v: &mut Value) {
    v["git"] = json!(true);
    if let Some(s) = git(path, &["status", "--porcelain=v2", "--branch"]) {
        let mut changed = 0;
        for line in s.lines() {
            if let Some(b) = line.strip_prefix("# branch.head ") {
                v["branch"] = json!(b);
            } else if let Some(ab) = line.strip_prefix("# branch.ab ") {
                let mut it = ab.split_whitespace();
                v["ahead"] = json!(it.next().and_then(|a| a.trim_start_matches('+').parse::<i64>().ok()).unwrap_or(0));
                v["behind"] = json!(it.next().and_then(|b| b.trim_start_matches('-').parse::<i64>().ok()).unwrap_or(0));
            } else if !line.starts_with('#') && !line.is_empty() {
                changed += 1;
            }
        }
        v["changed"] = json!(changed);
    }
    if let Some(s) = git(path, &["log", "-1", "--format=%cr%x1f%s"]) {
        let mut it = s.trim().splitn(2, '\u{1f}');
        v["commitAgo"] = json!(it.next().unwrap_or(""));
        v["commit"] = json!(it.next().unwrap_or("").chars().take(80).collect::<String>());
    }
}

/// TCP listeners on this laptop, from `netstat -ano`, minus Windows system services.
fn servers(names: &HashMap<u32, String>) -> Vec<Value> {
    const SKIP: [&str; 12] = [
        "system", "svchost.exe", "lsass.exe", "wininit.exe", "services.exe", "spoolsv.exe",
        "anydesk.exe", "onedrive.sync.service.exe", "adb.exe", "neatdm.exe", "searchhost.exe", "idle",
    ];
    let Ok(out) = Command::new("netstat").args(["-ano", "-p", "TCP"]).creation_flags(CREATE_NO_WINDOW).output() else {
        return vec![];
    };
    let out6 = Command::new("netstat").args(["-ano", "-p", "TCPv6"]).creation_flags(CREATE_NO_WINDOW).output().ok();
    let text = String::from_utf8_lossy(&out.stdout).to_string()
        + &out6.map(|o| String::from_utf8_lossy(&o.stdout).to_string()).unwrap_or_default();

    let mut seen: BTreeMap<u16, Value> = BTreeMap::new();
    for line in text.lines() {
        let cols: Vec<&str> = line.split_whitespace().collect();
        if cols.len() < 5 || cols[3] != "LISTENING" {
            continue;
        }
        let local = cols[1];
        let Some((addr, port)) = local.rsplit_once(':') else { continue };
        let Ok(port) = port.parse::<u16>() else { continue };
        if port >= 49152 {
            continue; // ephemeral / RPC ports
        }
        let pid: u32 = cols[4].parse().unwrap_or(0);
        let name = names.get(&pid).cloned().unwrap_or_else(|| format!("pid {pid}"));
        if SKIP.contains(&name.to_ascii_lowercase().as_str()) {
            continue;
        }
        let scope = if addr.starts_with("127.") || addr == "[::1]" { "local" } else { "network" };
        seen.entry(port).or_insert_with(|| {
            json!({ "port": port, "name": name.trim_end_matches(".exe"), "scope": scope })
        });
    }
    seen.into_values().take(10).collect()
}
