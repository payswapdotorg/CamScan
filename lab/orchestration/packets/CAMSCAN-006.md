# CAMSCAN-006 — evidence CLI (Worker 3)

You are Worker 3 (reconciliation/tooling worker) for payswapdotorg/CamScan — a
parity lab reconciling an Android document scanner (CamScan) against CamScanner.
This task: build `tools/evidence-cli` — the evidence-bundle + R2 pipeline.

## Setup
- Clone https://github.com/payswapdotorg/CamScan.git at base sha 3f4a2be886a3c1deb7e8d618d91945130d8062d8; branch `work/CAMSCAN-006`.
- Read FIRST: `lab/evidence/EVIDENCE.md` (bundle + manifest contract),
  `lab/providers/LABPROVIDER.md` (EvidenceBundle output of collect_evidence),
  `SECURITY.md` (credential rules), `lab/fixtures/fixture.schema.json`.

## Task
1. `tools/evidence-cli/` (Python 3, stdlib + boto3 only) with subcommands:
   - `bundle <run-dir>` — validate/normalize a run directory into the
     EVIDENCE.md layout; compute sha256 for every artifact; write/repair
     `manifest.json` (schema exactly per EVIDENCE.md: run_id, scenario,
     subject, provider, application, device, fixtures, artifacts[],
     action_trace[], timestamps). Reject bundles whose fixture ids/hashes
     are not in `lab/fixtures/manifest.json`.
   - `upload <run-dir>` — upload every artifact to R2
     (`runs/<run-id>/<subject>/<path>` key layout) + the manifest; verify
     each object via head (size match) after upload; write
     `r2-manifest.json` with keys + sha256 + bytes + verified flags.
     Credentials ONLY from env (R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY,
     R2_ENDPOINT, R2_BUCKET). Credentials must never appear in any
     manifest, log line, or committed file.
   - `verify <manifest.json>` — re-check: every artifact hash matches its
     sidecar, every R2 key in the manifest exists with matching size (when
     R2 creds present), manifest schema-valid. Exit non-zero on any
     mismatch; print a per-artifact table.
2. Deterministic output: JSON files sorted keys, trailing newline, no
   timestamps inside content-addressed sections.
3. Unit tests (pytest) with a fake fs fixture tree + moto-mocked S3 (or
   skip-if-no-creds integration test marked `@pytest.mark.integration`).

## Rules
- No credentials in code, manifests, logs, or test fixtures (leak-scan
  your diff for key prefixes before pushing).
- Do not touch `lab/` contracts; if a contract seems wrong, file it in the
  report instead of changing it.

## Verification (run these, paste outputs verbatim in the report)
```
python3 -m pytest tools/evidence-cli -q
python3 tools/evidence-cli/main.py bundle <your-test-run-dir>
python3 tools/evidence-cli/main.py verify <your-test-run-dir>/manifest.json
```

## Final report — use EXACTLY this headline:
```
=== CAMSCAN-006 COMPLETION REPORT ===
task: evidence CLI
environment: (toolchain versions)
what was implemented: …
verification: (verbatim command outputs)
evidence: (file list + sha256)
assumptions: …
open questions / handoffs: …
base sha: 3f4a2be886a3c1deb7e8d618d91945130d8062d8
```
Push branch `work/CAMSCAN-006` and report branch + HEAD sha. The tech lead re-runs
all gates at the integration station.
