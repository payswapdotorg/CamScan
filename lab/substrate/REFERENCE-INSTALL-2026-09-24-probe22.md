# "Lost network stack" suicide-loop forensics + probe-22 — 2026-09-24

The 2026-09-24 night regime (00:00–01:30 UTC) burned 9 consecutive
60-min sandbox windows without a single CamScanner install (observe
ladder attempts 1–8 + live S001 window 1). Root-caused live from inside
an in-flight sandbox; mitigation = probe-22 (GMS quiesce for the
install window), encoded in `tools/lab-cli/reference_live.py`.

## The failure chain (evidence from sandbox i4gws11j…, live S001)

1. Post-boot GMS churn (gms.persistent, wellbeing, vending) under
   degraded TCG — boot slowed through the night: 470 s (09-21) →
   487 s → 585 s — starves `com.android.networkstack` of binder
   servicing time.
2. ActivityManager kills it for **bg ANR**:
   `am_kill : [0,5041,com.android.networkstack.process,-800, bg anr]`
   (seen at 01:17:30 and again at 01:19:56 — the loop re-arms after
   every framework restart).
3. `ConnectivityModuleConnector` treats the module-service death as a
   terrible failure and **crashes system_server by design** (Android 11):
   `*** FATAL EXCEPTION IN SYSTEM PROCESS: main`
   `java.lang.IllegalStateException: Lost network stack`
   at `ConnectivityModuleConnector.maybeCrashWithTerribleFailure` —
   followed by the DeadSystemException cascade (phone, gms.persistent,
   wellbeing, systemui, …).
4. The framework restart takes minutes under TCG, then re-enters churn —
   the loop is SELF-SUSTAINING and was already running BEFORE the first
   install attempt (first crash-buffer entry 01:04:28, first install
   attempt ~01:08).
5. Install attempts inside the loop: `adb install-multiple` dies fast
   with `cmd: Failure calling service package: Broken pipe (32)` (exit 1
   within seconds), or the background install never writes its EXIT_n
   marker (outcome-window timeout). The install's own dexopt/CPU load
   AMPLIFIES the networkstack starvation — each retry re-triggers the
   ANR → suicide cycle.
6. 2026-09-22's probe-17 won its lottery because the install landed on
   attempt 2 (~90 s) before the first networkstack ANR — not because the
   substrate was immune.

## probe-22 mitigation (driver change, lead-authored)

- After the dexopt filter and BEFORE the XAPK acquire/install ladder:
  `pm disable-user --user 0` on `com.google.android.gms`,
  `com.google.android.apps.wellbeing`, `com.android.vending`
  (best-effort per package; sandbox death still aborts cleanly).
- After the install's registry verification: `pm enable` each package,
  then a +60 s settle so the re-enabled GMS processes spin up BEFORE
  the app launch (their binder churn lands outside the fragile window).
- Install-time quiesce only — the OBSERVED app behavior still runs with
  GMS present (observation fidelity unchanged; consistent with the
  honest capability record).
- Ladder visibility fix (run-001 lesson applied): failed attempts now
  emit the adb stderr tail from `/root/install.out` (the 2026-09-24
  forensics needed exactly this and it was missing).

## Honest limits

- If the TCG degradation alone (without GMS churn) still bg-ANRs the
  networkstack, probe-22 cannot save the window: the correct response
  is pausing the live campaign until the E2B host regime improves, not
  more retries.
- Boot-time degradation across consecutive sandboxes (470 → 487 → 585 s)
  is the leading indicator to watch.

## probe-23 (2026-09-24, follow-up): the commit outlives the adb stream

With probe-22 active (GMS quiesced, zero "Lost network stack" suicides,
package service healthy throughout), the first live window still showed
`outcome-window timeout` verdicts: under degraded TCG the
package-manager COMMIT lands minutes before the adb client stream
returns — observed live: `pm list packages` showed com.intsig.camscanner
at 01:54 while attempt 2's adb process was still streaming and its
6-minute outcome window expired at ~01:56 with no EXIT_n marker.

Ladder fix (probe-23, commit on main): at the outcome-window edge,
BEFORE declaring a timeout, poll `pm path com.intsig.camscanner` — a
committed package IS success (the vmdl staging dir never shows in
`pm list`; registry presence means the commit completed); the lingering
adb stream is then killed harmlessly and the caller's probe-15 registry
verification double-confirms.
