# CAMSCAN-007 — lab CLI (Worker 1)

You are Worker 1 (implementation worker) for payswapdotorg/CamScan — a
parity lab reconciling an Android document scanner (CamScan) against CamScanner.
This task: build `tools/lab-cli` — the control-plane CLI (validate / run /
report) that turns scenario YAML into executed, evidenced runs.

## Setup
- Clone https://github.com/payswapdotorg/CamScan.git at base sha c12177e1e009ee14d6dd5f15fb69d84382b6e22d; branch `work/CAMSCAN-007`.
- Read FIRST (in this order):
  1. `lab/scenarios/SCENARIO-DSL.md` — step + assertion vocabulary, the three
     timeout layers, typed `meta.requires`, lifecycle semantics
  2. `tools/adb-bridge/README.md` — the verb layer you will drive
     (`AdbBridge`, `TargetRegistry`, `targets.yaml`, `LabProviderLike`
     protocol, `VerbResult`)
  3. `lab/providers/LABPROVIDER.md` — `EnvironmentSpec`, capability report,
     `collect_evidence`, lifecycle ops
  4. `tools/lab-cli/validate.py` — the lead's validator: its existing gates
     are frozen; your `validate` subcommand must run ALL of them
  5. `lab/evidence/EVIDENCE.md` — the run-directory / manifest layout your
     `run` subcommand emits
  6. `lab/parity-ledger/ledger.schema.json` — status vocabulary for the
     ledger-diff

## Task
1. `tools/lab-cli/` full CLI (Python 3; stdlib + PyYAML + jsonschema only;
   provider imports stay lazy) with subcommands (entry:
   `python3 tools/lab-cli/main.py …`):
   - `validate` — run every gate the lead's `validate.py` runs today (import
     it; never loosen or bypass). You MAY add new checks after those pass;
     each addition documented in the report. Output: per-check table; exit
     non-zero on any failure.
   - `run --scenario <id-or-filename> [--purpose implementation] [--dry-run]`
     — execute one scenario end-to-end:
       a. parse + gate the scenario: DSL schema conformance and
          `meta.requires` via `lab.providers.scheduler.validate_requirements`
          against the provider's capability report (e2b states
          `emulator_acceleration: none` — the substrate truth)
       b. provision the environment (`EnvironmentSpec(purpose=…)`) and
          resolve the semantic target registry for the app under test
       c. execute steps: map each DSL step name to adb-bridge verbs (the
          mapping table is yours to build — cover EVERY step name used by
          `lab/scenarios/*.yaml`); per-step budget = `meta.step_timeout_seconds`;
          record one `action_trace[]` entry per step (verb, target,
          started_ms, elapsed_ms, outcome, error)
       d. evaluate assertions into structured results (PASS / FAIL / ERROR /
          NOT_OBSERVED + the evidence refs that justify each)
       e. emit a run directory exactly per `lab/evidence/EVIDENCE.md` layout
          (captures, logs, action_trace, assertions, timestamps) so the
          evidence-bundler (CAMSCAN-006, in flight) can consume it unchanged
       f. teardown on ALL paths (success, failure, timeout): provider stop →
          destroy; never leak a paid environment
     `--dry-run` prints the execution plan (steps→verbs, resolved targets,
     timeouts, provisioning spec) and provisions NOTHING.
   - `report <run-dir> [<run-dir>…] [--ledger-diff]` — render a run report:
     JSON (sorted keys, trailing newline) + a human table: per-scenario
     verdict, per-assertion outcomes, artifact inventory with sha256,
     elapsed. `--ledger-diff` prints PROPOSED ledger status transitions as a
     unified diff — it must NEVER write `lab/parity-ledger/ledger.json`
     (ledger mutations are lead-reviewed).
2. Determinism: no wall-clock values inside content-addressed sections;
   timestamps only in the schema-designated fields.
3. Unit tests (pytest, no network, no real sandbox): a fake provider stub
   satisfying the `LabProviderLike` protocol + a fake adb layer with
   scripted shell responses; a mapping-table test asserting every step name
   in `lab/scenarios/*.yaml` resolves to a verb; a teardown test proving
   destroy runs on step failure and on timeout. Integration tests marked
   `@pytest.mark.integration`, auto-skipped without `E2B_API_KEY`.

## Rules
- Do not modify `lab/` contracts, `tools/adb-bridge/`, or any existing
  validator check; if a contract seems wrong, file it in the report instead.
- Environments are paid resources: default to `--dry-run` behavior in tests;
  the real path requires the explicit provider flag.
- No credentials in code, logs, tests, or reports.

## Verification (run these, paste outputs verbatim in the report)
```
python3 -m pytest tools/lab-cli -q
python3 tools/lab-cli/main.py validate
python3 tools/lab-cli/main.py run --scenario application-launch --dry-run
python3 tools/lab-cli/main.py report <your-test-run-dir>
```

## Final report — use EXACTLY this headline:
```
=== CAMSCAN-007 COMPLETION REPORT ===
task: lab CLI (validate/run/report)
environment: (toolchain versions)
what was implemented: …
verification: (verbatim command outputs)
evidence: (file list + sha256)
assumptions: …
open questions / handoffs: …
base sha: c12177e1e009ee14d6dd5f15fb69d84382b6e22d
```
Push branch `work/CAMSCAN-007` and report branch + HEAD sha. The tech lead re-runs
all gates at the integration station.
