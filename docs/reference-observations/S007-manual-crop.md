# S007 — Manual crop adjustment

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S007-manual-crop.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. capture with `skewed-document` fixture
2. drag each corner handle in turn (provider `interact` swipe gestures on handle coordinates from the ui dump)
3. observe preview re-render after each drag (screenshot per step)
4. confirm → save → pull output; verify the crop reflects the dragged geometry (visual + output hash)

## UI structure to capture

- handle hit-targets (coordinates from ui_hierarchy)
- preview update latency

## State changes to check



## Outputs to record (files: names, formats, locations, hashes)

- cropped-output-reflects-adjustment (compare against unadjusted capture of same fixture)

## Errors / edge cases to probe

- degenerate crop (handles collapsed to a line)
- crop then cancel — original preserved?

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

—

## Proposed corrections to the provisional spec (for the lead)

- step `adjust-crop` needs concrete gesture parameters once handle coordinates are known — capture them at first run
