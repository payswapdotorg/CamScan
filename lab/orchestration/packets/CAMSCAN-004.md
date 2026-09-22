# CAMSCAN-004 — Reference capability discovery (Worker 2)

You are Worker 2 (reference/oracle worker) for the CamScan parity lab. This task:
observe the OFFICIAL CamScanner application's actual behavior in an isolated
reference environment and record it as the parity oracle. S001–S018 are
PROVISIONAL specs — your observations are the truth that corrects them.

## Setup
- Clone https://github.com/payswapdotorg/CamScan.git at base sha 3b3551a5052852b598cde7dac57d8492e6e835af; branch `work/CAMSCAN-004`.
- Your reference environment: the `e2b` LabProvider (READ `lab/providers/e2b/README.md`
  and `lab/providers/LABPROVIDER.md` first). Provision with
  `EnvironmentSpec(purpose="reference")` so the AVD is named `camscan-reference`
  (isolated from the implementation environment — never share an emulator with W1).
  Equivalent device profile: Android 11 x86_64, pixel_4, 1080x2280, locale en-US,
  timezone UTC (provider defaults).
- E2B credential: `E2B_API_KEY` is supplied in your sandbox environment notes below.
  ENV-ONLY: never in git, source, reports, evidence, screenshots, or logs —
  any leak means the whole lab credential rotates.

## Install the reference app
- Obtain the official CamScanner APK (latest stable, e.g. from the vendor's
  official site or a reputable APK mirror; record the exact source URL + file
  sha256). Do NOT reverse engineer, decompile, or copy proprietary source/assets —
  observable behavior only. Install via the provider (`adb install`).
- Record: application version, package name, Android version, device profile,
  locale, timezone, permissions requested (install-time + runtime), account/
  session requirements (does first-run demand login? which features gate on it?),
  network behavior (domains contacted at first run, if observable via logcat).

## Observe and record (docs/reference-observations/)
For each of the S001–S018 scenario areas (launch, onboarding, camera permission,
single capture, auto-detection, crop confirm, manual crop, perspective, enhancement,
rotate, save, reopen, multi-page, delete, OCR, pdf export, jpg export, share):
- workflow (exact UI flow you performed)
- UI structure (key screens/controls; uiautomator dumps where useful)
- state changes (library contents, settings, permissions)
- outputs (files produced: names, formats, locations — `adb shell ls` + pull hashes)
- errors/edge cases (denied permissions, cancel paths, offline behavior)
- capture evidence via the provider (`capture` screenshot/ui_hierarchy/logcat)
  into an evidence bundle (`collect_evidence`) — hash-verified

Deliverable `docs/capability-map.md`: the mission §6 feature inventory with
statuses OBSERVED / NOT-OBSERVED per capability, corrected S001–S018 behavior
notes. Deliverable `docs/reference-observations/REPORT.md`: the environment +
session requirements record above. Do NOT modify scenario YAMLs or the ledger
(lead-owned); propose corrections in your report instead.

## Verification (paste outputs verbatim)
- provider lifecycle commands + outputs (provision → start → install → observe)
- evidence bundle manifest (files + sha256)
- `python3 tools/lab-cli/validate.py` still green

## Final report — use EXACTLY this headline:
```
=== CAMSCAN-004 COMPLETION REPORT ===
task: reference capability discovery
environment: …
what was implemented/observed: …
verification: …
evidence: artifact list + sha256 (+ R2 keys if uploaded)
assumptions: …
open questions / handoffs: …
base sha: 3b3551a5052852b598cde7dac57d8492e6e835af
```
Push branch `work/CAMSCAN-004`.
