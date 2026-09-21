# S018 — Share a document

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S018-sharing.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. prereq: saved document
2. viewer → share → capture the system share sheet (`com.android.internal.app.ChooserActivity` in window focus)
3. record offered targets (emulator has few apps; Drive/Files if present)
4. complete a share to an available target (e.g. Files save) if possible → pulled artifact hash

## UI structure to capture

- system chooser sheet
- app-side share origin menu (format choice pdf/jpg?)

## State changes to check



## Outputs to record (files: names, formats, locations, hashes)

- shared-artifact-hash-stable (same bytes as the S016/S017 export of the same doc)

## Errors / edge cases to probe

- share offline (targets may be network apps)
- share from library long-press vs viewer

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

—

## Proposed corrections to the provisional spec (for the lead)

- assertion is good; add: share format options (pdf vs jpg) recorded for parity with S016/S017
