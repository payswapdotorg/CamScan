# Camera-fixture mechanism validation — 2026-09-21 (rounds 1–12)

Empirical campaign on the E2B `desktop` template + QEMU TCG substrate,
driven entirely through the production `lab/providers/e2b` provider API
(CAMSCAN-008 acceptance-gate code path). 12 fresh sandboxes; evidence at
`r2:camscan-parity-evidence/substrate/2026-09-21-camera-fixture-probes/`
(41 objects, sha256-verified).

## What is PROVEN working

1. **Camera hardware + HAL**: `pm list features` shows the full camera
   feature set; `dumpsys media.camera` registers `Device 0`; the emulated
   back camera initializes as `environment` (virtualscene).
2. **Camera application lifecycle**: AOSP `com.android.camera2` runs to a
   live viewfinder. Required fixes (both root-caused, both landed in the
   provider):
   - **sdcard**: AVD must be created with `-c 2048M` — without a sdcard
     volume the camera app dies with `ExceptionInInitializerError` in
     `Storage$Singleton` (crash stack captured, round 7). Affects the
     whole storage-hungry app class (document scanners).
   - **audio**: launch must NOT pass `-no-audio` — the camera app's
     audio-services init (shutter player) crashes in `onCreate` with it
     (round 4 vs round 5 A/B: crash buffer EMPTY with audio on; qemu
     falls back to a null audio backend headlessly; boot 390–434 s —
     no TCG penalty).
3. **Live viewfinder rendering under TCG**: the virtualscene room renders
   in the camera preview (VLM-verified scene contents: wooden room,
   window, bookshelf, cat statue, TV). First-run onboarding is walkable
   (2 NEXT taps). `dumpsys media.camera` reports the client OPEN.
4. **Camera rotation**: `adb emu rotate` works — four headings captured,
   scene changes per heading.
5. **ANR management under TCG launch load**: system_server/SystemUI ANR
   dialogs are dismissible via on-device `uiautomator dump` + bounds-grep
   + tap on the dialog's Wait button (`dump-tap` proven; fixed-coordinate
   fallback + `CLOSE_SYSTEM_DIALOGS` broadcast as tiers 2/3).

## What is NOT working (open)

- **Poster injection is not visually confirmed.** Mechanisms that exist
  and are accepted without error:
  - launch flag `-virtualscene-poster poster1=<file>` (parsed: emulator
    log shows `arg2 <file>` + `Initialized camera with name: environment`)
  - console `virtualscene-image <wall|table> <file>` (returns `OK`)
  Neither replaces the default scene poster (TV checkerboard) in the
  rendered viewfinder; `adb emu rotate` through all four headings shows
  no injected poster.
- **environment.ini is not shipped** in the SDK (`find` empty; emulator
  log: `getEnvironmentConfig: No environment config is provided` → the
  default scene is compiled in).
- **Remaining route** (documented, untried): gRPC
  `VirtualSceneService/listPosters` + `setPoster` (service names
  binary-mined from qemu; needs grpc tooling in-sandbox).

## Mitigation for capture-flow parity

`camera_fixture: false` (honest) does NOT block document-flow parity:
fixtures can be delivered via **gallery import** (push fixture PNGs to
`/sdcard/DCIM` on the AVD; apps import documents through the gallery
picker). This covers detect/crop/enhance/save/export flows without
camera poster injection. Live-capture parity (what the viewfinder sees)
remains substrate-gated on the poster question.

## Gotchas banked (in addition to VALIDATION-2026-09-21-TCG.md)

- gotcha 9: E2B command wrapper self-match on `pgrep/pkill -f` — always
  use the `[m]` bracket trick (fixed during the CAMSCAN-008 gate).
- gotcha 10: `-no-audio` breaks audio-initializing apps under headless
  qemu — leave audio enabled.
- gotcha 11: create AVDs with a sdcard (`-c 2048M`) or storage-hungry
  apps crash in static initializers.
- gotcha 12: ANR dismissal requires an ON-DEVICE uiautomator dump +
  on-device grep (two-filesystems trap: host-side grep of a guest-side
  dump path silently never matches).
- gotcha 13: `adb shell kill <system-uid-pid>` from the shell user fails
  silently (EPERM) — do not rely on it for ANR workarounds.

## Provider changes landed from this campaign

commit d11c47c: `step_avd` creates a 2048M sdcard; launch without
`-no-audio`; `spec.camera_poster` plumbing to the launch flag.
commit fde8946: capability-report notes updated (working camera recipe +
poster status + gallery-import mitigation).
