# S016 — Export document as PDF

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S016-pdf-export.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. prereq: saved document
2. viewer → share/export → PDF (capture exact menu path + labels)
3. destination: save-to-device (capture the file-picker flow if any) vs share-sheet
4. pull the exported PDF: sha256, page count, page size; open check (pdfinfo/readable)
5. options recorded: page size (A4/letter/fit), margins, quality if offered

## UI structure to capture

- export menu structure
- options (page size/quality)
- destination picker

## State changes to check



## Outputs to record (files: names, formats, locations, hashes)

- pdf-created
- pdf-readable (pdfinfo page count + sizes match document)

## Errors / edge cases to probe

- export offline
- export multi-page doc (page order)
- export to a full /sdcard (write failure path — hard to stage; note only)

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

—

## Proposed corrections to the provisional spec (for the lead)

- assertions fine; add export-options record (page size default matters for parity of outputs)
