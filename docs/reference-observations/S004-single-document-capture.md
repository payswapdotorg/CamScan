# S004 — Single-document capture end-to-end

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S004-single-document-capture.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. prereq: camera permission granted (S003 path) + camera fixture `clean-a4` (REQUIRES a working camera-fixture mechanism — provider `camera_fixture: false` today)
2. launch → tap scan → observe live preview (virtualscene content) → capture (shutter)
3. observe post-capture stage: crop/edit screen, detected edges
4. accept/save → observe library with the new document
5. hash every produced file (`find /sdcard/Android/data/com.intsig.camscanner -type f` + ls of legacy dirs /sdcard/CamScanner, DCIM, Pictures)

## UI structure to capture

- camera screen: shutter, torch, mode selector
- post-capture: crop corners, confirm/rotate/enhance controls
- library card for the saved doc

## State changes to check

- document-added-to-library

## Outputs to record (files: names, formats, locations, hashes)

- pdf or jpg per capture (names/formats/locations) — `adb shell ls` + pull + sha256 (work-order requirement)

## Errors / edge cases to probe

- capture with no document in frame (empty scene)
- cancel from post-capture (discard path)
- capture offline

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

Storage is scoped on Android 11 for targetSdk 36 → outputs expected under `/sdcard/Android/data/com.intsig.camscanner/files/...` or via MediaStore (UNVERIFIED prediction; `requestLegacyExternalStorage=true` is ignored for fresh installs on Android 11).

## Proposed corrections to the provisional spec (for the lead)

- `output: pdf-created` is an assumption — modern CamScanner saves in-app pages (jpg) by default; PDF may be an explicit export (S016). Verify default save format empirically
- blocked until camera_fixture exists: `meta.requires.camera_fixture: true` cannot be satisfied by the current e2b capability report
