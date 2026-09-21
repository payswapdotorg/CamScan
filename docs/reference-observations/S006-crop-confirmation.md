# S006 — Crop confirmation stage after capture

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S006-crop-confirmation.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. capture with `clean-a4` (S004 path up to post-capture)
2. screenshot + ui dump of the crop stage: corner handles, magnifier lobes, confirm button
3. confirm WITHOUT adjustment → next stage
4. record which controls the stage offers (adjust / rotate / enhance / more)

## UI structure to capture

- crop UI: 4 corner handles + edges
- confirm control label
- secondary controls

## State changes to check



## Outputs to record (files: names, formats, locations, hashes)

- the stage's intermediate output naming (page added to edit buffer)

## Errors / edge cases to probe

- confirm immediately vs after 10 s idle
- back from crop stage (discard capture?)

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

—

## Proposed corrections to the provisional spec (for the lead)

- assertions are UI-level and fine; add the actual control labels once observed (target registry for CAMSCAN-002 needs them)
