# Mission 006 — LEVI DOCK UI/UX Design

Design canvas: https://claude.ai/artifact/CeRjyLEQb6jY6bCSizTDD7 (private; six artboards, clickable rail in Play mode).

## Assumptions
- **Landscape**, full screen, 1920×1080 px, 480 dpi → **640×360 dp**. Artboards are 960×540 (½ px): **1 artboard px = 2/3 dp**.
- Viewed at desk distance on a 5" panel → minimum label 16 art-px (~11 dp), body 18 (12 dp), metrics 38 (25 dp).
- Values shown are sample data; `[City]`, `[LAPTOP]`, `[Track title]` etc. are placeholders filled live.

## Screens
| # | Screen | Content |
|---|---|---|
| 01 | Dashboard | Clock, date, weather; CPU (sparkline), memory, storage, network ↓/↑, battery, CPU temp; link status footer |
| 02 | Dev Command Center | Active projects + branch, current agent, task progress, build/test/git tiles, live terminal tail, local servers |
| 03 | Media | Album art, title/artist, progress, prev / play-pause / next, volume slider (source: Windows GSMTC) |
| 04 | LEVI Assistant | Chat with project + model chips, action buttons on replies, inline notifications, text + voice input |
| 05 | Quick Controls | Allow-listed actions grid; risky ones (Sleep) marked and gated by a confirm dialog; service status list |
| 06 | Design system | Tokens, type scale, shape/motion/performance rules |
| 07 | LEVI Ecosystem & Shared Input | Tiles for LEVI Command Center, LEVI Browser, JARVIS, LEVI Personal Growth, Bitchord, LEVI DOCK (status + open on laptop); shared keyboard/trackpad panel with Laptop/Dock switch, edge handoff + `Ctrl+Alt+D` hotkey (proposed), clipboard sharing toggle |

Media (03) now targets **Bitchord**: source chip, album art, transport controls, volume, and a **synced-lyrics panel** (LRC timing) where lines glide up and the current line brightens and scales.

Navigation: fixed 76-px left rail (LEVI mark, 6 sections incl. Ecosystem, connection dot). Selected = accent-dim fill + accent icon.

## Ecosystem integration (requirements from the owner, 2026-11-23)
- All LEVI apps — LEVI Browser, LEVI Command Center, JARVIS, LEVI Personal Growth, Bitchord — report status to the LEVI DOCK host on the laptop and can be opened/controlled from the dock. Each app needs a small local status/command hook (to be defined per app; unknown yet).
- The laptop's **one keyboard and trackpad** drive every LEVI surface, dock included (Mission 012: host-side edge handoff + input forwarding via ADB/scrcpy control channel, now unblocked on LineageOS).
- **BitChord** (third-party, open source GPL-3.0, github.com/kushagrasinghx/BitChord — YouTube Music client): now-playing, controls and lyrics. Findings 2026-11-23:
  - Latest release v1.10 (2026-11-21) ships **Android APKs only** (incl. `armeabi-v7a`), `minSdk 26` → it **can run on the Mi 3W itself** (Android 11). No Windows build in that release; repo has some JVM/desktop UI code but no documented Windows media-controls (SMTC) support, local API or IPC. Has Discord Rich Presence and Last.fm/ListenBrainz scrobbling.
  - Lyrics: word/line-synced, fetched online from LRCLIB, KuGou, Musixmatch, Genius and others; not exposed to other apps.
  - Integration plan: if BitChord runs on the **dock**, the LEVI DOCK app reads its Android MediaSession (title, artist, position, play/pause/next) via notification-listener access (grantable over ADB) and fetches synced lyrics itself from **LRCLIB** by title/artist/duration. If it runs on the **laptop**, the host reads Windows media sessions (only if BitChord registers one — to verify) and does the same LRCLIB lookup.

## Tokens (for Android `colors.xml` / `dimens.xml`)
| Token | Hex | Use |
|---|---|---|
| ground | `#090B0E` | screen background |
| surface | `#12151A` | tiles, cards |
| surface-sunk | `#0D1014` | terminal, secondary buttons |
| line | `#1C2027` (strong `#242932`) | 1-px borders |
| text | `#E6E9EE` | primary text |
| text-2 | `#A9B1BE` | secondary text |
| muted | `#8C95A3` | labels (≥ 4.5:1 on surface) |
| accent | `#4C8DFF` | healthy / active / primary action |
| accent-dim | `#16243A` | selected rail item, own chat bubble |
| attention | `#F2A93B` | needs-you states (also a different shape: square vs dot) |

Type: Inter (UI), JetBrains Mono (numbers, code). Radii: tile 20, button 16, pill full. Spacing: 4-px grid.

## Performance rules (Snapdragon 801 / Adreno 330, ~150 MB free RAM)
- Flat fills only: no blur, shadows, gradients or translucent overlays except the confirm-dialog scrim.
- Metrics redraw ≤ 1 Hz; only changed views invalidate. Sparklines as a single `Path` on a `Canvas`.
- Motion animates **transform and opacity only** (hardware layers): screen change slide 24 dp + fade 220 ms; lyrics scroll 520 ms, current line scale 1.14 + color 380 ms; numeric values tween 300 ms; press feedback scale 0.97 / 120 ms; play/pause icon crossfade 180 ms; curve `cubic-bezier(0.2, 0.8, 0.2, 1)`. Animations run only while on screen; no infinite loops.
- Native Views (no WebView/Compose); app RSS target < 120 MB. Fonts bundled as subsets.
