# Mission 005 — Hardware & Android Compatibility Report

*Run 2026-11-22. Read-only probes over Wi-Fi ADB; nothing on the phone was changed.*

## Phone

| Area | Result | Verdict / design rule |
|---|---|---|
| SoC | Qualcomm **MSM8974PRO-AB** (Snapdragon 801-class), 4 cores, 2.27 GHz max, `interactive` governor | Fine for native UI. |
| ABI | **armeabi-v7a (32-bit only)** | Build `armeabi-v7a` only if native libs are ever used; prefer pure Kotlin/Java. |
| RAM | 1.89 GB total, ~95 MB free + ~675 MB cache at idle (MIUI) | Target < 120 MB app RSS. |
| App heap | `heapgrowthlimit` **128 MB** (`largeHeap` 512 MB) | Keep bitmaps small, no image-heavy UI. |
| GPU | **Adreno 330, OpenGL ES 3.0** | Hardware-accelerated Canvas OK; avoid RenderScript blur / full-screen shaders. |
| Video codecs | HW H.264 decode + encode, HW HEVC decode | Future secondary-display stream feasible (H.264). |
| Android / MIUI | 6.0.1 (API 23), MIUI V9 | `minSdk 23`. |
| WebView | **Chromium 95** (2021) | Usable for a docs/markdown panel; not for the main UI. |
| Google services | Play Store, GMS, **Google app** (speech), Google TTS, TalkBack, Gboard | On-device voice input + TTS are possible (still verify offline/online behaviour). |
| Launcher | `com.miui.home` only | Companion can register as Home app → kiosk mode. |
| Unknown sources | `install_non_market_apps = 0` (off) | Must be enabled once via HID mouse, or install via `adb install` (test pending). |
| ADB input / secure settings | Blocked by MIUI (needs SIM + Mi account) | Never depend on `input`, `settings put`, `pm grant`. |
| Shell tools | `toybox` (nc, netcat), `curl` present | Handy for diagnostics. |
| Battery | Li-poly, health **GOOD**, 4.05 V, 40 %, 35.1 °C; charging at **500 mA (USB)** | USB 2.0 port charging is slow; screen-on at full brightness may drain faster than it charges. Use dim/dark UI. |
| Thermal | CPU sensors 45–48 °C idle-with-mirror, `msm_therm` 57 °C | Watch this during soak test; throttle refresh rate if > 65 °C. |

## Transport

| Test | Result |
|---|---|
| `adb reverse` over Wi-Fi ADB | ❌ fails (`more than one device/emulator`), so the phone can't reach the laptop through `adb reverse` on this setup. |
| `adb forward` over Wi-Fi ADB | ✅ works. Laptop → `127.0.0.1:47801` → phone localhost listener. Connect + first message **134 ms** (setup cost; persistent socket will be much lower). |
| Wi-Fi link quality | ✅ ping to router avg **4.1 ms**, 0 % loss. |

**Architecture change:** flip the connection direction. The **phone app hosts a WebSocket server on its own `127.0.0.1`**, and the **laptop host connects to it via `adb forward`**. Still private: nothing listens on the LAN on either side, and only the ADB-authorized laptop can reach the phone port. The LAN + pairing token mode stays as the fallback.

## Laptop toolchain

| Tool | Status |
|---|---|
| Rust 1.100 (msvc), Cargo | ✅ |
| Visual Studio 2022 (C++ build tools assumed) | ✅ present |
| Node 24.21, npm 11.19 | ✅ |
| WebView2 157 | ✅ (Tauri runtime) |
| Git 2.56, Python 3.14 | ✅ |
| **JDK 17** | ❌ missing — needed for Android builds |
| **Android SDK** (cmdline-tools, platform-tools ✅, build-tools, platform android-34/35) | ❌ missing |

Windows host (Tauri) can be built today. Android companion needs JDK 17 + Android SDK command-line tools (~1.5 GB) — **awaiting approval**.

## Bootloader (read-only, 2026-11-22)

`fastboot oem device-info`: **Device unlocked: false**, **Device tampered: true**, charger screen disabled. `product: MSM8974`.

* Windows fastboot driver: Google USB Driver ("Android Bootloader Interface") bound to `USB\VID_18D1&PID_D00D\B17BAD2`. Phone leaves fastboot after ~30 s without a driver; enter it with Vol-Down + Power.
* **Lock is NOT enforced.** `fastboot boot twrp-3.4.0-0-cancro.img` (official, SHA-256 `53cd4ed7…d9e19f` verified) was accepted and TWRP 3.4.0-0 ran from RAM (adb showed `recovery`, `omni_cancro`). Nothing was flashed or wiped; phone rebooted back to MIUI 6.0.1 normally. → Custom recovery / ROM install is technically possible without Xiaomi's unlock process.
* ROM landscape: official LineageOS 14.1 (Android 7.1, discontinued); unofficial LineageOS 18.1 (Android 11, F2FS) on XDA — unofficial, verify before use.

Laptop toolchain update: JDK 17 (Microsoft), Android SDK cmdline-tools, platforms;android-35, build-tools;35.0.0, Google USB Driver installed; Android SDK License accepted.

## Still to verify (needs a test APK)

1. `adb install` allowed with MIUI "Install via USB" off? Fallback: push + system installer + HID click, or enable Unknown sources.
2. App set as Home survives reboot; MIUI Autostart / battery-saver behaviour.
3. Phone-side WebSocket server via `adb forward`; sustained 1 Hz traffic.
4. 24 h soak: RSS, CPU %, battery temperature.
