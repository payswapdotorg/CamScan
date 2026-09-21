# S011 — Save document to library

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S011-save.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. capture `clean-a4` → accept → save (record the save flow: filename prompt? tags? folder choice?)
2. library list state before/after (screenshots)
3. `adb shell ls -laR` of the app's external storage before/after — exact files, names, formats
4. pull + sha256 every new file; identify default output format
5. relaunch — document still listed (persistence)

## UI structure to capture

- save controls + any name/tag prompt
- library entry (thumbnail, page count, date)

## State changes to check

- document-added-to-library

## Outputs to record (files: names, formats, locations, hashes)

- pdf-created (VERIFY — may be jpg in-app page by default)
- exact output locations + names

## Errors / edge cases to probe

- save offline (cloud-sync features may queue)
- duplicate names — auto-rename behavior

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

OBSERVED: targetSdk 36 → scoped storage on Android 11; `WRITE_EXTERNAL_STORAGE` ineffective. UNVERIFIED prediction: outputs under `/sdcard/Android/data/com.intsig.camscanner/files/` (or MediaStore collections for shared items), NOT legacy `/sdcard/CamScanner`.

## Proposed corrections to the provisional spec (for the lead)

- replace output-location assumption with: 'output files enumerated under the app-scoped external dir (exact subpaths recorded at first run)'
- default-format question (pdf vs in-app jpg) must be answered by observation before locking the assertion
