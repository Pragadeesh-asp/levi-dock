# LEVI DOCK — Custom ROM Selection (Mi 3W / cancro)

*Researched 2026-11-22 from the XDA "Mi 3 Android Development" forum and Android File Host. Nothing flashed.*

## Recommendation

**LineageOS 18.1 (Android 11) by tarkzim — `lineage-18.1-20211007-UNOFFICIAL-cancro.zip` (490.9 MB, Android File Host).**

Why this one:
- Most-used cancro build (4,858 downloads of the final build; 14-page thread, users on Mi 3W / Mi 4W report it "works perfect", most recent thanks Oct 2023, mirror posted Jun 2026).
- Standard install: **official TWRP** (already verified on this phone) → wipe → flash zip. No repartitioning, no opaque scripts.
- Working list covers everything LEVI DOCK needs: Wi-Fi, Bluetooth, USB ADB/MTP, audio, sensors. **SELinux enforcing.**
- Source trees public (github.com/Mi3-4-Dev). The other Android 11 builds (F2FS, BaikalOS) are based on the same sources.
- Removes all MIUI blocks → normal `adb shell input`, `settings put`, `adb install`, stay-awake; plus Android 11 gives **scrcpy audio forwarding** and **Wireless debugging**.

## Candidates compared

| ROM | Android | Last build | Install method | Notes | Verdict |
|---|---|---|---|---|---|
| **LineageOS 18.1 (tarkzim)** | 11 | 2021-10-07 | Official TWRP + zip | Mature, widely used; issues: GApps "not enough space" (use LiteGapps/none), Wi-Fi fails on **WPA3-only** networks | ✅ **Pick** |
| LineageOS 21.0 (Cyberspace0000) | 14 | 2026-10-10 | Fastboot `flash_all.bat` from Google Drive | Posted Oct 2026, 1 reply, no user reports; opaque flash script could rewrite partitions | ❌ too new / unverified |
| LineageOS 18.1 F2FS (LK-XiaoMi) | 11 | 2026-01-23 | Google Drive | **Bluetooth not working** (2 users, unresolved), random reboots (thermal), forced encryption, 720p default | ❌ Bluetooth is our fallback input |
| BaikalOS 11.1 | 11 | 2022 (patch Feb 2022) | Official TWRP + zip | AICP-based, built on tarkzim's sources, more features/weight, little user feedback | ➖ no advantage |
| AOSP 10 / LineageOS 17.1 | 10 | 2019–2020 | TWRP | Older, superseded | ➖ |
| Official LineageOS 14.1 | 7.1 | discontinued | TWRP | Old Android | ➖ fallback only |

## Trade-offs to accept

- **Security patch level is 2021.** Acceptable for a LAN-only desk appliance; do not use it for banking/accounts. LEVI DOCK keeps all keys on the laptop.
- **Unofficial build** (not signed by LineageOS). Mitigation: download from the developer's own AFH folder linked in the thread; record SHA-256.
- **Google apps:** not required for LEVI DOCK (app is sideloaded). Optional: LiteGapps arm/30 if Play Store is wanted.
- **Router check:** must offer WPA2 (or WPA2/WPA3 mixed) — WPA3-only fails on this ROM.

## Result (2026-11-23)

Installed with the owner's explicit approval. TWRP 3.4.0 RAM-booted → wiped system/data (kept internal storage)/cache/dalvik → `adb sideload` (`script succeeded… RC=0`) → booted LineageOS 18.1, Android 11, userdebug. USB debugging on by default; laptop authorized with "Always allow". Verified: `adb shell input` works, `settings put global stay_on_while_plugged_in 7` works, Bluetooth on, Wi-Fi connected, single scrcpy window with control + audio. Backup: `backups/mi3w-miui-2026-11-22/`.

## Install plan (each destructive step needs explicit approval)

1. **Backup** — RAM-boot TWRP (`fastboot boot`, no flash), make a TWRP backup, copy it plus photos/files to the laptop. *(non-destructive)*
2. Download ROM (+ optional LiteGapps), verify checksums.
3. **Flash TWRP to recovery** (`fastboot flash recovery`) — or keep using RAM-boot only.
4. **Wipe** system/data/cache — *erases the phone* — then flash ROM zip from TWRP via `adb sideload` (all driven from the laptop with `adb shell twrp …`, no touch needed).
5. First boot → setup wizard with **OTG mouse** → enable Developer options + USB debugging.
6. Laptop plugged in → accept "Always allow" with the **realme Bluetooth mouse** (pair it in step 5).
7. Verify: `adb shell input` works, scrcpy single window with full control + audio, Wi-Fi, Bluetooth, battery/thermal.
8. Rollback path: restore TWRP backup, or flash official MIUI fastboot image.
