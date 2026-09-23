# CLI-First Android Bootstrap Experiment — 2026-09-23

**Operator directive 2026-09-23 (step 3):** reproduce the known-good E2B
environment using the newer Android CLI flow, from a fresh sandbox, through
the full chain — without touching the known-good `sdkmanager`/`avdmanager`
bootstrap (`lab/providers/e2b/bootstrap.py`).

## Verdict

**PROVEN — the full CLI-first chain works on a fresh E2B desktop sandbox,
with the unified `android` CLI real, agent-oriented, and verified for SDK
installation.** Decision: **COMPLEMENT, not replace** (details §5).

## The newer unified `android` CLI

cmdline-tools **build 16111833** (newest of 25 builds listed in
`repository2-3.xml`; the known-good bootstrap pins **11076708**) ships a
unified binary `cmdline-tools/latest/bin/android` — version
**1.0.16406183** — alongside the classic `sdkmanager`/`avdmanager`. It is
explicitly agent-oriented (its own help: *"Tip: For the best experience
using Android CLI with agents, we strongly recommend running android init
to install the necessary skills and resources"*).

Command surface (evidence: `evidence/android_help.txt`,
`android_emu_help.txt`, `android_sdk_help.txt`):

| Command | Purpose |
|---|---|
| `sdk install/list/remove/update` | SDK package management |
| `emulator create/list/remove/start/stop` | AVD lifecycle; `start` **waits for full boot** |
| `install` | install APKs to a connected device/emulator |
| `create` / `describe` | project scaffolding + build-artifact metadata |
| `info` / `docs` / `init` / `completion` | environment info, docs search, agent setup |

## The full chain (fresh sandbox, all terminal-only, zero Android Studio)

Measured 2026-09-23 02:14–02:25 UTC, sandbox `iwlrzdpi24ucjkywngy80`
(8 cores / 7955 MB RAM / 25 GB disk). Chain executed **entirely
sandbox-side** (`setsid nohup`) — immune to lead-box process reaping and to
e2b driver-session hangs (both killed earlier driver-side runs; the
server-side pattern is the durable way to run long experiments).

| Phase | Time | Path used | Notes |
|---|---|---|---|
| provision (desktop template) | ~1 s | E2B API | warm template |
| java 17 + truststore | 12 s | known-good gotcha recipe | template java 11 → 17 |
| HTTPS gate | <1 s | curl HEAD 200 | hard gate before SDK |
| cmdline-tools discovery | (in 2 s) | `repository2-3.xml` parse | 25 builds; newest 16111833 |
| cmdline-tools install | 2 s | zip + unzip | `bin/android` present |
| **SDK install (7 pkgs, 4728 MB)** | **40 s** | **unified `android sdk install`** | exit 0 |
| exec bits | <1 s | gotcha 3 chmod | |
| AVD create | 4 s | `avdmanager` (fallback) | see §4 |
| **TCG cold boot → `sys.boot_completed=1`** | **378 s** | classic emulator binary, known-good flags | Android 11 |
| adb connect | ✓ | `adb devices` | |
| clone CamScan @ 6559fb6 | 2 s | git | v0.2 HEAD |
| **gradle assembleDebug** | **91 s, exit 0** | `./gradlew` (8.9 wrapper) | no IDE anywhere |
| APK install | 49 s | `adb install -r` | |
| APK launch + foreground | ✓ after ANR dismiss | `am start` | see §4 |

**Full chain: PASS.** Final resources: mem 3930/7955 MB used (emulator
running), disk 13.6/25 GB used (55%). Command reliability: 1 retry-free
server-side run after the two quoting/permission bugs below were fixed.

## Gotchas discovered (new, beyond the known-good 8)

1. **Shell quoting breaks the unified CLI's package args.** Variable-built
   `PKGLIST` with embedded literal quotes (`"$p"`) reaches argv as quote
   characters → `Invalid character '"' in path segment '"platform-tools"'`.
   The same literal-quoted list through bash *syntax* (not data) strips the
   quotes correctly. Fix: plain space-joined unquoted expansion.
2. **e2b `commands.run` defaults to a non-root user.** `/root`-based flows
   need `user="root"` explicitly (the driver-side experiment always passed
   it; a poll script that omitted it "succeeded" while doing nothing).
3. **Long unified-CLI invocations can hang the e2b command session** (the
   server-side process completes — all packages landed — but the driver's
   `commands.run` never returns; killed by its own 2400 s timeout). The
   sandbox-side `setsid nohup` script pattern eliminates the whole class.
4. **`android emulator create` is profile-based** — `android emulator create
   [<profile>] [--list-profiles]` — and rejects avdmanager-style flags
   (`-n/-k/-d/-c` → "Unrecognized option: -n"). Precise AVD control
   (explicit system image, sdcard size) still needs `avdmanager`.
5. **SystemUI ANR steals focus at first launch** (known substrate behavior,
   documented in REFERENCE-INSTALL-2026-09-22.md): the dismissal ladder
   (uiautomator dump + tap Wait) restores the app to
   `mCurrentFocus=org.payswap.camscan/.MainActivity` — verified with
   `mResumedActivity` + a post-dismissal screenshot
   (`evidence/launch_foreground.png`).

## Why complement, not replace (directive §3 decision)

- The unified CLI's `sdk install` is **faster and simpler** than
  sdkmanager (40 s, no license dance beyond the accepted ToS) — a strong
  candidate for bootstrap v2.
- But `emulator create` cannot yet express the AVD specifics the lab needs
  (system-image pinning for TCG pixel_4 + 2048M sdcard — the camera-app
  survival requirement), and `emulator start`'s TCG flag surface
  (`-accel off`, `-gpu swiftshader_indirect`, `-no-snapshot`, locale/timezone
  props) is unproven — the classic binary + `avdmanager` remain the
  known-good path for AVD + boot.
- The known-good `bootstrap.py` stays the default recipe untouched; the
  unified CLI is documented here as the verified complementary path
  (and the `android_cli` capability's provenance now cites this experiment).

## Evidence

Local: `/home/z/lead-staging/cli-first/evidence/` (chain_result.json,
chain.log, versions.txt, android_*.txt help captures, gradle_version.txt,
inventory.txt, adb_devices.txt, mem_final.txt, disk_final.txt,
launch_anr_behind.png, launch_foreground.png, cmdtools_bin.txt).

Exact versions: Android CLI 1.0.16406183 · cmdline-tools 16111833 ·
emulator 37.1.11.0 (build 15917651) · adb 1.0.41 · Gradle 8.9 (wrapper) ·
JDK 17.0.20.1 · Android 11 (API 30) guest · Ubuntu 22.04.5 host.

## Capability impact

`android_sdk` / `android_cli` / `gradle` (capability model v0.2) are now
**empirically verified end-to-end by this experiment** — the e2b capability
report's `verified_utc` advances to 2026-09-23T02:25:42Z with per-key
provenance in its notes.
