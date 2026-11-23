# lineage-18.1-20211007-UNOFFICIAL-cancro.zip — verification (2026-11-22)

| Check | Result |
|---|---|
| Source | Android File Host, developer tarkzim's own folder (flid 319926, fid 7161016148664819856), linked from his XDA thread |
| Size | 490.9 MB |
| MD5 (published on AFH) | `0d7d9a54efe78111b4fbd2728fd24ef9` — **match** |
| SHA-256 (recorded locally) | `eeb076cd6c84defe7a0fbd5dab998799800d009a26dcf51694fafb7ef4973305` |
| Build | `Xiaomi/lineage_cancro/cancro:11/RQ3A.211001.001/eng.tark.20211007.085756:userdebug/test-keys`, SDK 30, security patch 2021-10-01 |
| Partitions written | **system** (block image) and **boot** only — no bootloader, modem, EFS or partition-table changes |
| Device assert | `ro.product.device == cancro` → phone: `cancro` ✅ |
| Baseband assert | `MPSS.DI.4.0` → phone: `MPSS.DI.4.0-eaa9d90` ✅ |
| SoC check (`device_check.sh`) | expects raw_id 1978 (Mi 3W) → phone: `1978` ✅ (keeps NFC, Mi 3 audio config) |

Notes: `userdebug` build → `adb root` available after install. Signed with test-keys (normal for unofficial builds).
