# Evidence model (v0.1)

Every scenario execution produces an evidence bundle under `runs/<run-id>/`:

```
runs/<run-id>/
  scenario.yaml                 # verbatim copy of the scenario executed
  reference/                    # run against CamScanner
    manifest.json               # subject: "reference" (the bundle contract)
    screenshots/  *.png         # numbered per step
    recordings/   *.mp4 | *.webm
    ui/           *.xml         # UI hierarchy dumps (per step)
    logs/         logcat.txt app-events.json
    outputs/      *.pdf *.jpg *.txt (+ sha256 sidecars)
  implementation/               # run against CamScan (same layout)
    manifest.json               # subject: "implementation"
  reconciliation/
    diff.json                   # Worker 3's structured comparison
    verdict.json                # PASS | PARTIAL | FAIL | BLOCKED | NOT OBSERVED
```

### Run-dir layout decision (lead, 2026-09-23 — resolves the v0 drawing ambiguity)

The v0 tree drew ONE `manifest.json` at the run root alongside BOTH subject
subtrees, while the manifest schema carries `subject: reference |
implementation` (one manifest = one subject). Resolution:

- There is NO run-root manifest. Each subject subtree (`reference/`,
  `implementation/`) is a COMPLETE single-subject bundle: its own
  `manifest.json` at the subtree root, own artifacts, own sidecars.
- `scenario.yaml` lives at the RUN root (one copy, shared by both subjects);
  per-subject manifests reference the scenario by id.
- `tools/evidence-cli bundle <subject-dir>` operates per subject subtree
  (`runs/<run-id>/reference`, `runs/<run-id>/implementation`) — the
  single-subject model applies per invocation.
- `tools/parity-cli compare <run-id>` reads BOTH subject manifests from the
  run dir and writes `reconciliation/`.
- R2 keys remain `runs/<run-id>/<subject>/<path>` (unchanged).

## manifest.json contract

```json
{
  "run_id": "20260921T103000Z-S004-<suffix>",
  "scenario": "single-document-capture",
  "subject": "reference | implementation",
  "provider": {"slug": "…", "capabilities": {…}, "environment_id": "…"},
  "application": {"package": "…", "version_name": "…", "version_code": …,
                  "installer_sha256": "…"},
  "device": {"model": "…", "android_version": "…", "screen": "WxH@dpi",
             "locale": "…", "timezone": "…", "permission_baseline": {…}},
  "fixtures": [{"id": "clean-a4", "sha256": "…"}],
  "artifacts": [{"path": "screenshots/01-launch.png", "sha256": "…", "bytes": …}],
  "action_trace": [{"t_ms": 0, "action": "launch", "target": "…", "result": "…"}],
  "started_at": "ISO-8601", "finished_at": "ISO-8601"
}
```

## Rules

- Every artifact is SHA-256 hashed in the manifest; large artifacts are ALSO stored
  in R2 (`camscan-parity-evidence`, key = `runs/<run-id>/<subject>/<path>`) and the
  manifest records the R2 key.
- Durable truth (specs, manifests, hashes, findings, verdicts) lives in Git; bulk
  bytes live in R2.
- Reference and implementation runs must use the same fixture ids + hashes, same
  device profile parameters, same locale/timezone and permission baseline — the
  manifest proves it.
- Evidence never contains credentials (see SECURITY.md).
- `NOT OBSERVED` is a legitimate verdict when the reference app was not exercisable
  for a capability; it is never silently converted into PASS.
