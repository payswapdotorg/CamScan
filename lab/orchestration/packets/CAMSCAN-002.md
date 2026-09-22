# CAMSCAN-002 — adb-bridge (Worker 1)

You are Worker 1 (implementation worker) for payswapdotorg/CamScan — a parity
lab reconciling an Android document scanner (CamScan) against CamScanner.
This task: build `tools/adb-bridge` — the provider-neutral device-driving
layer the scenario runner will sit on.

## Setup
- Clone https://github.com/payswapdotorg/CamScan.git at base sha a4d6f03fbbfe3d64d3bde1217dce35d2ebf77529; branch `work/CAMSCAN-002`.
- Read FIRST: `lab/providers/LABPROVIDER.md` (the 13 verbs + typed specs),
  `lab/providers/types.py` (Interaction, CaptureKind, ResetSpec),
  `lab/providers/e2b/provider.py` (the reference implementation — your
  bridge wraps a LabProvider, never raw adb).

## Task
1. `tools/adb-bridge/` (Python 3) — a thin, typed facade over any
   LabProvider:
   - `AdbBridge(provider: LabProvider, env_id: EnvironmentId)` with
     semantic verbs (each maps to provider calls, adds timeouts + traces):
     `launch(app, activity)`, `force_stop(app)`, `grant(app, permission)`,
     `revoke(app, permission)`, `tap(x, y)`, `tap_semantic(target_id)`,
     `swipe(x1, y1, x2, y2, ms)`, `type_text(text)`, `key(code)`,
     `back()`, `home()`, `wait_idle(timeout)`, `screenshot() -> bytes`,
     `ui_dump() -> str`, `logcat(filter, tail)`, `install(apk_path)`,
     `push(local, remote)`, `pull(remote, local)`, `wake()`.
   - Semantic target registry: `tools/adb-bridge/targets.yaml` mapping
     semantic ids (e.g. `shutter_button`, `next_button`, `gallery_tile_1`,
     `permission_allow`) to coordinates OR ui-selector strings; resolution
     order: ui-selector match in the latest ui dump, else coordinates.
     The registry is data, not code — scenario files reference semantic
     ids only.
   - `wait_for(predicate, timeout, poll)` + ready-made predicates:
     `activity_resumed(name)`, `text_visible(text)`, `package_foreground(pkg)`.
   - Every verb returns a structured result (ok, detail, duration_ms) and
     appends to the provider's action trace when available.
2. Determinism: no sleeps without polls; every wait is bounded; TCG-scale
   timeouts (an adb shell round-trip can take 30-60s under software
   emulation — default verb timeout 180s, overridable).
3. Unit tests (pytest) with a FakeProvider (in-memory Interaction/Capture
   recording) covering every verb + target resolution order + timeout
   paths.

## Rules
- The bridge NEVER shells out to adb directly — provider calls only
  (provider-neutral: it must work against any future LabProvider).
- No credentials, no network at runtime.

## Verification (run these, paste outputs verbatim in the report)
```
python3 -m pytest tools/adb-bridge -q
python3 -c "from tools.adb_bridge.bridge import AdbBridge; print('import ok')"
```

## Final report — use EXACTLY this headline:
```
=== CAMSCAN-002 COMPLETION REPORT ===
task: adb-bridge
environment: (toolchain versions)
what was implemented: …
verification: (verbatim command outputs)
evidence: (file list + sha256)
assumptions: …
open questions / handoffs: …
base sha: a4d6f03fbbfe3d64d3bde1217dce35d2ebf77529
```
Push branch `work/CAMSCAN-002` and report branch + HEAD sha. The tech lead re-runs
all gates at the integration station.
