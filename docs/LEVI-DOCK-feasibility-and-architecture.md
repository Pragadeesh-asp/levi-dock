# LEVI DOCK — Technical Feasibility & Architecture Proposal

*Status: proposal, no implementation started. Prepared 2026-11-22 after Mission 004.*

---

## 1. Verified device facts (read over ADB, 2026-11-22)

| Item | Value | Impact |
|---|---|---|
| Model | Xiaomi MI 3W (`cancro`), serial `b17bad2` | — |
| Android | **6.0.1 (API 23)**, MIUI Global 9.5.7.0 (`MXDMIFA`) | Better than feared (not 4.4). Runtime permissions, Doze, MediaCodec, WebSockets via OkHttp all available. |
| SoC / RAM | Snapdragon 800, **~1.9 GB** RAM total | Keep the app well under ~150 MB RSS; no heavy WebView/JS UI. |
| Display | 1080×1920, 480 dpi (xxhdpi) | Sharp; GPU fill-rate is the real limit → avoid full-screen blur/overdraw. |
| Storage | 12.5 GB `/data`, 6.5 GB free | Plenty. |
| Network | Wi-Fi up, `192.168.1.11/24` | LAN path available. |
| Bluetooth | Paired with laptop; also paired to realme 8 Pro as HID mouse | Spare input path. |
| Battery | 39 %, charging, 35.4 °C | Will be permanently powered — see risk R5. |
| Touch | Dead (`atmel-maxtouch` driver present, hardware broken) | All input must come from laptop/HID. |

## 2. Mission 004 outcome — how access works now

```
Laptop ──USB cable──► Mi 3W     scrcpy --otg (AOA HID): laptop keyboard + mouse appear as REAL USB HID devices
Laptop ──Wi-Fi ADB──► Mi 3W     adb 192.168.1.11:5555 : shell, install, port-forwarding, scrcpy video (view only)
```

* ADB is **properly authorized** ("Always allow from this computer" accepted by the owner using a Bluetooth mouse). No authorization was bypassed.
* **MIUI blocks ADB input injection and secure-settings writes** unless "USB debugging (Security settings)" is on, which needs a SIM + Mi account. AOA HID input sidesteps this legitimately, because the phone sees a physical keyboard/mouse.
* Windows only lets one process open the USB device, so ADB moved to Wi-Fi. `adb tcpip` resets on every phone reboot → `start-levi-dock.ps1` restores it (needs one boot-time USB ADB moment before OTG grabs the cable).
* Launcher: `start-levi-dock.ps1` (project root).

## 3. Feature feasibility

| Feature | Feasible? | Approach | Notes |
|---|---|---|---|
| **A. Dashboard** (time, weather, CPU, RAM, disk, net, battery, temps) | ✅ High | Windows agent collects metrics (Rust `sysinfo`, WMI/`windows` crate); pushes ~1 Hz over WebSocket; phone renders natively. | Temps: CPU package temp on Windows often needs LibreHardwareMonitor / vendor WMI; mark "where available". Weather fetched by the laptop (keeps API keys off the phone). |
| **B. Shared mouse & keyboard** | ✅ Works today (basic) / ⚠️ Medium for "seamless" | Today: scrcpy OTG/AOA. Seamless edge-crossing: Windows app with a low-level mouse hook that hands the cursor to the phone at a screen edge and drives AOA HID itself (libusb) instead of scrcpy's window. | Clipboard: do it in the companion app over WebSocket (scrcpy clipboard sync needs the blocked control channel). Android 6 lets a foreground app read/write the clipboard freely. |
| **C. Secondary display** | ⚠️ Medium–Hard | **Recommended:** "companion panels" — phone renders terminal output, build logs, docs, music etc. natively from data the laptop sends (cheap, crisp). **Optional true extended desktop:** Windows Indirect Display (IddCx) virtual monitor → capture → H.264 → phone hardware decode (MediaCodec, SD800 does 1080p30). | True extended display needs a virtual display driver installed on Windows (signed IddCx driver) — explicit approval required. Expect 50–120 ms latency over Wi-Fi; fine for logs, poor for video. |
| **D. AI assistant** | ✅ High | Phone = UI only. Laptop agent calls OpenRouter (or FreeLLMAPI) and streams tokens over WebSocket. | Keys stay on laptop. Voice: Android `SpeechRecognizer` depends on Google app on MIUI (unverified) → safer: phone records audio (mic works) → laptop does STT (Whisper local or API) and TTS. |
| **E. Dev command center** | ✅ High | Laptop agent watches git repos (`git2`), running processes/ports, build/test commands it launched, agent logs; publishes events. | Integrate with LEVI Command Center later via the same event bus. |
| **F. Media controller** | ✅ High | Windows Global System Media Transport Controls (GSMTC) via `windows` crate: title/artist/art, play/pause/next/prev; volume via Core Audio. | Works for Spotify, browsers, most players. |
| **G. Quick controls** | ✅ High | Laptop agent exposes an **allow-list** of actions (lock, screenshot, launch app X, run shortcut Y). Destructive ones need confirmation on the laptop. | Never a generic "run command" endpoint. |

## 4. Recommended architecture

```
┌──────────────────────── Windows 11 laptop ────────────────────────┐
│  LEVI DOCK Host (Tauri 2 · Rust core · React/TS settings UI)      │
│   ├─ metrics      (sysinfo, WMI, battery, net)                    │
│   ├─ media        (GSMTC, Core Audio)                             │
│   ├─ actions      (allow-listed, permission-gated)                │
│   ├─ dev monitor  (git2, process/port scan, build/test runners)   │
│   ├─ ai gateway   (OpenRouter / FreeLLMAPI, streaming)            │
│   ├─ device mgr   (adb + scrcpy/AOA orchestration, reconnect)     │
│   └─ WebSocket server  bound to 127.0.0.1:47800  (JSON messages)  │
└──────────────┬────────────────────────────────────────────────────┘
               │  adb reverse tcp:47800 tcp:47800   (preferred)
               │  or LAN + paired token              (fallback)
┌──────────────▼──────────── Xiaomi Mi 3W ──────────────────────────┐
│  LEVI DOCK Companion (Kotlin, minSdk 23, native Views + Canvas)   │
│   ├─ set as HOME app → always-on kiosk, survives MIUI killers     │
│   ├─ one OkHttp WebSocket client, auto-reconnect                  │
│   ├─ screens: Dashboard · Dev · Media · AI · Controls · Panels    │
│   └─ optional: mic capture, clipboard bridge, local notifications │
└───────────────────────────────────────────────────────────────────┘
```

**Key decisions**

1. **Laptop does the work, phone only renders.** All API keys, AI calls, metrics and actions live in the Rust host. The phone is a thin, fast client. This protects 1.9 GB RAM and keeps secrets off a device that can't be unlocked easily.
2. **Transport (revised after Mission 005): phone app hosts a WebSocket on its own 127.0.0.1; laptop connects via `adb forward`** (`adb reverse` fails over Wi-Fi ADB on this device). Originally proposed: WebSocket bound to localhost + `adb reverse`. The server is never reachable from the LAN or the internet; only the ADB-authorized phone can reach it. LAN mode is a fallback with a one-time pairing token + HMAC'd messages. (ADB over TCP on Android 6 is not encrypted — acceptable on the home network; documented.)
3. **Android UI: native Views + custom Canvas charts, not WebView/React Native/Compose.** Lowest memory, predictable 60 fps on SD800. Compose works on API 23 but costs more RAM/startup; WebView on this device is likely outdated.
4. **Windows: Tauri 2 / Rust / React confirmed compatible** with Windows 11. React is only for the laptop-side settings UI; the core is a Rust service usable headless.
5. **Protocol:** versioned JSON envelopes `{v, type, id, ts, payload}`; topics `metrics`, `media`, `dev`, `ai`, `action.request/result`, `clipboard`, `notify`. Metrics deltas at 1 Hz; everything else event-driven.

## 5. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | MIUI blocks `adb install` without "Install via USB" (also gated by SIM/Mi account). | Push APK with `adb push`, open the installer with an intent, confirm with the HID mouse; or enable "Unknown sources". Verify in Mission 005. |
| R2 | MIUI kills background apps / blocks autostart. | Make the companion the **default Home app**; grant Autostart + "No restrictions" battery mode once via HID mouse. |
| R3 | Wi-Fi ADB resets on reboot; OTG holds the USB device. | Host's device manager runs the boot sequence automatically (`start-levi-dock.ps1` logic in Rust). Long term: replace scrcpy OTG with the host's own AOA HID so ADB-over-USB and HID can be coordinated. |
| R4 | No input injection via ADB (MIUI). | All interaction through AOA HID or through the companion app's own UI (it handles its own events, no injection needed). |
| R5 | 24/7 charging of a ~2013 Li-ion battery → swelling / heat. | Monitor battery temp & health from the app; alert above 40 °C; dim screen / OLED-friendly dark UI; consider a smart plug or timer-based charging. No root-based charge limit (forbidden without approval). |
| R6 | Screen burn-in / power on a permanent display. | Dark theme, subtle pixel shifting, night dimming schedule. |
| R7 | Voice recognition availability on MIUI. | Laptop-side STT. |
| R8 | True secondary display needs a Windows virtual display driver. | Treat as optional, separate approval. |

## 6. What depended on Android access — now unblocked

| Needs ADB (now ✅) | Needs only the companion app | Laptop-only |
|---|---|---|
| Installing the companion APK · `adb reverse` transport · scrcpy mirror · reading device facts · granting Autostart via HID | Dashboard, Dev center, Media, AI, Quick controls UI, clipboard, notifications | Metrics collection, AI gateway, media/actions backends, virtual display driver |

Remaining gated item: anything requiring MIUI "Security settings" (ADB input injection, `settings put`, possibly `adb install`). Every feature has a path around it.

## 7. Phased roadmap

| Phase | Missions | Deliverable | Exit criteria |
|---|---|---|---|
| **P0 Access** | 004 ✅ | Authorized ADB, HID control, mirror, launcher script | Done |
| **P1 Compatibility** | 005 | Verify APK install path, Home-app/kiosk behavior, Autostart, WebSocket over `adb reverse` with a tiny hello-world APK; battery/thermal baseline | Hello-world app survives reboot + 24 h |
| **P2 Design** | 006 | LEVI design system (charcoal/black, single blue accent, type scale, chart style), screen layouts for 1080×1920 portrait/landscape | Approved mock-ups |
| **P3 Core link** | 007 + 008 (thin) | Rust host service + Kotlin client, protocol v1, reconnect, pairing | Round-trip < 30 ms over USB-reverse |
| **P4 Dashboard** | 009 | Metrics + weather + clock screen | 1 Hz updates, phone CPU < 15 % |
| **P5 Controls** | 011 | Media controller + allow-listed quick actions | Works with Spotify/browser; destructive actions confirmed |
| **P6 Dev center** | (E) | Git, processes, ports, build/test status, notifications | Live status of LEVI projects |
| **P7 AI** | 010 | Chat UI, streaming, project context, voice via laptop STT | Streaming responses, keys never on phone |
| **P8 Input** | 012 | Host-native AOA HID + edge-crossing + clipboard bridge | Seamless cursor handoff |
| **P9 Display** | 013 | Companion panels; optional virtual-display spike | Decision on IddCx driver |
| **P10 Polish** | 014 | Optimization, soak tests, auto-start on Windows login | 7-day unattended run |

## 8. Decisions needed from you

1. Approve the **thin-client** architecture (laptop computes, phone renders) and **native Views** on Android.
2. Approve **`adb reverse` over Wi-Fi ADB** as the primary transport (LAN token mode as fallback).
3. Primary orientation for the dock: **portrait or landscape**?
4. Secondary display: companion panels only, or also spike the virtual-display driver later?
5. Charging strategy given R5 (always plugged vs. smart plug/timer).
