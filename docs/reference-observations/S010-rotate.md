# S010 — Page rotation

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S010-rotate.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. capture with `clean-a4` → edit stage
2. tap rotate (record control label + direction) — screenshot per tap (90° steps)
3. save → pull PDF → verify page rotation metadata (pdfinfo 'Page rot' or render check)

## UI structure to capture

- rotate control (label, 90°-step behavior, long-press variants?)

## State changes to check



## Outputs to record (files: names, formats, locations, hashes)

- pdf-page-rotated (page rotation attribute or rendered orientation)

## Errors / edge cases to probe

- rotate 4× (full circle) — returns to original?
- rotate + crop order interplay

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

—

## Proposed corrections to the provisional spec (for the lead)

- assertion `rotation-persists-in-output` should specify the check: PDF `/Rotate` attribute vs rendered pixels — choose the semantic check
