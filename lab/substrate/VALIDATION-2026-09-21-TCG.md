# E2B TCG Android emulator validation — 2026-09-21 (revised hard gate)

**Purpose.** The original gate ([VALIDATION-2026-09-21.md](VALIDATION-2026-09-21.md))
proved accelerated emulation is impossible in E2B (no nested `/dev/kvm`) and — by
assumption, not measurement — dismissed QEMU TCG software emulation as "unacceptably
slow." The operator has since directed: **E2B-only, no GCP, no external provider**
(handoff credentials: E2B API key, repo-scoped GitHub token, scoped R2 — nothing else).
That assumption therefore had to be tested empirically, because this lab is
**scripted and batch-driven, not human-interactive**: slowness costs throughput, not
correctness.

## Method

Live probe on the **public E2B `desktop` template** (not probed before — the earlier
`e2b-dev/desktop-small` template ID 404s): create sandbox → Java 17 + truststore →
Android cmdline-tools + SDK packages → tiny AVD → headless emulator boot with
`-accel off` (pure TCG, no KVM) → `sys.boot_completed` poll → adb measurements →
screencap evidence. Probe script kept outside the repo (lead-staging
`e2b_tcg_probe.py`; credentials from env only).

## Environment

| Property | Value |
|---|---|
| Template | `desktop` (public, E2B Desktop/Xfce) |
| Resources | 8 vCPU, 7955 MB RAM, 25 GB disk (20 GB free) |
| Emulator | 37.1.11, `-accel off -no-window -gpu swiftshader_indirect -memory 2048 -cores 4` |
| Image | `system-images;android-30;default;x86_64` (Android 11, x86_64/x86) |
| AVD | `pixel_4` device profile, 1080x2280 |

## Results — **TCG_EMULATOR_VIABLE**

| Stage | Time |
|---|---|
| Sandbox create | 0.7 s |
| Java 17 + truststore fix | 38.0 s |
| SDK install (cmdline-tools + platform-tools + emulator + system image, ~1.6 GB) | 27.6 s |
| AVD create | 2.0 s |
| **Boot → `sys.boot_completed=1`** | **410 s (~6.8 min)** |
| adb `input keyevent` round trip | ~34.8 s |
| adb `screencap` round trip | ~30.1 s |

Evidence: `tcg_boot_screencap.png` (42,803 bytes — booted Android 11 display),
`tcg_probe_result.json`, `tcg_emulator.log` (uploaded to R2 under
`substrate/2026-09-21-tcg-gate/`).

## Verdict

**PASS for a scripted parity lab.** A fresh sandbox reaches a booted, adb-responsive
Android 11 in **~8 minutes wall clock end-to-end** with zero acceleration. Per-action
latency of ~30 s under TCG means a 40-step scenario takes ~20 minutes — slow but
deterministic and fully automatable. This satisfies the lab's requirements:

- scenario runs are batch/scheduled, not human-interactive;
- timeouts are explicit and generous per scenario (`meta.timeout`);
- throughput can be recovered later by (a) baking the SDK into a custom E2B template
  (the broken `flauz-parity` template slot can be rebuilt — see below), (b) AVD
  snapshots to skip full boots, (c) parallel sandboxes (the account can run several).

Per [LABPROVIDER.md](../providers/LABPROVIDER.md): provider `e2b` reports
`emulator_acceleration: none`; scenario verdicts record the acceleration they ran
under so a future Flauz provider (`emulator_acceleration: kvm`) can re-run the suite
faster without redesign.

## Gotchas encountered (recipe for CAMSCAN-008)

These cost several probe iterations — the `e2b` provider implementation MUST bake
them in:

1. **Java 17 required, template ships Java 11** — `apt-get install
   openjdk-17-jre-headless`; `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` for all
   sdkmanager/avdmanager calls.
2. **Broken JDK truststore** — apt headless JDKs leave `/etc/ssl/certs/java/cacerts`
   as a ~32-byte stub → `SSLException: trustAnchors parameter must be non-empty` →
   sdkmanager "Failed to find package". Fix: `rm -f /etc/ssl/certs/java/cacerts &&
   update-ca-certificates -f` (fallback: bulk `keytool -importcert` from
   `/etc/ssl/certs/ca-certificates.crt`, storepass `changeit`).
3. **Exec bits missing after sdkmanager unpack** — `chmod a+x` on
   `emulator/emulator`, `emulator/crashpad_handler` (sic) and
   `emulator/qemu/linux-x86_64/*` before launching.
4. **The emulator binary is `<sdk>/emulator/emulator`** — `<sdk>/emulator` is a
   *directory*; `cd <sdk> && ./emulator` fails with a misleading "Permission denied"
   (EACCES on directory exec). Launch from inside `<sdk>/emulator/`.
5. **e2b SDK command semantics** — `commands.run` raises `CommandExitException` on
   non-zero exit (the exception carries exit_code/stdout/stderr); run setup as
   `user="root"`; sandbox lifetime caps at **1 h per `set_timeout` call** (extend
   repeatedly for long boots).
6. **Boot poll** — poll `adb shell getprop sys.boot_completed`; expect `emulator-5554`
   in `device` state within ~1 min, `boot_completed=1` in ~7 min; budget ≥ 20 min.
7. **pgrep checks** — the launcher execs straight into
   `qemu-system-x86_64-headless` (comm ≠ `emulator`); count with
   `pgrep -c -f 'qemu-system.*-avd <name>'` and note `pgrep -c` prints the count even
   when it exits non-zero.

## Open items for the lab (not blockers)

- The account's custom `flauz-parity` template (built from
  `tetevis-project/flauz-parity` via E2B's GitHub integration) is in `buildStatus:
  error` with a zero build id and no logs. If fixed/rebuilt with the SDK baked in,
  sandbox bring-up drops from ~8 min to seconds. Worth a work order once the lab runs.
- GCP is **retired** by operator directive — do not implement `gcp-nested-kvm`.
