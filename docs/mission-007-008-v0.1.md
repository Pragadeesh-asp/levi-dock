# Missions 007 / 008 — LEVI DOCK v0.1 (first working build), 2026-11-23

## What exists
| Part | Path | Notes |
|---|---|---|
| Dock app | `android/` | Kotlin, plain Android framework (no AndroidX/Compose), `com.levi.dock`, minSdk 23, armeabi-v7a only, debug APK 814 KB. One canvas-drawn `DashboardView` in the design's 960×540 units ×2. `LinkServer` = loopback JSON-lines server on 127.0.0.1:47800. |
| Laptop host | `host/` | Rust (`levi-host`), sysinfo 0.40 + windows-sys (battery). Finds the dock via adb, `adb forward`, auto-starts the app, reconnects. |
| Protocol | `docs/protocol-v1.md` | host→dock `metrics` 1 Hz, `ping`/`pong` for latency. |
| Launcher | `start-levi-dock.ps1` | Also starts `levi-host` if not running. |

## Verified on the Mi 3W (LineageOS 18.1)
- Dashboard shows live laptop data (host "Cathy"): CPU % + GHz + sparkline, memory 6.5/7 GB, C: 89 % used (attention colour ≥ 85 %), network ↓/↑, battery 100 % plugged in, dock temp; link latency 3 ms over USB ADB.
- No crashes in the crash buffer.
- Resource use: **~23 MB PSS**, **~9 % CPU** (after capping tween frames at 30 fps and skipping sub-1-unit tweens; was 22 %).
- Host survives an app reinstall/restart and relinks in ~2 s.

## v0.2 — Media + synced lyrics (2026-11-23)
- New: shared `LeviScreen` base (rail, artboard scaling, touch mapping), tappable rail with 220 ms slide+fade between Dashboard and Media; `adb shell am start -n com.levi.dock/.MainActivity --ei screen 2` opens Media.
- `MediaHub`: follows the dock's active media session (BitChord preferred) via `MediaSessionManager`; needs notification-listener access for `MediaListener`, granted with `adb shell cmd notification allow_listener com.levi.dock/com.levi.dock.MediaListener`. Controls send media-button events (BitChord ignores a bare `play()` while idle).
- `Lyrics`: LRCLIB `/api/get` then `/api/search` (duration match ±6 s), LRC parser (multi-tags, word tags stripped), title/artist clean-up, 30-entry cache.
- `MediaView`: art, title/artist, progress + seek, prev/play-pause/next, volume slider (STREAM_MUSIC), "Open BitChord" when idle, lyrics panel with 520 ms glide and current-line scale/colour 380 ms.
- Verified with BitChord v1.10 playing "Bang Bang (New Version)" (K'NAAN) on the dock speaker at volume 4/25: metadata + art shown, LRCLIB synced lyrics found and following playback, play from idle / pause / Open BitChord all work. Memory ~43 MB PSS (art + lyrics), CPU ~1 % paused.

## v0.3 — Dev Command Center (2026-11-23)
- Host `src/dev.rs`: LEVI project folders + git (`status --porcelain=v2 --branch`, `log -1`), coding agents and dev tools from the process list (sysinfo cmdlines), TCP listeners from `netstat -ano` with local/network scope. ~1.9 s per sample on its own thread.
- Dock `DevView`: project cards (most recently changed = ACTIVE), Git / Last commit / Coding agents tiles, Local servers table (NETWORK in attention colour), Running tools chips. Rail now switches Dashboard ↔ Dev ↔ Media.
- Verified on the dock with real data: 4 LEVI projects (3 git repos on `wip/week1-stabilize`), Claude + Codex, Docker/Gradle/Kotlin daemon, 7 listeners. ~1.6 % CPU, ~28 MB PSS.
- Observation: MySQL (3306, 33060) and Postgres (5432) listen on all network interfaces on the laptop, not just localhost.
- Not yet: build/test pass/fail (needs a `levi run <cmd>` wrapper or per-project hooks), terminal tail, JARVIS (not under `C:\projects\LEVI`).

## v0.4 — Ecosystem + shared keyboard & trackpad (2026-11-23)
- Ecosystem screen (rail item 6): LEVI Command Center, LEVI Browser (Spaces count), JARVIS (part of Command Center), LEVI Personal Growth / LEVI OS (Next.js dev server detection), BitChord (on the dock), LEVI DOCK. Tapping a launchable tile sends `open-app`; BitChord opens on the dock.
- Pairing token + dock→host commands (see protocol doc). Verified: token provisioned and stored; `input-mode` dock/laptop round trip from the Ecosystem buttons works.
- LeviInput helper verified: Android registered "LEVI Keyboard" and "LEVI Mouse" and showed a real pointer moving from HID reports.
- Verified by the owner (2026-11-23): real laptop trackpad + keyboard on the dock and the edge/hotkey handoff "work well".
- Not yet verified: clipboard both ways, opening Command Center/Browser from the dock (not triggered automatically: Command Center's first launch creates a DB snapshot and switches to WAL).

## v0.5 — LEVI Assistant (2026-11-23)
- Dock `AssistantView` (rail item 4): conversation bubbles, streaming reply with "thinking" indicator, project + provider/model chips, New chat (Ctrl+L), mouse-wheel/drag scroll, input from the shared LEVI Keyboard (Enter send, Shift+Enter newline, Ctrl+V paste).
- Host `src/ai.rs`: OpenAI-compatible streaming chat, key in Credential Manager (`levi-host --set-ai-key`), config file, project-aware system prompt.
- Verified without a key: typed question → token-checked `ai-ask` → host → "No API key yet…" shown as an error bubble.
- Verified with the owner's OpenRouter key (`openrouter/auto`): "Which of my projects have uncommitted changes?" → streamed reply "LEVI Personal Growth, LEVI Command Center, and LEVI Browser each have 1 uncommitted file." (correct, from the live `dev` data).
- Key handling: `--set-ai-key` (UTF-8) and keys saved through Windows' Credential Manager screen (UTF-16) both work; `levi-host --check-ai-key` describes the stored key's length/format/provider prefix without printing it. A first key without the `sk-or-` prefix got OpenRouter 401.
- Input helper now exits cleanly when levi-host stops (no crash log).

## v0.6 — Click fix, Quick Controls, "Hey LEVI" (2026-11-23)
- **Click bug (owner report: "can't choose any tab, can't play music").** Root cause: the broken `atmel-maxtouch` panel floods phantom touches (~4,000 events / 6 s). Android's InputDispatcher then drops other pointer devices' events ("a pointer for a different device is already active"), so the LEVI Mouse button *release* was often lost and buttons that act on release never fired. Disabling the panel needs DISABLE_INPUT_DEVICE (the Android 11 shell lacks it; root would work). Fix in the app: mouse clicks act on press; touches from `atmel*` are ignored. Verified with a simulated dock-mode click through levi-host (test switch `LEVI_ALLOW_INJECTED=1`) that switched screens. Other apps (e.g. BitChord's own UI) can still lose clicks while phantom touches are active.
- **Quick Controls** (rail item 5): Lock Windows, Screenshot (Win+PrtScn), Terminal (wt), VS Code, File Explorer, Task Manager, Mute, Volume −/+, Sleep (confirmation dialog on the dock); laptop media prev/play/next; services list. Host `src/quick.rs` = fixed allow-list, `quick` command → `quick-result`. Verified: Volume + / − round trip.
- **"Hey LEVI"** (`src/voice.rs`, `assets/levi-voice.ps1`): Windows' built-in offline recognizer (System.Speech en-US) on the laptop microphone. Grammars: `hey levi` + fixed commands; `hey levi` + dictation for questions; bare `hey levi` opens 8 s of free dictation. Actions: screens, dock music (play/pause/next/previous), dock volume, Quick Controls (not Sleep), switch input, questions → Assistant (dock shows the question, streams the answer; laptop speaks the first two sentences). Dock shows a "HEY LEVI · …" banner. Disable with `LEVI_VOICE=0`. Unit tests for phrase mapping pass. Not yet verified with a real voice (laptop speaker → mic loopback is filtered by echo cancellation).

## v0.7 — "Hey LEVI" on the dock mic + speaker (2026-11-23)
- Owner: laptop mic didn't react → use the dock's mic and speaker. LineageOS has no Google speech service, so recognition stays on the laptop.
- Dock `VoiceService` (foreground, type microphone): `127.0.0.1:47801` streams 16 kHz mono PCM16 from `VOICE_RECOGNITION` (+ platform AGC, adaptive gain up to 4× without clipping); `127.0.0.1:47802` plays 16 kHz PCM16 replies (USAGE_ASSISTANT, music ducks). Both require the pairing token as the first line (no other app can tap the mic). RECORD_AUDIO granted by levi-host via `pm grant`.
- Host: `adb forward` 47801/47802; `levi-voice.ps1` reads the dock stream through a seekable wrapper (SAPI rejects non-seekable streams); replies are synthesised to PCM and streamed to the dock. `LEVI_VOICE_SOURCE=laptop` switches back to the laptop mic/speakers.
- Two-step mode: "Hey LEVI" → banner "Listening…" → command without prefix (or a question) within 8 s.
- Measured: dock mic level after gain peak ≈ 8 k / RMS ≈ 400 for the dock's own speaker at 10/25. Loopback test (synthetic voice from the dock's speaker into its own mic) reliably matched "hey levi" (conf 0.66–0.82) but garbled the command words — a real voice still needs to be tried. App CPU while listening ≈ 9 %.

## v0.8 — Wake word offline, command via FreeLLMAPI Whisper (2026-11-23)
- Owner test: "Listening…" appeared but nothing happened. Host log: wake word recognised 3×, but Windows' offline SAPI never matched a follow-up command from the dock mic audio.
- New pipeline: levi-host buffers the last 20 s of dock audio and relays it to `levi-voice.ps1`, which now only spots "hey levi" (and reports how much of the phrase followed "levi"). levi-host then captures the command (until ~0.9 s of silence after speech, max 8 s; counts back from the newest audio because SAPI's AudioPosition is per Recognize() call), strips "hey levi", and transcribes it.
- Transcription: OpenRouter refused audio (402: needs ≥ $0.50 credit). Now uses the owner's local **FreeLLMAPI** router (`voiceBaseUrl` default `http://127.0.0.1:31415/v1`): `/audio/transcriptions` (Whisper, default model `whisper-large-v3`) first, audio-chat model fallback. Key in Credential Manager "LEVI DOCK Voice" via `levi-host --set-voice-key`; `levi-host --voice-check [clip.wav]` lists speech models and tests a clip without printing the key. FreeLLMAPI lists 284 models incl. Whisper-style transcription, Gemini Flash, Voxtral.
- Verified: test clip → "Open the media screen."; full loop through the dock speaker → dock mic → wake word → Whisper "Hey Levi Open media" → Media screen opened on the dock (~4 s). Tests: 6 voice test groups pass.

## Known gaps / next
- Laptop CPU temperature: not exposed by Windows on this laptop → tile shows "—" (needs LibreHardwareMonitor or vendor WMI).
- Weather not wired (needs a provider + city; host-side fetch).
- Other screens (Dev, Media/BitChord + lyrics, Assistant, Controls, Ecosystem) and the dock→host command channel (needs pairing token, see protocol security notes).
- Fonts: system sans/mono for now; bundle Inter + JetBrains Mono subsets later.
- Not yet set as Home app; not yet a git repository.
