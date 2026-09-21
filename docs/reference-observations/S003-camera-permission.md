# S003 — Camera permission request and grant

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S003-camera-permission.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. fresh install (camera permission baseline: `pm list permissions -g` / `dumpsys package` requested vs granted)
2. launch; navigate to the scan/camera entry WITHOUT pre-granting
3. capture the system permission dialog (screenshot + ui dump: `com.android.packageinstaller` / `com.google.android.permissioncontroller`)
4. tap Allow; verify `dumpsys package com.intsig.camscanner` granted=true for CAMERA
5. repeat fresh-install → Deny path: capture the app's denied-state UI (retry banner? settings deep-link?)
6. repeat fresh-install → Allow-only-once (Android 11 one-time grant) if offered

## UI structure to capture

- system dialog text (While using the app / Only this time / Deny)
- app's rationale UI before/after denial

## State changes to check

- CAMERA granted=true/false/one-time
- possible bundled LOCATION prompt — record whether it appears at camera time

## Outputs to record (files: names, formats, locations, hashes)

- none

## Errors / edge cases to probe

- deny → retry loop
- deny + don't-ask-again → settings deep-link
- permission revoked post-grant (pm revoke) then camera re-entry

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

OBSERVED (manifest): `CAMERA` is requested and is a runtime dangerous permission on Android 11 — a system dialog WILL appear before first camera use. ALSO runtime: `READ_EXTERNAL_STORAGE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — expect possible additional prompts (EXIF geotagging). `WRITE_EXTERNAL_STORAGE` is auto-granted-but-ineffective (targetSdk 36).

## Proposed corrections to the provisional spec (for the lead)

- steps assume a single camera prompt; add optional `grant-permission: location` step or an assertion capturing a possible bundled location prompt
- assertion `permission-requested-before-camera-use` is correct-by-manifest; the dialog is the OS's, not the app's UI — keep the UI assertion on the app's pre-permission rationale screen
