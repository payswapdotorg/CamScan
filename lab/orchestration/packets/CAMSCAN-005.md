# CAMSCAN-005 — parity CLI (Worker 3)

You are Worker 3 (reconciliation worker) for payswapdotorg/CamScan — a parity
lab reconciling an Android document scanner (CamScan) against CamScanner.
This task: build `tools/parity-cli` — the manifest-driven comparison engine
that turns two evidence bundles into a verdict.

## Setup
- Clone https://github.com/payswapdotorg/CamScan.git at base sha fde8946b5ccfba5cb21736a830066801116925a5; branch `work/CAMSCAN-005`.
- Read FIRST: `lab/evidence/EVIDENCE.md` (manifest contract),
  `lab/reconciliation/GAP-FORMAT.md` (gap yaml), `lab/parity-ledger/`
  (ledger.json + ledger.schema.json — verdict vocabulary + statuses),
  `lab/scenarios/SCENARIO-DSL.md` (what a scenario asserts).

## Task
1. `tools/parity-cli/` (Python 3, stdlib + jinja2 optional) with:
   - `compare <run-id>` — locate `runs/<run-id>/reference/manifest.json` +
     `runs/<run-id>/implementation/manifest.json`; produce
     `runs/<run-id>/reconciliation/diff.json` + `verdict.json`.
   - Comparison dimensions (each a diff entry with severity):
     a. environment parity: device profile, locale, timezone, android
        version, screen, permission baseline (from both manifests).
     b. fixture parity: same fixture ids + sha256 both sides.
     c. artifact parity: per-step screenshots/ui dumps exist on both sides;
        missing-step detection from action_trace.
     d. action outcome parity: action_trace step-by-step — target reached,
        launch ok, permission granted vs denied, same step count (allowing
        documented reference-app-specific steps to be masked via an
        ignore-list file you design: `tools/parity-cli/masks/`).
     e. output parity: outputs/ files by type + count + sha256 (equality is
        NOT required — record divergence, don't fail on it).
   2. Verdict computation (deterministic, from diff.json):
      - PASS: all critical+high dimensions equal.
      - PARTIAL: medium/low divergences only.
      - FAIL: any critical/high divergence.
      - BLOCKED: implementation bundle missing/incomplete for external
        reasons (recorded, never counted as PASS).
      - NOT_OBSERVED: reference bundle lacks the capability observation.
      - verdict.json: {run_id, scenario, verdict, summary, counts by
        severity, generated_utc}.
   3. `gap <run-id> [--from-diff]` — emit gap yamls per GAP-FORMAT.md for
      open divergences (id, feature, reference/implementation behavior +
      evidence paths, difference, severity, required_change, verification).
   4. `ledger-update <run-id>` — update `lab/parity-ledger/ledger.json`
      scenario status per verdict (schema-valid; ids/titles mirror
      scenario files).
   5. Unit tests (pytest) with synthetic manifest pairs covering every
      verdict path (PASS/PARTIAL/FAIL/BLOCKED/NOT_OBSERVED) — determinism
      test: two runs produce byte-identical diff.json.

## Rules
- The comparison engine NEVER trusts app self-reports; evidence-only.
- NOT_OBSERVED is never silently converted into PASS.
- Do not modify lab/ contracts; file contract concerns in the report.

## Verification (run these, paste outputs verbatim in the report)
```
python3 -m pytest tools/parity-cli -q
python3 tools/parity-cli/main.py compare <synthetic-run-id>   (your fixture pair)
python3 tools/parity-cli/main.py ledger-update <synthetic-run-id>
```

## Final report — use EXACTLY this headline:
```
=== CAMSCAN-005 COMPLETION REPORT ===
task: parity CLI
environment: (toolchain versions)
what was implemented: …
verification: (verbatim command outputs)
evidence: (file list + sha256)
assumptions: …
open questions / handoffs: …
base sha: fde8946b5ccfba5cb21736a830066801116925a5
```
Push branch `work/CAMSCAN-005` and report branch + HEAD sha. The tech lead re-runs
all gates at the integration station.
