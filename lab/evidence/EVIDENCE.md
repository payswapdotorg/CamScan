# Evidence model (v0)

Every scenario execution produces an evidence bundle under `runs/<run-id>/`:

```
runs/<run-id>/
  scenario.yaml                 # verbatim copy of the scenario executed
  manifest.json                 # the bundle contract (below)
  reference/                    # run against CamScanner
    screenshots/  *.png         # numbered per step
    recordings/   *.mp4 | *.webm
    ui/           *.xml         # UI hierarchy dumps (per step)
    logs/         logcat.txt app-events.json
    outputs/      *.pdf *.jpg *.txt (+ sha256 sidecars)
  implementation/               # run against CamScan (same layout)
  reconciliation/
    diff.json                   # Worker 3's structured comparison
    verdict.json                # PASS | PARTIAL | FAIL | BLOCKED | NOT OBSERVED
```

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
