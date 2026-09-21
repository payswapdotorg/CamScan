# S009 — Enhancement modes

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S009-enhancement.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. capture with `low-light` fixture
2. at the edit stage cycle through enhancement modes (labels + order from ui dump: e.g. Original / Magic / Grayscale / B&W / Lighten)
3. screenshot per mode; record default-selected mode
4. accept with one mode → save → pull output; verify enhancement visible in the output file

## UI structure to capture

- mode selector labels + order
- preview update per mode

## State changes to check



## Outputs to record (files: names, formats, locations, hashes)

- enhanced-output-artifact (hash differs per mode)

## Errors / edge cases to probe

- mode + rotate combined
- mode switch on a multi-page doc (per-page or all-pages?)

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

—

## Proposed corrections to the provisional spec (for the lead)

- mode names are unknown until observed — capture verbatim labels for the target registry; assertions stay semantic
