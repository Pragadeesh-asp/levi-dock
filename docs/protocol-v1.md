# LEVI DOCK link — protocol v1

## Transport
- The **dock app** listens on its own loopback **127.0.0.1:47800** (never on Wi-Fi/LAN).
- The **laptop host** runs `adb -s <serial> forward tcp:47800 tcp:47800` and connects to `127.0.0.1:47800` on the laptop.
- Works over USB or Wi-Fi ADB; only the ADB-authorized laptop can reach the port.
- Framing: one UTF-8 JSON object per line (`\n`). Lines over 64 KB are ignored.
- One client at a time. The dock drops a link with no traffic for 6 s.

## Messages

| Direction | `type` | Fields |
|---|---|---|
| host → dock | `ping` | `t` (host ms epoch), every 2 s |
| dock → host | `pong` | `t` echoed; host computes round-trip time |
| host → dock | `metrics` | `ts`, `host` (name), `cpu` (%), `cpuMhz`, `mem {used,total}` (bytes), `disk {free,total}` (bytes, C:), `net {rx,tx}` (bytes/s), `battery {pct (-1 = none), charging, ac}`, `cpuTemp` (°C or null), `rtt` (ms or -1) — every 1 s |

| host → dock | `dev` | `projects[] {name, active, git, branch, changed, ahead, behind, commit, commitAgo, modifiedAgo}` (folders under `LEVI_PROJECTS`, default `C:\projects\LEVI`, most recently changed first, max 6), `agents[] {name, processes}` (Claude, Codex, Cursor, Windsurf), `tools[] {name, processes}` (Cargo, Gradle, Kotlin daemon, Node/Vite/Next/Tauri, Python/pytest, Docker…), `servers[] {port, name, scope: local|network}` (TCP listeners < 49152, Windows services filtered) — gathered on a background thread every 5 s, sent when changed |

| host → dock | `dev.apps[]` | `{id: command-center|browser|jarvis|growth, name, running, detail, launchable}` from the LEVI ecosystem manifests (`%LOCALAPPDATA%\LEVI\ecosystem\*.json`) and the process list |
| host → dock | `input` | `mode: laptop|dock`, `ready` (LEVI Keyboard + Mouse exist on the dock), `clipboard` (sharing on) — on every change |
| host → dock | `clipboard` | `text` — laptop clipboard, sent when input moves to the dock (if sharing is on) |
| host → dock | `clipboard-request` | sent when input returns to the laptop; the dock answers with a `clipboard` command if it can read its clipboard (Android 10+: only while LEVI DOCK has focus) |
| dock → host | `cmd` | `token` + `action`: `open-app {app}`, `input-mode {mode}`, `clipboard-share {on}`, `clipboard {text}` |

Unknown types and fields are ignored (forward compatible). `levi-host --dump-dev` prints one `dev` sample.

## LEVI Assistant
| Direction | `type` / action | Fields |
|---|---|---|
| host → dock | `ai-status` | `ready` (key present), `provider`, `model` — at link start |
| dock → host | `cmd` `ai-ask` | `id`, `text` (≤ 4000 chars) |
| dock → host | `cmd` `ai-clear` | forget the conversation history |
| host → dock | `ai-delta` | `id`, `text` (streamed chunk) |
| host → dock | `ai-done` | `id`, `model` or `error` |

- The host calls an OpenAI-compatible `/chat/completions` endpoint with `stream: true` (`src/ai.rs`, ureq + rustls). Settings: `%LOCALAPPDATA%\LEVI\dock\assistant.json` (`provider`, `baseUrl`, `model`; default OpenRouter, `openrouter/auto`). Works with FreeLLMAPI or any compatible base URL.
- API key: Windows Credential Manager, generic credential **"LEVI DOCK Assistant"**, set by the owner with `levi-host --set-ai-key` (input not echoed). It is never sent to the dock.
- System prompt includes a summary of the latest `dev` sample (projects, git, agents, tools, servers, LEVI apps), fenced as data. History: last 16 messages, in memory only. `max_tokens` 900.

## Pairing token (dock → host commands)
- `levi-host` creates a random 256-bit token once (`%LOCALAPPDATA%\LEVI\dock\token`, via `BCryptGenRandom`) and, on every link, gives it to the app with `adb shell am broadcast -n com.levi.dock/.TokenReceiver --es token …`.
- `TokenReceiver` requires `android.permission.DUMP`, which only the ADB shell and the system hold, so other apps can't plant a token. The app stores it in private preferences.
- The host ignores every `cmd` whose `token` doesn't match. `open-app` only starts an existing absolute `levi*.exe` named in a LEVI manifest, with no arguments and no shell.

## Shared keyboard & trackpad
- `input-helper/LeviInput.java` (dex, pushed to `/data/local/tmp/levi-input.jar`) runs as the ADB shell via `app_process`, creates **LEVI Keyboard** (boot keyboard) and **LEVI Mouse** (5 buttons, 16-bit X/Y, wheel, AC pan) through `/dev/uhid`, and replays frames from stdin: `[type][len][payload]` (1 = mouse, 2 = keyboard, 9 = keep-alive). The Mi 3W's 3.4 kernel lacks `UHID_CREATE2`, so it falls back to legacy `UHID_CREATE`/`UHID_INPUT` (descriptor passed by pointer).
- `levi-host` (`src/input.rs`) installs `WH_MOUSE_LL` + `WH_KEYBOARD_LL`. Right screen edge (4 pushes within 600 ms) or **Ctrl+Alt+D** → dock mode: events are swallowed on Windows and sent as HID reports; the laptop cursor is parked at the edge. Leaving: Ctrl+Alt+D, or pushing past the dock's left edge (position estimated from the deltas; exits late rather than early). Keys/buttons held when switching are released on the side that saw them pressed. Media/volume keys stay with the laptop.
- If the helper or adb dies, the host switches back to laptop mode immediately; closing `levi-host` removes the hooks and the virtual devices.

## Security notes (v1)
- v1 traffic is **read-only display data** from host to dock; the dock sends nothing but `pong`.
- Any app on the dock could also connect to its loopback port, so before commands flow dock → host (media control, quick actions, v2), the link gets a **pairing token** handshake: the host provisions a random token to the app over ADB, every session starts with `hello {token}`, and destructive actions still need confirmation on the laptop.

## Running
- Host: `host\target\release\levi-host.exe` (finds `C:\platform-tools\adb.exe`, override with `LEVI_ADB`; device with `LEVI_SERIAL`). It sets up the forward, starts the app if needed, and reconnects automatically.
- Dock app: `com.levi.dock` (package), launcher label "LEVI DOCK".
