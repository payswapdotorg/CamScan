# S005 — Automatic document edge detection

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S005-automatic-document-detection.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. camera permission granted + `clean-a4` fixture in view
2. observe the live preview overlay: edge rectangle updates as the 'document' appears in the virtualscene
3. screenshot pairs (with/without document in frame) at t and t+3 s — overlay difference
4. capture with document detected vs not — compare post-capture crops

## UI structure to capture

- live edge overlay (animated rectangle)
- shutter enabled state possibly gated on detection confidence

## State changes to check



## Outputs to record (files: names, formats, locations, hashes)

- none (stage-level behavior)

## Errors / edge cases to probe

- scene with no document-like content (does capture still fire?)
- partially-visible document (clipped edges)

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

Scan engine is native (arm64 split) — S005 depends on the ABI-resolution open question (REPORT §6.2).

## Proposed corrections to the provisional spec (for the lead)

- assertion `detection-overlay-shown-live` assumes continuous overlay — CamScanner historically shows a 'detect' state + hint text; record the actual affordance
- requires camera_fixture — currently unsatisfiable on e2b (capability report)
