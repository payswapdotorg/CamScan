# S002 — First-run onboarding flow

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S002-onboarding.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. reset to fresh install (wipe_data)
2. launch; capture every screen until a stable state (screenshot + ui dump per screen, back-driven where needed)
3. record the onboarding decision tree verbatim: pages, buttons (Skip/Next/Agree), privacy-policy gate, login prompt position
4. complete OR skip onboarding; then force-stop + relaunch — does onboarding reappear? (persistent flag)
5. record account/session requirements: does first-run DEMAND login? which paths proceed anonymous? (work-order question)

## UI structure to capture

- onboarding pager steps + skip control
- login/signup screen presence + dismissability
- privacy-consent dialog text

## State changes to check

- onboarding-completed-flag observable via non-reappearance after relaunch

## Outputs to record (files: names, formats, locations, hashes)

- none expected

## Errors / edge cases to probe

- back-press during onboarding (exit vs previous page)
- onboarding offline (privacy webview may fail offline)

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

No static signal for onboarding structure. Flutter module + sentry present — first-run may show feature carousel / crash-report consent (UNVERIFIED).

## Proposed corrections to the provisional spec (for the lead)

- assertion `onboarding-shown-on-first-run` may be FALSE for the official app (modern CamScanner versions land on the camera/library screen directly with EULA on first scan) — must be observed, not assumed
- add assertion: `login-not-required-for-core-scan-flow` (or its negation) — the work order explicitly asks which features gate on accounts
