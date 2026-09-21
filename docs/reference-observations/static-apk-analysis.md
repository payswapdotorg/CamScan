# CAMSCAN-004 — Static Reference-Artifact Analysis (official CamScanner)

**Status: OBSERVED (static artifact facts, cryptographically verified) — live on-device behavior: NOT OBSERVED (see [REPORT.md](REPORT.md) blocker).**

Black-box analysis of the official CamScanner APK artifact only: zip listing, JAR
signature verification, binary AndroidManifest metadata parse. **No reverse
engineering, no decompilation, no copying of proprietary source/assets.**
Machine-readable companion: [static-apk-analysis.json](static-apk-analysis.json).

## Artifact provenance

| field | value |
|---|---|
| app | CamScanner (official, INTSIG Information Co., Ltd) |
| versionName | `7.25.5.2609020000` (latest stable at 2026-09-21) |
| versionCode | `72551` |
| obtained | 2026-09-21T22:36Z |
| source | APKCombo (reputable mirror): <https://apkcombo.com/camscanner/com.intsig.camscanner/download/phone-7.25.5.2609020000-apk> |
| vendor site check | <https://www.camscanner.com/download> serves only store links — no direct APK; mirror chosen per the work order |
| format | XAPK v2 bundle (mirror packaging of the Google Play split set) |
| bundle bytes / sha256 | `221499725` / `7ef8e46525a1210bbaa332d5f5d560ce30d768b4375ea92265d2e292d25dba65` |

Split set (Play-listing splits re-packaged by the mirror):

| split | bytes | sha256 |
|---|---|---|
| `com.intsig.camscanner.apk` (base) | 162035756 | `18e70e79928129ad87aed8a99880e6da0b80e834d8c10c34a1fd37ab8657051c` (sha1 `4f2e80d29afe7908e7d9ffb614b0bce91a2a6ef4` — matches the mirror's content-addressed filename) |
| `config.arm64_v8a.apk` | 59168248 | `2c525ce9eb3a3437c1765f5a2ee43d80f98ab1a50172f70a8a5864dd7ebbdc4a` |
| `config.en.apk` | 291225 | `1990469a1780920425e498b67b02232fca43f7f61204091b3afb82fb570b8e47` |

## Signature verification chain (mirror-tampering ruled out)

1. **Signer certificate** (extracted from `META-INF/BNDLTOOL.RSA`, PKCS#7):
   ```
   subject=issuer=C=CN, ST=Shanghai, L=Shanghai, O=www.intsig.com,
            OU=IntSig Information Co.,Ltd, CN=IntSig   (self-signed)
   serial=4B453CFA   validity=2010-01-07 → 2064-10-10
   ```
2. **PKCS#7 signature over `BNDLTOOL.SF`** — verified with
   `openssl smime -verify -inform DER -in BNDLTOOL.RSA -content BNDLTOOL.SF` (against the
   certificate carried in the same PKCS#7): **verification successful**.
3. **`BNDLTOOL.SF` digest over `MANIFEST.MF`** —
   `SHA-256-Digest-Manifest: qIFS6ym0sB4w2wFsUs6B8OayapRe25d8uxs4ghe37nQ=` —
   recomputed over the actual `MANIFEST.MF`: **matches**.
4. **Per-entry digests** — all **13,083 / 13,083** entries named in `MANIFEST.MF`
   (SHA-256 + SHA-1, JAR line-unfolding applied) verified against actual file
   bytes: **0 mismatches, 0 missing**.

Conclusion: every v1-signed byte of the artifact is covered by IntSig's own key.
The mirror passed through the genuine Play-listing splits unmodified.

## Application identity (AndroidManifest, base APK)

| field | value |
|---|---|
| package | `com.intsig.camscanner` |
| versionName / versionCode | `7.25.5.2609020000` / `72551` |
| minSdk / targetSdk | **21 (Android 5.0)** / **36** |
| label | CamScanner |
| application class | `com.intsig.camscanner.launch.CsApplication` |
| launchable activity | `com.intsig.camscanner.mainmenu.mainactivity.MainActivity` |
| notable attributes | `allowBackup=false`, `largeHeap=true`, `hardwareAccelerated=true`, `requestLegacyExternalStorage=true`, `preserveLegacyExternalStorage=true`, `usesCleartextTraffic=true`, `networkSecurityConfig` present, `taskAffinity=com.intsig.camscanner`, `extractNativeLibs=true` |

## Permissions (38 uses-permission + 3 declared custom)

**Runtime (dangerous) prompts expected on the Android 11 reference device:**

- `android.permission.CAMERA` (S003 core)
- `android.permission.READ_EXTERNAL_STORAGE`
- `android.permission.ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`
  (scanner asking for location — likely EXIF geotagging; watch for a bundled prompt at
  first scan — protocol note for S002/S003)

**Declared no-op / auto-granted on Android 11 (API 30) given targetSdk 36:**

- `WRITE_EXTERNAL_STORAGE` (auto-granted, no effect for targetSdk≥30 — *storage is
  scoped*; correction input for S011/S016/S017 expectations)
- `POST_NOTIFICATIONS`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED` (API 33+)
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (API 31+)

**Install-time (normal):** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
`VIBRATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`(+`_DATA_SYNC`), `GET_TASKS` (deprecated),
`CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_LOCATION_EXTRA_COMMANDS`, `USE_BIOMETRIC`,
`USE_FINGERPRINT`, `INSTALL_SHORTCUT`, `AD_ID`.

**Declared custom permissions (signature, protectionLevel 0x2):**
`com.intsig.camscanner.{READ_CAMSCANNER, WRITE_CAMSCANNER,
DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION}`.

Full list: [static-apk-analysis.json](static-apk-analysis.json) · mirror metadata
(XAPK `manifest.json`) lists 40 = the 38 uses-permissions + the 2 READ/WRITE custom
entries (cross-checked against the raw binary manifest — UTF-16LE string pool).

## uses-feature (all `required=false`)

`camera`, `camera.autofocus`, `camera.flash`, `telephony`, `wifi`,
`screen.portrait`, `usb.host`, `nfc` — installable on the emulator regardless of
these features; none force-filter the install.

## Native-code / ABI risk for the reference environment (open question)

- base APK contains **zero `lib/` entries**; **all native code ships in the
  `config.arm64_v8a.apk` split (arm64-v8a only)**.
- The pinned reference substrate is `system-images;android-30;default;x86_64`
  (AOSP) — the `default` images carry **no ARM translation layer**
  (`google_apis` images carry ndk_translation since API 28).
- Consequence: `adb install-multiple` of base+arm64 split should succeed, but any
  load-bearing native-lib load (scan engine) can fail on x86_64 with
  `UnsatisfiedLinkError`. **Must be probed empirically at the first reference run** —
  see mitigations in [REPORT.md](REPORT.md#open-questions--handoffs).

## Runtime library hints (from packaging metadata only)

Flutter module embedded (`assets/flutter_assets/`, `packages/aichat` — AI chat
features), `io.sentry` crash reporting, netty/okhttp/firebase networking stack.
These predict (not prove) first-run network destinations — actual domains: NOT
OBSERVED until the live session records them from logcat.
