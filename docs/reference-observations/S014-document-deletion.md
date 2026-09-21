# S014 — Delete a document from library

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S014-document-deletion.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. prereq: saved document (S011)
2. library → long-press or overflow menu → delete (capture exact affordance)
3. confirmation dialog (capture text) → confirm
4. library after: document gone; `ls` of storage after: orphaned files? (cleanup vs retained)

## UI structure to capture

- delete affordance (long-press menu vs overflow vs swipe)
- confirm dialog

## State changes to check

- document-removed-from-library

## Outputs to record (files: names, formats, locations, hashes)

- storage delta after delete (files removed?)

## Errors / edge cases to probe

- cancel at confirm dialog
- delete the currently-open document from its viewer
- delete with pending cloud sync

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

—

## Proposed corrections to the provisional spec (for the lead)

- add storage-level assertion: deletion also removes or marks the backing files (observable via ls delta)
