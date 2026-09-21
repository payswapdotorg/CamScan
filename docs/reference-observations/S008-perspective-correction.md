# S008 — Perspective correction after capture

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S008-perspective-correction.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. capture with `skewed-document` (off-angle view)
2. at crop stage accept the detected quad (not manual) → save
3. reopen the document → inspect rectification: page geometry vs original skew
4. export PDF (S016 path) → verify rectangular page (pdfinfo/PyPDF page box) — semantic output assertion

## UI structure to capture

- post-correction preview

## State changes to check

- document-added-to-library

## Outputs to record (files: names, formats, locations, hashes)

- pdf-pages-rectangular (page aspect/box check, not pixel diff)

## Errors / edge cases to probe

- extreme skew (near-90°) — does correction fail/degenerate?

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

Perspective correction is native-engine work (arm64 split) — ABI open question applies.

## Proposed corrections to the provisional spec (for the lead)

- assertion `rectified-page-geometry` needs a measurable tolerance (e.g., corner angles 90°±3°) — pixel-perfect not required per LAB.md
