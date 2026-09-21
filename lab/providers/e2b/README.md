# E2B LabProvider (CAMSCAN-008)

Real implementation of the `LabProvider` contract (`../LABPROVIDER.md`) over
E2B sandboxes running the Android emulator under **QEMU TCG software
emulation** (`-accel off`, capability `emulator_acceleration: none`).

## Substrate

| | |
|---|---|
| template | E2B public `desktop` (8 vCPU / ~8 GB RAM / 25 GB disk) |
| Android | 11 (API 30), x86_64, `pixel_4`, 1080x2280, swiftshader_indirect |
| acceleration | none (TCG) — there is no `/dev/kvm` in E2B Firecracker microVMs |
| measured | cold boot → `sys.boot_completed=1` in 410 s; ~30–35 s per adb action |

Empirical gate: `../../substrate/VALIDATION-2026-09-21-TCG.md`.

## Layout

| file | role |
|---|---|
| `provider.py` | `E2BProvider` — all 13 contract operations + health report |
| `bootstrap.py` | the baked TCG recipe (Java 17, truststore hard gate, SDK, exec-bit fix, AVD) with the 7 substrate gotchas encoded |
| `acceptance.py` | CAMSCAN-008 acceptance gate: full lifecycle + capability probes |
| `probe_apk.sh` | deterministic probe APK builder (runs inside the sandbox; `hasCode=false` launchable activity — the real app is CAMSCAN-001's deliverable) |
| `capability-report.json` | committed typed capability report (CI-validated; updated by acceptance probes) |

## Timeout model (three explicit layers — never conflated)

1. **Provider budgets** (this config): TCG boot `boot_budget_s=2400` (measured
   410 s), install 600 s, capture 300 s, default command 300 s. Sandbox
   lifetime renewed opportunistically before every operation (per-call cap
   1 h) and on a fixed cadence during boot polling.
2. **Scenario `meta.timeout_seconds`**: wall-clock for the scenario EXECUTION
   phase (env-ready → evidence complete). Enforced by the scenario runner;
   provisioning/boot never counts against it.
3. **Scenario `meta.step_timeout_seconds`**: per interactive step, passed by
   the runner as the `timeout` argument of `interact`/`execute` (default 120 s).

## Long operations

- boot: dedicated poll loop (20 s cadence), fail-fast on emulator death and
  fatal `emulator.log` strings, renewal every 4 min;
- sandbox lifetime: renewed before each op + during polls — a 40-min boot
  storm cannot let the sandbox expire;
- every `execute`/`interact`/`capture` takes an explicit timeout.

## Environment handle

`provision()` returns an `EnvironmentHandle` with `env_id`, `sandbox_id`,
`avd_name`, `adb_serial`, `provider_slug`, capability report, per-step
timings, and a sandbox-side JSONL action trace. Bootstrap is marker-file
idempotent per sandbox (stop/start reuses the installed SDK + AVD).

## Running the acceptance gate

```bash
pip install -r lab/providers/e2b/requirements.txt
export E2B_API_KEY=...        # env only — never in git/evidence/logs
python3 lab/providers/e2b/acceptance.py
```

Emits `.acceptance/acceptance_result.json`, `health_report.json`, and the
pulled evidence bundle. The gate exercises every contract operation with no
manual repair steps; capability probes (`recording`, `snapshot`,
`camera_fixture`) record empirical truth into the result.

## Credentials

`E2B_API_KEY` from the environment only. It never appears in git, source
config, scenario files, reports, evidence, screenshots, logs, or manifests.
