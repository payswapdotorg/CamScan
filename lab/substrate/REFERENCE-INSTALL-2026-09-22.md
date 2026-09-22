# Reference-app install/launch validation — 2026-09-22 (ABI probes 1–17)

Empirical campaign to install and launch the genuine CamScanner 7.25.5
XAPK (reference application for CAMSCAN-004/009) on the E2B `desktop`
template + QEMU TCG substrate with the `google_apis;android-30;x86_64`
system image @ 4 GB (ndk_translation for arm64-v8a splits). 17 probe
rounds, ~10 fresh sandboxes; result JSONs + screenshots in
`lab/substrate/abi-probe-results/` (this commit) — the decisive round 17
proves the full recipe end-to-end.

## The proven recipe (probe 17, 2026-09-22T10:43–11:40Z, zero crashes)

1. **Provision** the standard google_apis x86_64 @ 4096 MB spec (same
   image class as the provider default), TCG boot (~9 min), wait for
   package-service settle (`pm list packages` responds; +60 s settle).
2. **Dexopt filter** — `adb root` once, `setprop pm.dexopt.install
   verify`, `adb unroot`. This replaces the default dexopt filter with
   the cheap `verify` filter and eliminates the dexopt monitor storm
   that killed `system_server` during/after install (probe 9 vs 15:
   crash buffer EMPTY with it, system_server survives throughout).
3. **Install** with `adb install-multiple -r <HOST paths>` — base +
   `config.arm64_v8a.apk` + `config.en.apk`. Attempt 1 may hit a
   transient `Can't find service: package` flap right after the
   adbd root/unroot cycle; retry after 90 s. Probe 17: SUCCESS on
   attempt 2; `pm path` resolves instantly, all three APKs staged.
4. **Resolve launcher dynamically** — `cmd package resolve-activity
   --brief -a android.intent.action.MAIN -c android.intent.category.
   LAUNCHER -p com.intsig.camscanner` → MainActivity; never trust
   statically-derived component names.
5. **Patient launch** — `am start -W`, then wait; CamScanner's process
   comes up (ndk_translation interpreting the arm64 app under TCG is
   slow), the splash screen renders.
6. **Expect a SystemUI ANR dialog on first launch** (same TCG noise as
   the camera campaign, rounds R3/R4): dismiss via the proven on-device
   dump-tap ladder (uiautomator dump → grep the Wait button → tap).
   The app task stays visible/alive behind the dialog.

## What is DEAD (root-caused, do not retry these paths)

- **`pm install-create`/`install-write`/`install-commit` session
  installs** — every path variant: with/without `-p`, single- vs
  multi-execute, root/unroot, extract/verify filters. `pm
  install-write` streams the 162 MB base through **binder
  transactions** which die under TCG slowness: every write throws
  (Binder.execTransact frames), commit verdict is
  `INSTALL_FAILED_INVALID_APK: Session N. No packages staged in
  /data/app/vmdlN.tmp` (probe 16, verdict-marked). All earlier
  "Success" claims were the `install-create` string false-positive
  (probes 9/15; the `Success: created install session [N]` line).
  - uid-mismatch SecurityException (probes 10–14): a side-effect of
    splitting the session lifecycle across E2B executes; single-shell
    avoided the exception but still staged nothing.
- **`adb install-multiple` with device paths** — adb stats args on the
  HOST; device paths fail with `failed to stat`. Always pass host
  paths.

## Status of the reference application

- **Installed & launchable**: versionName 7.25.5.2609020000,
  primaryCpuAbi arm64-v8a (ndk_translation), base + arm64 + en splits
  staged; MainActivity launches with the task visible; splash renders;
  crash buffer EMPTY through the whole run.
- **Next step (CAMSCAN-009)**: reference-observation driver with the
  ANR-dismissal ladder + permission grants, then S001–S004 reference
  runs.
