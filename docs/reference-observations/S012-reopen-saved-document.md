# S012 — Reopen a saved document

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S012-reopen-saved-document.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. prereq: a saved document exists (S011 product)
2. launch → library → tap the document (coordinates from ui dump)
3. viewer state: pages rendered as saved; page navigation
4. screenshot + ui dump of the viewer; note available actions (OCR/export/share/delete)

## UI structure to capture

- library → viewer transition
- viewer controls (page list, edit, more)

## State changes to check

- document-open-in-viewer

## Outputs to record (files: names, formats, locations, hashes)

- none (viewer-level)

## Errors / edge cases to probe

- reopen after force-stop (state restore)
- reopen offline (cloud thumbnails may differ)

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

—

## Proposed corrections to the provisional spec (for the lead)

- minor: add an assertion for viewer action inventory (feeds S015–S018 target discovery)
