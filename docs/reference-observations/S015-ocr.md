# S015 — OCR text extraction

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S015-ocr.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. prereq: saved document with legible text (clean-a4 with text)
2. viewer → run OCR (record the control label — often 'Recognize'/'OCR')
3. capture progress + result (text layer / recognized-text panel)
4. pull any OCR output files (txt/pdf with text layer); sample recognized strings vs fixture ground truth
5. offline OCR run — cloud dependency check (error vs degraded local recognition)

## UI structure to capture

- OCR entry control + progress UI
- recognized-text presentation

## State changes to check



## Outputs to record (files: names, formats, locations, hashes)

- ocr-text-present
- ocr-text-recognizable (semantic match vs fixture ground truth)

## Errors / edge cases to probe

- OCR offline (cloud OCR failure path)
- OCR on handwriting fixture (future)
- OCR on rotated page

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

UNVERIFIED hint: app allows cleartext traffic + heavy network stack — cloud-assisted OCR likely; a login-gated OCR quota is plausible (work-order question: which features gate on account).

## Proposed corrections to the provisional spec (for the lead)

- assertion `ocr-runs-on-demand` should note possible cloud dependency + account gate; record whether recognition works anonymously & offline
- ground-truth comparison needs CAMSCAN-003 fixture text content committed
