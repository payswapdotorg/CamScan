# S013 — Multi-page document scan

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S013-multi-page-scan.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. permission granted + `multi-page` fixture sequence
2. scan → capture → capture → capture (record the add-page control between captures)
3. done → save → library entry page count == captures
4. pull output: page count of produced artifact (pdfinfo) == 3

## UI structure to capture

- add-page control (label + position)
- page counter during capture
- page list at review

## State changes to check

- document-added-to-library

## Outputs to record (files: names, formats, locations, hashes)

- pdf-page-count-equals-captures

## Errors / edge cases to probe

- zero additional pages (single-page doc via multi-page flow)
- delete a page mid-flow
- camera permission revoked mid-sequence

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

—

## Proposed corrections to the provisional spec (for the lead)

- requires camera_fixture (currently unsatisfiable); page-count check should use the produced artifact, not the UI badge alone
