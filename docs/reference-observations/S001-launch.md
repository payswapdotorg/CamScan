# S001 — Cold launch of the application

> Scenario record for the CAMSCAN-004 reference capability discovery session
> (`camscan004-reference-20260921T224152Z`). Provisional spec:
> `lab/scenarios/S001-application-launch.yaml` (lead-owned; corrections proposed here, not applied).

**Status: NOT-OBSERVED — blocked by the reference-substrate credential** (no `E2B_API_KEY` in this worker sandbox; see [REPORT.md §3](REPORT.md#3-blocker--reference-substrate-credential-absent-from-this-worker-sandbox) for the verbatim attempt record). Static manifest facts below are OBSERVED-and-verified where marked.

## Observation workflow (to perform when unblocked)

1. precondition via `provider.reset(ResetSpec(wipe_data=True, reinstall_apk=<official bundle splits>))` — fresh install state
2. `am start`/`monkey -p com.intsig.camscanner -c android.intent.category.LAUNCHER 1` (launchable activity verified statically)
3. screenshots + ui_hierarchy at 0 / 15 / 30 s; `dumpsys window | grep mCurrentFocus` per capture
4. logcat (-d) for the first-run process: crash stack vs clean start
5. `ps -A | grep com.intsig.camscanner` — process alive check

## UI structure to capture

- first foreground activity (onboarding? login? main library?)
- root controls visible

## State changes to check

- app foregrounded
- first-run flags in app storage (inaccessible — observe via next-run behavior instead)

## Outputs to record (files: names, formats, locations, hashes)

- none expected at launch (verify: no files appear under /sdcard/Android/data/com.intsig.camscanner/)

## Errors / edge cases to probe

- launch after force-stop (warm start)
- launch offline (svc wifi/data disable) — splash/error states

## Static-informed expectations (UNVERIFIED unless marked OBSERVED)

Launchable activity `com.intsig.camscanner.mainmenu.mainactivity.MainActivity` (OBSERVED, manifest-verified). Application class `CsApplication`, `largeHeap=true`, `hardwareAccelerated=true`.

## Proposed corrections to the provisional spec (for the lead)

- assertion `cold-start-under-threshold` needs a concrete threshold (suggest ≤ 30 s to first frame under TCG — provider measured ~30–35 s per adb action; lead to pin the number)
- split the unknown: first-run foreground = onboarding (S002) — S001 should assert only process-up + no-crash + a visible root screen
