# S017 — Export page as JPG

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S017-jpg-export.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. prereq: saved document
2. viewer → export → JPG/image (record labels; single page vs all pages)
3. pull exported jpg: sha256, dimensions, EXIF presence (app may strip or add geotags — location permission interplay)
4. viewable check (PIL open + dimensions)

## UI structure to capture

- export path for image format
- per-page vs whole-doc selection

## State changes to check



## Outputs to record (files: names, formats, locations, hashes)

- jpg-created
- jpg-readable

## Errors / edge cases to probe

- jpg export offline
- EXIF/geotag presence (ties to ACCESS_FINE_LOCATION — privacy behavior worth recording)

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

Location permissions present in manifest — EXIF geotagging behavior is a real parity-relevant observable (UNVERIFIED).

## Proposed corrections to the provisional spec (for the lead)

- add optional assertion: `jpg-exif-geotag-{present,absent}` — record the app's actual behavior
