"""The live reference-env driver — the official CamScanner on the e2b TCG
substrate (CAMSCAN-009).

Drives the REFERENCE subject (env=reference) of a paired run: the genuine
CamScanner (com.intsig.camscanner, black-box — observable behavior only,
no reverse engineering) installed from its split XAPK bundle and exercised
through the adb-bridge verb layer. This is exactly the environment the
CAMSCAN-004 observation campaign probed (tools/reference-observe/
observe.py) and the probe-17 install recipe validated
(lab/substrate/REFERENCE-INSTALL-2026-09-22.md); the observe.py lessons
are the spec.

Like :mod:`tools.lab_cli.e2b_live` this module is **lab-exercised, never
pytest-exercised** (live-provider network calls are out of pytest scope);
the in-tool tests pin the driver *contract* — the install-recipe call
order, the retry-ladder state machine, the registry flip, the teardown
invariant — through an injected scripted transport, never the e2b SDK.

The proven recipe (every constant provenance-pinned below — probe-17 /
run-003 / run-005 lessons / observe.py lines; never a bare number):

1. PROVISION — the reference environment's OWN profile (never shared
   with the implementation env): google_apis;android-30;x86_64 image
   @ 4096 MB — the image class that carries libndk_translation for
   CamScanner's arm64-v8a-only splits (run-002 lesson 2026-09-22: the
   default image dies INSTALL_FAILED_NO_MATCHING_ABIS) — AVD
   camscan-reference, TCG boot with the provider-owned budget, then a
   package-service settle (pm list packages answers; +60 s).
2. DEXOPT FILTER — ``setprop pm.dexopt.install verify`` BEFORE any
   install (adb root → setprop → unroot): the cheap filter eliminates
   the dexopt monitor storm that killed system_server in probe 9.
   The EVIDENCE.md device block falls back through the provider report
   to the capability-record-documented pixel_4 profile (CAMSCAN-010B:
   the report's identity probes can come back empty for this env —
   never an empty field into the bundle).
3. INSTALL — the XAPK is acquired in-sandbox from a presigned URL when
   CAMSCAN_APK_URL is set (the proven delivery: pushing the 221 MB
   bundle through the e2b files API stalls in an httpx retry loop —
   observe.py's 2026-09-23 lesson), sha256-verified in-sandbox, splits
   unzipped; or extracted + pushed from a local --apk (the fallback).
   Then ``adb install-multiple -r`` with SANDBOX-side paths (adb stats
   its args on the host side — device paths fail; pm session installs
   are DEAD — probe 16) through the GMS-churn retry ladder: background
   install + EXIT_n marker (probe-21b pattern), a 6-min outcome window
   per attempt (never a 20-min hang — run-005 lesson a),
   package-service re-settle probes between failed attempts (run-003
   lesson), up to 8 bounded attempts, sandbox death → clean abort
   (destroy + raise — run-005 lesson b: never hammer a corpse), and an
   explicit ``pm path`` registry verification after Success (probe-15:
   an adb Success does NOT prove the package landed). Right after that
   verification the INSTALL-TIME package facts are read ONCE through
   the 010B bounded-blindness machinery and stashed on the handle
   (CAMSCAN-010C, ``native.install_time_facts``): the facts are stable
   from registry confirmation, and the 2026-09-25 campaign runs died
   re-reading them at execute()-end (sandbox age exactly 3600 s — the
   E2B Hobby total-lifetime cap, CAMSCAN-010D; instant
   ``.set_timeout``-advice rejections — the death zone), so the read
   lives where the system is freshest: BEFORE the
   GMS restore whose post-restore flap is the 010B blindness class.
   The early read is best-effort — provision never fails on it.
4. LAUNCH — the probe-26..31 machinery (CAMSCAN-010A, ported from
   observe.py's proven first-run section — the probes-25..34 launch
   lessons that never reached this driver; the 2026-09-25 S001 run
   died at STEP 01 on the probe-24 single-shot monkey form while the
   driver already held the resolved component): component launch
   via ``am start -W -n <resolved component>`` (the handle's
   provision-time resolution — ``cmd package resolve-activity``,
   never statically-derived components — re-resolved only when
   empty; the exact form ``bridge.launch(app, activity=component)``
   drives) under the probe-27 retry ladder (background am start +
   EXIT_n marker, bounded outcome windows, activity-service gate +
   gap-cadence settle, 4 attempts; "brought to the front" counts as
   up), monkey ONLY as the no-component fallback; a probe-29 dex2oat
   quiescence gate before the ladder and timestamped (HH:MM:SS)
   ladder diagnostics; probe-30: am start -W's own ``Status: ok`` +
   ``Activity: <pkg>/`` output is AUTHORITATIVE launch evidence
   (am_confirmed — a transport-blind ps read never vetoes it); the
   patient process poll (probe-24 budget, probe-25 death forensics,
   probe-31-capped at ~2 min when am already confirmed) decides the
   step verdict, and its diagnostics reach the evidence layer
   (problems/reason + the run-metadata action trace) — and since
   CAMSCAN-010D BOTH process-wait loops (this poll and the
   post-launch wait) are governed by the E2B Hobby total-lifetime
   budget: each cuts early when the remaining sandbox lifetime drops
   below LAUNCH_PROC_BUDGET_RESERVE_S so the evidence phases get
   their minutes inside the 3600 s cap. Then the
   SystemUI-ANR dismissal ladder after a SUCCESSFUL launch
   (uiautomator dump → Wait-button bounds → center tap; the proven
   (540, 1244) fallback).
5. EXECUTE — scenario steps through adb-bridge verbs, per-step
   screenshot + ui-dump captures, final logcat, package facts
   STASH-FIRST (CAMSCAN-010C: the install-time stash on the handle is
   the ground truth execute() consumes — provenance named on the
   driver's diag channel; the CAMSCAN-010B blindness-tolerant late
   read — the probe-33 bounded retry with timestamped blind-read
   diagnostics — runs only for facts the stash could not land, seeded
   with the stashed ones; a blind read is never an absence
   observation, and a both-blind run fails the run honestly with the
   combined install-time + late diagnostics instead of shipping empty
   placeholders into the bundle; CAMSCAN-010D: a read carrying the
   sandbox-death signature — the exact ``.set_timeout``-advice
   rejection an expired Hobby sandbox serves — is NOT a blind
   transient: the retries abort at once and the honest-fail reason
   names the expiry with age context), the EVIDENCE.md single-subject
   metadata.
6. TEARDOWN — best-effort stop + destroy on EVERY path; never raises
   (the runner owns the invariant; provision cleans up its own partial
   state before raising — a paid sandbox is never leaked).

Two deliberate deviations from the implementation driver:

- no ``provider.reset`` — its reinstall path is a single-APK
  ``adb install -r`` which is DEAD for split bundles (probe 16: the
  binder-transaction staging dies under TCG — install-multiple owns
  the flow) and its wipe relaunch is redundant on a fresh sandbox (a
  new sandbox + new AVD is fresh-install state by construction;
  persistent: false);
- permission baselines are granted by the driver with the full
  ``pm grant <pkg> <perm>`` form (the correct one for app runtime
  permissions).

CAMSCAN-010D — the E2B Hobby total-lifetime budget governor (3600 s
hard cap): the account is Hobby-tier; the SDK docstring
(e2b/sandbox_sync/main.py, set_timeout) caps a sandbox's TOTAL
lifetime at 1 hour (3_600 seconds) for Hobby users, and the
20260925T110418Z-S001-live postmortem matched it exactly (sandbox
created ~11:04:24, envd UNAVAILABLE at 12:04:24 = age exactly
3600 s). set_timeout renewal cannot move that deadline under Hobby,
so the driver governs its own waits against E2B_TOTAL_LIFETIME_CAP_S
via ``_Native.sandbox_t0`` (the birth mark captured in provision()
immediately BEFORE provider.provision): the launch-step process poll
and the post-launch process wait cut early at
LAUNCH_PROC_BUDGET_RESERVE_S (the ANR ladder still runs — it is
required for subsequent steps), and a package-facts read with the
sandbox-death signature aborts the retries at once (never hammer a
corpse) and fails the run honestly naming the expiry. A SIGINT
campaign stop can no longer leak the paid sandbox: provision()'s
ladder-region cleanup catches BaseException too.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile
from collections.abc import Callable
from pathlib import Path
from typing import Any

from tools.evidence_cli.schema import SCREEN_RE
from tools.lab_cli.drivers import (
    DriverHandle,
    ExecutionRequest,
    ProvisionRequest,
    SubjectRunResult,
)
from tools.lab_cli.evidence import provider_capabilities
from tools.lab_cli.scenarios import LabCliError, Scenario
from tools.lab_cli.steps import APP, StepPlan

#: Environment variable holding the E2B credential (provider contract).
API_KEY_ENV = "E2B_API_KEY"

#: Presigned-URL delivery for the XAPK (observe.py's CAMSCAN_APK_URL
#: pattern: the e2b files-API push of the 221 MB bundle stalls in an
#: httpx retry loop — the sandbox curl from object storage is the
#: proven path, sha256-verified in-sandbox before install).
APK_URL_ENV = "CAMSCAN_APK_URL"

#: The pinned reference application (CAMSCAN-004 black-box corpus):
#: official CamScanner 7.25.5.2609020000 XAPK, archived at
#: r2:camscan-parity-evidence/reference/camscanner/ — bytes + sha256
#: recorded in-repo (docs/reference-observations/session-manifest.json
#: and static-apk-analysis.md). The expected hash for URL-mode delivery
#: when no local --apk supplies its own.
RECORDED_XAPK_SHA256 = ("7ef8e46525a1210bbaa332d5f5d560ce"
                        "30d768b4375ea92265d2e292d25dba65")
RECORDED_XAPK_BYTES = 221499725

#: The reference environment's OWN profile (CAMSCAN-004: equivalent
#: device profile, separate AVD — never shared with the implementation
#: env). google_apis image class: carries libndk_translation for the
#: app's arm64-v8a-only splits (run-002 lesson, probe-17 proof);
#: 4096 MB is the proven memory size (probe-17 step 1).
REFERENCE_SYSTEM_IMAGE = "system-images;android-30;google_apis;x86_64"
REFERENCE_MEMORY_MB = 4096

#: The reference app package (targets.yaml app scope / observe.py
#: APK_PACKAGE_DEFAULT).
REFERENCE_PACKAGE = "com.intsig.camscanner"

#: Camera permission map for preconditions → pm grant.
_PRECONDITION_PERMISSIONS = {
    "camera-permission-granted": "android.permission.CAMERA",
}

# ------------------------------------------------- install-recipe constants
# CAMSCAN-009 contract: every retry/wait constant cites its provenance —
# probe-17 (lab/substrate/REFERENCE-INSTALL-2026-09-22.md), the run-003 /
# run-005 lessons, or the observe.py line it mirrors. Never a bare number.

#: probe-17 step 1: after boot_completed, wait for the package service to
#: answer, "then +60 s settle" before the dexopt/install sequence.
BOOT_SETTLE_S = 60

#: run-003 lesson (observe.py L447): the package service can die
#: MID-STREAM and recover minutes later — between failed install attempts,
#: probe `pm list packages` until it answers again; "up to ~120 s service
#: re-settle" = 8 probes x 15 s.
SERVICE_SETTLE_MAX_PROBES = 8
SERVICE_SETTLE_POLL_S = 15                      # observe.py L456 (sleep 15)

#: probe-22 (2026-09-24 night-regime forensics): the GMS churn sources
#: disabled BEFORE the install ladder and re-enabled after the registry
#: verification. Post-boot GMS churn under degraded TCG bg-ANRs
#: com.android.networkstack; ConnectivityModuleConnector then commits
#: system_server suicide ("Lost network stack") and the minutes-long
#: framework restart leaves the package service dead — the install
#: ladder burns all 8 attempts inside that loop. Install-time quiesce
#: only: the OBSERVED app behavior still runs with GMS present.
GMS_QUIESCE_PKGS = (
    "com.google.android.gms",
    "com.google.android.apps.wellbeing",
    "com.android.vending",
)
GMS_QUIESCE_TIMEOUT_S = 90

#: observe.py L459: after the service re-settles, sleep 30 before the next
#: attempt (probe 17 succeeded on attempt 2 after ~90 s total).
RETRY_BACKOFF_S = 30

#: observe.py L367 + its comment: the google_apis image NEVER settles
#: (GMS bg-ANR churn bursts every 5-10 min and kills the package service
#: mid-stream at random); 8 bounded attempts span ~30 min of windows —
#: "luck is real and bounded retries harvest it" (probes 17, 21b).
INSTALL_MAX_ATTEMPTS = 8

#: run-005 lesson (a) (observe.py L397): a hung install stream eats the
#: whole 20-min command timeout with no renewal opportunity — run each
#: attempt in the background with an EXIT_n marker and give it a 6-min
#: OUTCOME window; a hang then costs one window, not 20 min.
OUTCOME_WINDOW_S = 360

#: observe.py L399: poll the outcome file every 20 s — light polls renew
#: the sandbox lifetime (a poll is an execute; renewals ride on it).
OUTCOME_POLL_S = 20

#: observe.py L388: the background-launcher execute itself gets 60 s.
INSTALL_LAUNCH_TIMEOUT_S = 60

#: observe.py L376 / L401 / L410: diagnostics / outcome polls / full fetch.
DIAG_TIMEOUT_S = 120
POLL_TIMEOUT_S = 60

#: observe.py L277: the dexopt root/setprop/unroot block gets 300 s.
DEXOPT_TIMEOUT_S = 300

#: observe.py L300: the in-sandbox curl + sha256 + unzip block gets 900 s.
CURL_TIMEOUT_S = 900

#: observe.py L485: `pm path` registry check after Success (probe-15
#: lesson: an adb Success does NOT prove the package landed).
REGISTRY_TIMEOUT_S = 60

#: observe.py L507: dynamic launcher resolution budget.
LAUNCHER_RESOLVE_TIMEOUT_S = 180

#: observe.py L512: dumpsys package facts budget.
PKG_FACTS_TIMEOUT_S = 180

#: observe.py L612 (svc/pm commands): 120 s per pm command.
GRANT_TIMEOUT_S = 120

#: probe-24 (2026-09-24): the night TCG regime needs ~6-7 min for the
#: cold-start process to appear (observe.py L529 originally 12 x 10 s —
#: the app process took >120 s while the run was already failing).
PROCESS_WAIT_ROUNDS = 40
PROCESS_WAIT_S = 10

# ------------------------------------------- launch-step constants (010A)
# CAMSCAN-010A: the probe-26..31 launch machinery ported from
# tools/reference-observe/observe.py (its first-run section — the
# probes-25..34 launch lessons that never reached this driver; the
# 2026-09-25 S001 live run died at STEP 01 on the probe-24 single-shot
# monkey form while the driver already held the resolved component).
# Same doctrine as the install-recipe block above: every constant
# cites its probe/lesson provenance, never a bare number.

#: probe-29 (observe.py dex2oat gate): the install's background dexopt
#: (verify filter on a 162 MB base + 59 MB arm64 split under ~130 MB
#: free) steals the exact CPU the cold start needs — that run's am
#: start was ACCEPTED yet the process never spawned through a 90 s
#: WaitTime + a 6.7-min patient poll while logcat showed system_server
#: slow-dispatch storms. Gate the ladder on dex2oat quiescence,
#: bounded: 24 polls x 10 s ≈ 4 min, then proceed anyway (fresh
#: windows land installs fast and the verifier is usually already
#: done; a read that errors or comes back blind also proceeds).
DEX2OAT_GATE_MAX_POLLS = 24
DEX2OAT_GATE_POLL_S = 10

#: probe-27 (observe.py `for attempt in range(4)`): 4 bounded attempts —
#: the launch binder call dies EXACTLY like the install one (transient
#: broken-pipe bursts on the regime's 5-10 min inter-burst cadence) and
#: the install ladder PROVED luck is real and bounded retries harvest
#: it (probe 17 attempt 2; probe-29's run: attempts 2-3 PM-blind,
#: attempt 4 resolved).
LAUNCH_MAX_ATTEMPTS = 4

#: probe-27 (observe.py "3-min outcome window"): a blocked am start
#: costs one bounded window, not an 8-min hang (run-005 lesson (a)
#: applied to the launch binder; the install ladder's 6-min
#: OUTCOME_WINDOW_S is the install-stream analogue).
LAUNCH_OUTCOME_WINDOW_S = 180

#: observe.py background-launcher budget: the marker-launching execute
#: itself gets 60 s (the install ladder's INSTALL_LAUNCH_TIMEOUT_S
#: analogue — probe-28: its in-band timeout text is a transient, never
#: a verdict).
LAUNCH_LAUNCHER_TIMEOUT_S = 60

#: probe-29 (observe.py "gap-cadence settle"): probe-27's original
#: 30 s settle clustered all 4 attempts inside ONE TCG burst (the
#: regime's inter-burst gaps run 5-10 min); 120 s lets the ladder
#: straddle a full burst cycle — 4 attempts now span ~15-20 min. (The
#: install ladder's RETRY_BACKOFF_S is the analogue.)
LAUNCH_SETTLE_S = 120

#: probe-27 (observe.py activity-service gate): `am get-current-user`
#: must answer a digit again before the next attempt — 8 probes x 15 s
#: (the install ladder's package-service re-settle cadence,
#: SERVICE_SETTLE_MAX_PROBES x SERVICE_SETTLE_POLL_S, applied to the
#: activity service; only a genuinely dead activity service aborts the
#: ladder — fall through to the patient poll).
LAUNCH_GATE_MAX_PROBES = 8
LAUNCH_GATE_POLL_S = 15

#: observe.py patient-poll ps budget: each poll read gets 120 s (the
#: process poll is the verdict under strain — a short read budget
#: wastes poll rounds on transport slowness, not absence).
LAUNCH_PS_TIMEOUT_S = 120

#: probe-31 (observe.py `poll_cap = 12 if am_confirmed else 40`): when
#: am already confirmed the launch ('Status: ok' + 'Activity:
#: <pkg>/...') the ps poll is a NICE-TO-HAVE (a process-line detail),
#: not the gate — cap it at 12 polls (~2 min at the probe-24 cadence)
#: and go straight to the evidence phases while the transport still
#: has minutes left (run 20260924T174918Z died with the app up and
#: zero captures). The unconfirmed cap is PROCESS_WAIT_ROUNDS.
LAUNCH_PROC_POLL_CAP_CONFIRMED = 12

#: observe.py L552 / L567: ANR-dismissal ladder — up to 3 dump→tap
#: rounds, 8 s settle between rounds.
ANR_MAX_ROUNDS = 3
ANR_ROUND_SETTLE_S = 8

#: observe.py L570: the camera-campaign-proven Wait-button coordinates
#: (last resort when the dump-tap ladder cannot dismiss the dialog).
ANR_FALLBACK_TAP = (540, 1244)

#: CAMSCAN-010F — the dump-first onboarding discovery ladder's budget
#: (the _anr_ladder shape ported to onboarding): up to 12 bounded
#: rounds — permission dialogs AND onboarding pages both consume
#: rounds (the live S002 chain shows both classes in sequence) — with
#: an 8 s settle between rounds (TCG-paced, the same cadence as the
#: ANR ladder's ANR_ROUND_SETTLE_S).
ONB_MAX_ROUNDS = 12
ONB_ROUND_SETTLE_S = 8

#: CAMSCAN-010F — the permission-dialog affirmatives, checked FIRST
#: every round: the registered global selectors' exact texts
#: (tools/adb-bridge/targets.yaml: permission_allow text="While using
#: the app", permission_allow_this_time text="Only this time"). Exact
#: matches — the deny button ("Don't allow") never matches either set.
#: Permission grants are idempotent-safe.
_ONB_PERMISSION_TEXTS: tuple[str, ...] = (
    "While using the app",
    "Only this time",
)

#: CAMSCAN-010F — onboarding affirmative discovery: case-insensitive
#: substring matches over CLICKABLE nodes' text= and content-desc=,
#: first match in document order. Onboarding-progress labels only —
#: monetization traps live in the exclusion set below.
_ONB_AFFIRMATIVE_SUBSTRINGS: tuple[str, ...] = (
    "next", "continue", "get started", "start using", "start", "skip",
    "done", "finish", "agree", "accept", "allow", "ok", "got it",
    "let's go",
)

#: CAMSCAN-010F — the hard exclusion set: NEVER tapped even when it is
#: all that is clickable (a present-but-refused control is NOT
#: completion — the loop keeps looking and fails honestly when the
#: rounds exhaust; a clean screen completes).
_ONB_EXCLUSION_SUBSTRINGS: tuple[str, ...] = (
    "purchase", "buy", "upgrade", "premium", "share", "rate",
    "subscribe",
)

#: run-005 lesson (b) (observe.py L361-365): these phrases mean the
#: SANDBOX died — not a retryable install failure. Abort at once; a
#: fresh run gets a fresh 60-min window (the E2B hard cap).
#: CAMSCAN-010D: the "sandbox timeout" entry is the exact signature
#: the expired Hobby sandbox serves on every read past age 3600 s
#: (20260925T110418Z postmortem — see E2B_TOTAL_LIFETIME_CAP_S).
SANDBOX_DEATH_MARKERS: tuple[str, ...] = (
    "sandbox was not found",
    "sandbox timeout",
    "ended before the stream completed",
)

#: The Wait-button matcher for the ANR ladder (observe.py L557 — the dump
#: text is read through the bridge, so the two-filesystems trap cannot
#: bite: the XML rides in VerbResult.text).
_ANR_WAIT_RE = re.compile(
    r'text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')

#: CAMSCAN-010F — the onboarding discovery ladder's bounds-center
#: idiom (the _anr_ladder regex, generalized): uiautomator's
#: "[left,top][right,bottom]" bounds attribute.
_ONB_BOUNDS_RE = re.compile(
    r"\[\s*(\d+)\s*,\s*(\d+)\s*\]\s*\[\s*(\d+)\s*,\s*(\d+)\s*\]")

# ------------------------------------ run-metadata constants (CAMSCAN-010B)
# CAMSCAN-010B: the 2026-09-25 attempt-3 live run
# (20260925T091135Z-S001-live) succeeded through the ENTIRE chain for
# the first time (install first-try via the probe-23 edge-registry, GMS
# restore, launcher resolved, LAUNCH AM-CONFIRMED via the CAMSCAN-010A
# ladder, SystemUI-ANR dismissed, steps executed, sandbox cleanly
# destroyed) and then died at the evidence stage on two run-metadata
# gaps: device.screen shipped EMPTY (the provider report carries no
# resolution for this env — deterministic, EVERY run) and
# application.version_name shipped EMPTY (one blind dumpsys read — the
# probe-33 post-restore transport-flap class, solved in observe.py
# commit eeeeb0b). Same doctrine as the blocks above: every constant
# cites its capability-record / probe / commit provenance — never a
# bare number.

#: The capability-record-documented pixel_4 geometry
#: (lab/providers/e2b-reference/capability-report.json
#: notes.device_profile: "pixel_4 / Android 11 (API 30) / 1080x2280 /
#: en-US / UTC — the equivalent-profile requirement (CAMSCAN-004)").
#: The AVD is created from that pixel_4 device definition
#: (lab/providers/e2b/bootstrap.py step_avd: ``avdmanager create avd
#: -d pixel_4`` via EnvironmentSpec.device_profile), whose SDK device
#: profile pairs the 1080x2280 panel with 440 dpi — the density the
#: provider report's own probe reads (lab/providers/e2b/provider.py:
#: ``getprop ro.sf.lcd_density``). Identical to the pair-comparable
#: recording driver's pinned value (tools/lab-cli/recording.py
#: _DEVICE["screen"]) and the evidence-cli schema's canonical example
#: (tools/evidence-cli/schema.py SCREEN_RE — '1080x2280@440dpi'); used
#: verbatim when the report's resolution probe comes back empty (the
#: 20260925T091135Z hole) and satisfies the WxH@dpi bundle shape.
REFERENCE_FALLBACK_SCREEN = "1080x2280@440dpi"

#: The pixel_4 dpi alone, composed onto a report-carried WxH geometry
#: when the report's resolution answered but its density probe did not
#: (same provenance as REFERENCE_FALLBACK_SCREEN — the report is used
#: verbatim where it answered; the pinned density fills only the hole).
REFERENCE_FALLBACK_DENSITY_DPI = "440"

#: Last-resort device identity — defensive rungs under the report →
#: spec chain (the 20260925T091135Z run had both filled; the fallback
#: chain must never ship an empty string into the manifest): the model
#: string the pair-comparable recording driver pins (recording.py
#: _DEVICE["model"]) and the Android release the capability record
#: documents for the pinned REFERENCE_SYSTEM_IMAGE (android-30 =
#: Android 11, API 30 — capability-report.json notes.device_profile).
REFERENCE_FALLBACK_DEVICE_MODEL = "Pixel 4 (AVD pixel_4)"
REFERENCE_FALLBACK_ANDROID_VERSION = "11"

#: probe-33 port (observe.py commit eeeeb0b — "package-facts blindness
#: tolerance — pm-list retry + resolve-as-proof"; run 20260924T203342Z
#: postmortem): the dumpsys package-facts reads die on the same
#: post-restore transport flap that blinded ``pm list packages``, and
#: the 20260925T091135Z run proved one blind read rejects the whole
#: finished run at the bundle stage. Bounded blindness-aware retry at
#: observe.py's own package-facts cadence (its ``for _ in range(4): …
#: time.sleep(15)``): 4 attempts x 15 s settle — "luck is real and
#: bounded retries harvest it" (probe-17/21b doctrine).
PKG_FACTS_MAX_ATTEMPTS = 4
PKG_FACTS_SETTLE_S = 15

# ------------------------- install-time facts stash (CAMSCAN-010C)
# CAMSCAN-010C (work order "early package-facts stash — install-time
# ground truth"; the 2026-09-25 campaign-run diagnosis, verified by
# the lead): two full-chain runs reached the FINAL stage and died
# there — launch am-confirmed, ANR dismissed, steps executed — then
# the package-facts read went all-blind with INSTANT rejections
# (exit=-1, stderr tail "…ling '.set_timeout' on the sandbox with the
# desired timeout." — the E2B sandbox-lifetime rejection), sandbox age
# at death exactly 3600 s — the E2B Hobby TOTAL-lifetime cap
# (CAMSCAN-010D, lead-verified 2026-09-25 on postmortem run
# 20260925T110418Z-S001-live: sandbox created ~11:04:24, envd went
# UNAVAILABLE at 12:04:24 = age exactly 3600 s; the 010C-era "≈ 51
# min" figure was the slow-run wall-clock approximation of the same
# hard cap — slow runs: hostile install windows + launch fights
# stretch the wall clock; the README's design accepts sandbox
# death → fresh run, but the FACTS read must not live in the death
# zone). The facts themselves (application.version_name /
# version_code) are INSTALL-TIME properties — stable from the moment
# _registry_verify confirms the package; reading them at execute()-end
# (after the launch ladder + patient poll + ANR rounds + logcat) was
# pure late-run fragility. Doctrine (install-time-facts): capture them
# ONCE right after the registry verify — install confirmed, system
# freshest, BEFORE the GMS restore whose post-restore transport flap
# is the 010B blindness class — stash them on the handle
# (native.install_time_facts), and let execute() consume the stash
# first; the 010B bounded retry stays the fallback, and a both-blind
# run still fails honestly with the combined (install-time + late)
# timestamped diagnostics. Same doctrine as the blocks above: every
# constant/comment cites its work-order/probe provenance.

#: Phase labels for the two package-facts read stages (CAMSCAN-010C):
#: every diag line and console line carries its read stage, so the
#: combined both-blind failure names BOTH diag sets honestly (the
#: work order's "naming both diag sets") and the stash path's
#: provenance — WHEN the facts were discovered — is readable in the
#: console story (the manifest contract is additionalProperties:false
#: wherever a provenance field would sit, and the evidence-cli schema
#: is out of this work order's scope: the diag channel is the honest
#: note, never a smuggled schema violation).
PKG_FACTS_PHASE_INSTALL_TIME = "install-time"
PKG_FACTS_PHASE_LATE = "late"

# ------------------------- total-lifetime budget (CAMSCAN-010D)
# CAMSCAN-010D (work order "E2B Hobby total-lifetime budget governor
# — 3600s hard cap"; root cause lead-verified 2026-09-25 on campaign
# run 20260925T110418Z-S001-live, attempt 1): the E2B account is
# Hobby-tier — the SDK docstring (e2b/sandbox_sync/main.py,
# set_timeout) says "The maximum time a sandbox can be kept alive is
# 24 hours (86_400 seconds) for Pro users and 1 hour (3_600 seconds)
# for Hobby users." The empirics match EXACTLY: sandbox created
# ~11:04:24 (provision start 11:04:18), envd went UNAVAILABLE at
# 12:04:24 = age exactly 3600 s — and the evidence-stage reads were
# rejected INSTANTLY (exit=-1, ~0.5 s per read, stderr tail "…ling
# '.set_timeout' on the sandbox with the desired timeout." — the envd
# Code.UNAVAILABLE → TimeoutException mapping, e2b/envd/rpc.py +
# exceptions.py format_sandbox_timeout_exception; the "sandbox
# timeout" entry of SANDBOX_DEATH_MARKERS matches that signature).
# The provider's renewal machinery (renewal_s=3600 per set_timeout
# call) is built on a WRONG "per-call cap" reading: under Hobby,
# set_timeout(3600) at age N still caps TOTAL age at 3600 — renewal
# is a harmless no-op beyond the initial create-time deadline
# (run-005 lesson: "a fresh run gets a fresh 60-min window (the E2B
# hard cap)" — see the provider comment truth-fix in the same work
# order). This driver therefore governs its own waits against the
# cap: _Native.sandbox_t0 is the birth mark on the injected
# monotonic clock (captured in provision() immediately BEFORE
# provider.provision(spec) — the create/bootstrap minutes count;
# ordering pinned by test), the _sandbox_age_s /
# _sandbox_budget_remaining_s helpers read it, and every wait loop
# below cuts early when the remaining budget drops under the
# evidence reserve. Same doctrine as the blocks above: every
# constant cites its SDK-docstring / postmortem / work-order
# provenance — never a bare number.

#: The E2B Hobby TOTAL-lifetime cap for one sandbox: the SDK
#: docstring (e2b/sandbox_sync/main.py, set_timeout — "1 hour
#: (3_600 seconds) for Hobby users") + the 20260925T110418Z-S001-live
#: postmortem (envd UNAVAILABLE at sandbox age exactly 3600 s; the
#: instant '.set_timeout'-advice rejections are its signature). NOT a
#: per-call budget and NOT renewable under Hobby — the total age is
#: capped from create time, and a fresh run gets a fresh window
#: (run-005 lesson).
E2B_TOTAL_LIFETIME_CAP_S = 3600

#: The evidence-phase reserve the process-wait governors defend
#: (CAMSCAN-010D work order: "size it from the observed evidence-phase
#: needs: ANR ladder + remaining steps + captures + facts + logcat;
#: ~480s is the lead's estimate — sanity-check and cite"). Sizing
#: from the driver's own observed/bounded budgets: the ANR ladder
#: (ANR_MAX_ROUNDS x ANR_ROUND_SETTLE_S settles + the dump/tap reads
#: ≈ 120 s worst case) + the remaining scenario steps with their
#: per-step screenshot + ui-dump captures under TCG (S001-S003: up to
#: 3 steps x ~60-120 s ≈ 360 s worst case, ~180 s typical) + the late
#: package-facts read (bounded PKG_FACTS_MAX_ATTEMPTS x
#: PKG_FACTS_SETTLE_S settles + reads ≈ 100 s typical) + the final
#: logcat fetch (bounded by the capture window, up to DIAG_TIMEOUT_S)
#: → ~480 s covers the typical evidence tail with margin without
#: abandoning a slow cold start too early. The 20260925T110418Z
#: killer was exactly this class: _post_launch's 400 s process wait
#: ran at sandbox age ~3200→3600 s (console line "app process not
#: observed within 400s (TCG slow path)" printed INSIDE the death
#: zone) and starved steps/captures/facts of the sandbox's final
#: minutes.
LAUNCH_PROC_BUDGET_RESERVE_S = 480


def _require_api_key() -> str:
    """Preflight the credential BEFORE any provisioning (no network)."""
    key = os.environ.get(API_KEY_ENV)
    if not key:
        raise LabCliError(
            f"{API_KEY_ENV} is not set — the live reference driver refuses "
            "to provision without it (credentials come from the "
            "environment only; pass --driver recording for substrate-free "
            "runs)")
    return key


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sandbox_dead(res: Any) -> bool:
    """run-005 lesson (b): sandbox death is NOT a retryable failure."""
    blob = f"{res.stdout or ''}\n{res.stderr or ''}"
    return any(marker in blob for marker in SANDBOX_DEATH_MARKERS)


def _facts_brief(facts: dict[str, Any]) -> str:
    """Landed package facts rendered compactly, fixed key order — the
    CAMSCAN-010C stash/provenance emit lines (install-time stash
    landed / stash used / stash partial) all render through this one
    helper so the console story stays uniform."""
    return ", ".join(f"{key}={facts[key]!r}"
                     for key in ("version_name", "version_code")
                     if key in facts)


# ------------------------------- onboarding discovery (CAMSCAN-010F)

def _onb_clickable_nodes(dump_xml: str) -> list[tuple[str, str, tuple[int, int]]]:
    """(text, content-desc, bounds-center) for every CLICKABLE node of
    a ui-hierarchy dump, in document order — the discovery ladder's
    scan surface. Unparseable dumps and zero-area nodes yield nothing
    (the round still counts; the ladder settles, never crashes)."""
    try:
        root = ET.fromstring(dump_xml)
    except ET.ParseError:
        return []
    out: list[tuple[str, str, tuple[int, int]]] = []
    for node in root.iter():
        if node.tag != "node":
            continue
        if (node.get("clickable") or "").strip().lower() != "true":
            continue
        match = _ONB_BOUNDS_RE.search(node.get("bounds") or "")
        if not match:
            continue
        left, top, right, bottom = (int(g) for g in match.groups())
        if right <= left or bottom <= top:   # zero-area nodes are not
            continue                         # tappable
        out.append(((node.get("text") or ""),
                    (node.get("content-desc") or ""),
                    ((left + right) // 2, (top + bottom) // 2)))
    return out


def _onb_round_action(nodes: list[tuple[str, str, tuple[int, int]]]) \
        -> tuple[str, str, tuple[int, int] | None]:
    """One discovery round's verdict over the clickable nodes.

    Returns (kind, label, center): ``("permission", ...)``
    ("While using the app" / "Only this time" — the registered global
    selectors' texts) is tapped first (grants are idempotent-safe);
    ``("affirmative", ...)`` is the first onboarding-progress label in
    document order (case-insensitive substring over text/content-desc)
    on a node that is NOT an excluded trap; ``("excluded", ...)`` means
    only refused traps are present (never tapped — and NOT completion);
    ``("", "", None)`` means no actionable control at all — onboarding
    complete."""
    for text, desc, center in nodes:
        if text in _ONB_PERMISSION_TEXTS or desc in _ONB_PERMISSION_TEXTS:
            return "permission", (text or desc), center
    refused: tuple[str, str, tuple[int, int] | None] = ("", "", None)
    for text, desc, center in nodes:
        values = (text, desc)
        if any(bad in value.lower()
               for value in values
               for bad in _ONB_EXCLUSION_SUBSTRINGS):
            if refused[2] is None:      # remembered for the refusal line
                refused = ("excluded", (text or desc), center)
            continue    # NEVER tapped, even if it is all that is
                        # clickable
        for value in values:
            if any(good in value.lower()
                   for good in _ONB_AFFIRMATIVE_SUBSTRINGS):
                return "affirmative", value, center
    return refused


def onboarding_discovery_loop(bridge: Any, *, step_timeout: int,
                              sleep: Callable[[float], None],
                              emit: Callable[[str], None],
                              label: str) -> bool:
    """CAMSCAN-010F — the dump-first onboarding discovery ladder (the
    PROVEN _anr_ladder shape: dump → bounds-regex → center-tap →
    settle → bounded rounds), shared by both live drivers: the
    reference env runs it directly (its registry scope is RESERVED-
    EMPTY by design — ids come from observed live dumps, never
    invention, so a registry tap can never resolve there); the
    implementation env taps its design-contract next_button FIRST and
    falls here on UnknownTargetError.

    Per round: a FRESH ui dump through the hardened CAMSCAN-010F
    capture (a failed capture counts the round, settles, continues);
    the permission dialog is checked FIRST (exact registered
    affirmative texts); then affirmative discovery (first match in
    document order; excluded traps are skipped and never tapped). A
    round whose dump holds neither — and no excluded trap — is
    onboarding COMPLETE (the scenario asserts onboarding-skippable-
    or-completable: running out of onboarding controls IS completion).
    Exhausted rounds → False — the honest failure; the driver's
    existing failure path does the rest."""
    prefix = f"  {label}: onboarding"
    for round_no in range(1, ONB_MAX_ROUNDS + 1):
        try:
            dump = bridge.ui_dump(timeout=step_timeout)
        except Exception as exc:  # noqa: BLE001 — a failed capture is a round, never a crash
            emit(f"{prefix} round {round_no}: ui dump failed "
                 f"({type(exc).__name__}: {exc}) — settling")
            sleep(ONB_ROUND_SETTLE_S)
            continue
        if not dump.ok:
            emit(f"{prefix} round {round_no}: ui dump unavailable "
                 f"({(dump.error or 'no dump body')[:160]}) — settling")
            sleep(ONB_ROUND_SETTLE_S)
            continue
        kind, hit, center = _onb_round_action(
            _onb_clickable_nodes(dump.text or ""))
        if kind == "permission":
            bridge.tap(*center)
            emit(f"{prefix} round {round_no}: permission granted "
                 f"at ({center[0]},{center[1]})")
            sleep(ONB_ROUND_SETTLE_S)
            continue
        if kind == "affirmative":
            bridge.tap(*center)
            emit(f"{prefix} round {round_no}: control '{hit}' tapped "
                 f"at ({center[0]},{center[1]})")
            sleep(ONB_ROUND_SETTLE_S)
            continue
        if kind == "excluded":
            emit(f"{prefix} round {round_no}: control '{hit}' excluded "
                 f"— not tapped, continuing")
            sleep(ONB_ROUND_SETTLE_S)
            continue
        emit(f"{prefix} round {round_no}: no actionable control "
             f"— onboarding complete")
        return True
    emit(f"{prefix} discovery exhausted {ONB_MAX_ROUNDS} rounds "
         f"without completing — honest failure")
    return False


def extract_split_bundle(xapk: Path, dest: Path) -> list[Path]:
    """Extract an .xapk/.apks split bundle (ZIP) into its member APKs.

    Mirrors observe.py's proven extractor (CAMSCAN-004 discovery: the
    official CamScanner 7.25.5 bundle is base + config.arm64_v8a +
    config.en — installed with install-multiple, NEVER adb install).
    Raises on a bundle with no base apk. Observable-artifact handling
    only (unzipping our own APK file).
    """
    dest.mkdir(parents=True, exist_ok=True)
    members: list[Path] = []
    base: list[str] = []
    with zipfile.ZipFile(xapk) as z:
        for name in z.namelist():
            if name.endswith(".apk"):
                target = dest / Path(name).name
                target.write_bytes(z.read(name))
                members.append(target)
        try:
            meta = json.loads(z.read("manifest.json"))
            base = [s["file"] for s in meta.get("split_apks", [])
                    if s.get("id") == "base"]
        except (KeyError, ValueError):
            pass  # no (parseable) XAPK manifest — plain zip of splits
    if base and not (dest / base[0]).exists():
        raise LabCliError(f"base split {base[0]!r} missing after extraction")
    if not base and not any("config" not in m.name for m in members):
        raise LabCliError(f"no base apk found in split bundle {xapk}")
    if not members:
        raise LabCliError(f"no .apk members in split bundle {xapk}")
    return sorted(members)


class _Native:
    """Driver-private state carried on the handle (never serialized)."""

    def __init__(self, provider: Any, env_id: str, adb: str,
                 launcher_component: str,
                 install_time_facts: dict[str, Any] | None = None,
                 install_time_facts_diag: list[str] | None = None,
                 sandbox_t0: float = 0.0) -> None:
        self.provider = provider
        self.env_id = env_id
        self.adb = adb
        self.launcher_component = launcher_component
        # CAMSCAN-010D: the sandbox's birth mark on the driver's
        # injected monotonic clock — captured in provision()
        # immediately BEFORE provider.provision(spec) (ordering
        # pinned by test: the create/bootstrap minutes count toward
        # the E2B Hobby total-lifetime cap; a mark taken after
        # provision would understate the age and overstate the
        # remaining budget, cutting the governors too late). The
        # budget helpers (_sandbox_age_s /
        # _sandbox_budget_remaining_s) read it; the 0.0 default only
        # keeps direct constructions (the teardown contract tests)
        # valid — a real handle always carries the captured mark.
        self.sandbox_t0: float = sandbox_t0
        # CAMSCAN-010C: the install-time package-facts stash — ground
        # truth captured right after _registry_verify (see the 010C
        # provenance block above the PKG_FACTS_PHASE_* constants).
        # Empty when the early read went blind (BEST-EFFORT: provision
        # never fails on a blind early read — the execute-time read
        # stays the fallback); whatever landed rides along, and the
        # early diag lines sit beside it so a both-blind run fails
        # honestly with the COMBINED (install-time + late)
        # timestamped diagnostics.
        self.install_time_facts: dict[str, Any] = dict(
            install_time_facts or {})
        self.install_time_facts_diag: list[str] = list(
            install_time_facts_diag or [])


class ReferenceDriver:
    """Live reference-env driver: official CamScanner on the e2b TCG
    substrate, per the probe-17 recipe (module docstring)."""

    slug = "reference-live"

    def __init__(self, apk: str | Path | None = None, *,
                 provider: Any = None,
                 sleep: Callable[[float], None] = time.sleep,
                 monotonic: Callable[[], float] = time.monotonic,
                 wall_time: Callable[[], float] = time.time) -> None:
        #: local XAPK/APK path (the --apk push fallback / sha source).
        self.apk = Path(apk) if apk is not None else None
        #: transport injection — the hermetic tests script a fake; the
        #: live path lazy-constructs E2BProvider inside provision
        #: (importing it must never require the e2b SDK at import time).
        self._provider = provider
        #: time injection — the ladder's waits are fake-clock-driven in
        #: tests (never real sleeps in pytest).
        self._sleep = sleep
        self._monotonic = monotonic
        #: wall-clock source for the probe-29 ladder-diag timestamps
        #: (HH:MM:SS prefixes — injectable so the hermetic tests stay
        #: deterministic; UTC-formatted for host independence).
        self._wall_time = wall_time

    # ------------------------------------------------------------- lifecycle

    def provision(self, request: ProvisionRequest) -> DriverHandle:
        _require_api_key()
        if request.subject != "reference":
            raise LabCliError(
                "the live reference driver is on record for env=reference "
                "only (the implementation env's driver is e2b-live)")
        apk = self.apk or (Path(request.apk) if request.apk else None)
        apk_url = os.environ.get(APK_URL_ENV, "")
        if apk is None and not apk_url:
            raise LabCliError(
                "live reference runs need the CamScanner XAPK: --apk "
                "<path-to-xapk> (push fallback) or "
                f"{APK_URL_ENV}=<presigned-url> (the proven in-sandbox "
                "curl delivery — the e2b files-API push of the 221 MB "
                "bundle stalls)")
        emit = request.emit

        # Lazy imports: the e2b SDK is only needed from here on; the
        # bootstrap module itself is stdlib-only (SDK-free import).
        from lab.providers.e2b.bootstrap import ADB
        from lab.providers.types import EnvironmentSpec
        provider = self._provider
        if provider is None:
            from lab.providers.e2b import E2BProvider
            provider = E2BProvider()

        camera = (request.scenario.fixture or {}).get("camera")
        spec = EnvironmentSpec(
            purpose="reference",        # AVD camscan-reference (own AVD)
            system_image=REFERENCE_SYSTEM_IMAGE,
            memory_mb=REFERENCE_MEMORY_MB,
            extra_sdk_packages=(REFERENCE_SYSTEM_IMAGE,),
            camera_poster=(str(camera) if isinstance(camera, str) and camera
                           else None),
            tag=request.run_id,
        )
        emit("  reference: provisioning e2b sandbox (google_apis image, "
             "TCG boot budget is provider-owned)…")
        # CAMSCAN-010D: the budget governor's birth mark — the
        # injected monotonic clock captured immediately BEFORE
        # provider.provision(spec) so the sandbox create/bootstrap
        # minutes count toward the E2B Hobby total-lifetime cap
        # (E2B_TOTAL_LIFETIME_CAP_S). Ordering pinned by test.
        sandbox_t0 = self._monotonic()
        env = provider.provision(spec)
        env_id = env.env_id
        # CAMSCAN-010C: the install-time facts stash (initialized empty
        # so the handle construction below never depends on the try
        # block having reached the read; a raising path destroys the
        # sandbox and never gets here).
        install_facts: dict[str, Any] = {}
        install_facts_diag: list[str] = []
        try:
            boot = provider.start(env_id)
            emit(f"  reference: booted {env.avd_name} "
                 f"(boot_s={boot.get('boot_s')})")
            self._boot_settle(provider, env_id, ADB, emit)
            self._apply_dexopt_filter(provider, env_id, ADB, emit)
            self._quiesce_gms(provider, env_id, ADB, emit)
            _remote_files, install_cmd, delivery = self._acquire_bundle(
                provider, env_id, ADB, apk, apk_url, emit)
            self._install_ladder(provider, env_id, ADB, install_cmd, emit)
            self._registry_verify(provider, env_id, ADB, emit)
            # CAMSCAN-010C: the EARLY package-facts read — one full
            # 010B _package_facts invocation (4x15s bounded blindness
            # retry, timestamped diag) run right after _registry_verify
            # succeeds: install confirmed, system freshest, BEFORE the
            # GMS restore (whose post-restore transport flap is the
            # 010B blindness class). The landed facts are INSTALL-TIME
            # GROUND TRUTH — stable from registry confirmation — so
            # execute() consumes the stash instead of re-reading them
            # at the end of the sandbox's lifetime (the 2026-09-25
            # campaign diagnosis: all-blind INSTANT '.set_timeout'
            # rejections at sandbox age exactly 3600 s — the E2B
            # Hobby total-lifetime cap, CAMSCAN-010D postmortem run
            # 20260925T110418Z-S001-live). BEST-EFFORT: a blind
            # early read NEVER fails provision — the late read stays
            # the fallback; the diag rides the handle for the combined
            # honest-failure diagnostics.
            install_facts, install_facts_diag, _early_expired = \
                self._package_facts(
                    provider, env_id, ADB, REFERENCE_PACKAGE, emit,
                    phase=PKG_FACTS_PHASE_INSTALL_TIME)
            # (_early_expired: an install-time read that carried the
            # sandbox-death signature aborted its retries at once —
            # the very next _restore_gms read raises the death
            # LabCliError and provision cleans up; the fast-abort
            # just saved the bounded-retry budget. Best-effort
            # doctrine unchanged: the early read never fails
            # provision by itself.)
            if install_facts:
                emit("  reference: install-time package facts stashed ("
                     + _facts_brief(install_facts)
                     + ") — CAMSCAN-010C ground truth for execute()")
            else:
                emit("  reference: install-time package facts blind "
                     f"after {PKG_FACTS_MAX_ATTEMPTS} bounded attempts "
                     "(best-effort — provision continues; the "
                     "execute-time read stays the fallback; its diag "
                     "recorded on the handle)")
            self._restore_gms(provider, env_id, ADB, emit)
            launcher = self._resolve_launcher(provider, env_id, ADB, emit)
            self._grant_preconditions(provider, env_id, ADB,
                                      request.scenario, emit)
        except LabCliError:
            self._destroy_quietly(provider, env_id, emit)
            raise
        except Exception as exc:
            self._destroy_quietly(provider, env_id, emit)
            raise LabCliError(
                f"reference provisioning failed: {type(exc).__name__}: "
                f"{exc}") from exc
        except BaseException:  # SIGINT is not an Exception
            # CAMSCAN-010D: KeyboardInterrupt/SystemExit are
            # BaseExceptions — ``except Exception`` let a SIGINT
            # campaign stop sail past this cleanup and LEAK the paid
            # sandbox (2026-09-25 lead-verified incident, killed
            # manually via the E2B API minutes later). Destroy
            # quietly and re-raise the raw signal — the operator's
            # stop always propagates, the sandbox always dies.
            self._destroy_quietly(provider, env_id, emit)
            raise

        report = provider.report(env_id) or {}
        # CAMSCAN-010B: the report's identity probes can come back empty
        # for this env (the 20260925T091135Z-S001-live postmortem: NO
        # resolution at all — device.screen shipped empty and the
        # evidence-cli bundle rejected the finished run). Every field
        # falls back through the provisioned spec to the
        # capability-record-documented pixel_4 profile — never an empty
        # string into the manifest.
        resolution = str(report.get("resolution") or "").strip()
        density = str(report.get("density") or "").strip().removesuffix(
            "dpi")
        if not resolution:
            # the documented pixel_4 geometry (REFERENCE_FALLBACK_SCREEN
            # provenance: capability-report.json notes.device_profile)
            screen = REFERENCE_FALLBACK_SCREEN
        elif SCREEN_RE.match(resolution):
            # the report already carries the full WxH@dpi shape — use
            # it verbatim (no override, no doubled dpi suffix)
            screen = resolution
        else:
            # report WxH verbatim (never overridden by the pinned
            # geometry); dpi from the report's density probe, else the
            # pinned pixel_4 density
            screen = (f"{resolution}@"
                      f"{density or REFERENCE_FALLBACK_DENSITY_DPI}dpi")
        device = {
            "model": str(report.get("device_model")
                         or spec.device_profile
                         or REFERENCE_FALLBACK_DEVICE_MODEL),
            "android_version": str(report.get("android_version")
                                   or REFERENCE_FALLBACK_ANDROID_VERSION),
            "screen": screen,
            "locale": str(report.get("locale") or spec.locale),
            "timezone": str(report.get("timezone") or spec.timezone),
            "permission_baseline": {
                perm: True
                for pre in request.scenario.preconditions
                if (perm := _PRECONDITION_PERMISSIONS.get(pre))
            },
        }
        installer_sha = ""
        if apk is not None and Path(apk).is_file():
            installer_sha = _file_sha256(apk)
        elif delivery == "in-sandbox-url-download":
            installer_sha = RECORDED_XAPK_SHA256
        application = {
            "package": REFERENCE_PACKAGE,
            # discovered facts — placeholders here; execute() replaces
            # them from the install-time stash when it landed
            # (CAMSCAN-010C ground truth) and otherwise from the late
            # dumpsys read (the 010B fallback).
            "version_name": "",
            "version_code": 0,
            "installer_sha256": installer_sha,
        }
        handle = DriverHandle(
            subject="reference",
            provider_slug=str(request.provider_report.get("slug", "e2b")),
            environment_id=env_id,
            capabilities=provider_capabilities(request.provider_report),
            application=application,
            device=device,
            native=_Native(provider, env_id, ADB, launcher,
                           install_time_facts=install_facts,
                           install_time_facts_diag=install_facts_diag,
                           sandbox_t0=sandbox_t0),
        )
        emit(f"  reference: environment ready ({env_id}, delivery="
             f"{delivery}, launcher={launcher or 'resolved-at-launch'})")
        return handle

    def execute(self, handle: DriverHandle,
                request: ExecutionRequest) -> SubjectRunResult:
        from tools.adb_bridge.bridge import AdbBridge

        native: _Native = handle.native
        bridge = AdbBridge(native.provider, native.env_id,
                           default_timeout_s=float(request.scenario
                                                   .step_timeout_seconds))
        subject_dir = Path(request.stage_dir) / request.subject
        for sub in ("screenshots", "ui", "logs"):
            (subject_dir / sub).mkdir(parents=True, exist_ok=True)

        problems: list[str] = []
        trace: list[dict[str, Any]] = []
        executed = 0
        deadline = self._monotonic() + request.scenario.timeout_seconds
        started_real = request.clock() or request.started_at

        for plan in request.step_plans:
            if self._monotonic() > deadline:
                problems.append(
                    "meta.timeout_seconds exceeded — BLOCKED (timeout), "
                    "never silently truncated")
                break
            outcome, launch_diag = self._invoke_plan(
                bridge, plan, handle.application["package"],
                request.scenario.step_timeout_seconds, native, request.emit)
            entry: dict[str, Any] = {
                "t_ms": 1400 * (plan.index - 1),
                "action": plan.step.action,
                "target": (str(plan.step.arg)
                           if plan.step.arg is not None else ""),
                "result": "ok" if outcome else "failed",
            }
            if not outcome and launch_diag:
                # CAMSCAN-010A (work-order item 8): failed-launch
                # forensics in the run-metadata action trace — attempt-1
                # left nothing but "step 01 launch failed"; the next
                # postmortem must be a read, not an inference. (Failure
                # only: a successful launch keeps the deterministic
                # trace shape — the driver contract's no-wall-clock
                # doctrine; the probe-29 ladder timestamps ride only
                # the failure forensics, where the work order demands
                # them.)
                entry["diag"] = launch_diag
            trace.append(entry)
            executed += 1
            if outcome and any(c.verb == "launch" for c in plan.calls):
                # probe-17 steps 5-6: patient launch settle + the
                # SystemUI-ANR dismissal ladder after the first
                # successful launch.
                self._post_launch(bridge, native,
                                  request.scenario.step_timeout_seconds,
                                  request.emit)
            self._capture_step(bridge, subject_dir, plan.index,
                               plan.step.action)
            if not outcome:
                problems.append(f"step {plan.index:02d} "
                               f"{plan.step.label()} failed")
                if launch_diag:
                    # CAMSCAN-010A (work-order item 8): the timestamped
                    # ladder diagnostics (probe-29 HH:MM:SS lines — am
                    # output tails, exit codes, gate state, death
                    # forensics) reach the evidence layer's problem
                    # list / reason, not just the console.
                    problems.append(
                        f"step {plan.index:02d} launch ladder diagnostics "
                        f"({len(launch_diag)} lines): "
                        + " | ".join(launch_diag))

        logcat = bridge.logcat()
        (subject_dir / "logs" / "logcat.txt").write_text(
            logcat.text or "", encoding="utf-8")

        application = dict(handle.application)
        # CAMSCAN-010B: the package-facts discovery is blindness-tolerant
        # (the probe-33 port) and fails the run HONESTLY when the facts
        # do not land — the 20260925T091135Z run proved the old
        # best-effort hole: one blind dumpsys read shipped an empty
        # version_name and the evidence-cli bundle rejected the whole
        # finished run with a cryptic schema error. The readable reason
        # names every blind read; the runner (run.py) never bundles a
        # failed subject, so the empty placeholder never reaches the
        # validator.
        #
        # CAMSCAN-010C: STASH-FIRST. The install-time stash
        # (native.install_time_facts — captured right after the registry
        # verify, when the system is freshest, precisely because the
        # 2026-09-25 campaign runs died re-reading the facts at
        # exactly 3600 s sandbox age — the E2B Hobby total-lifetime
        # cap, CAMSCAN-010D) is the ground truth for what it
        # carries: when it carries BOTH facts the late dumpsys read is
        # NOT required. The late read (the 010B machinery, unchanged)
        # runs only for facts the stash could not land, seeded with the
        # stashed ones — the 010B doctrine "a landed fact is never
        # re-read" extends naturally to the stash. Fact provenance —
        # WHEN the facts were discovered — rides the driver's diag
        # channel: the console line names the source, and a both-blind
        # failure names BOTH diag sets (install-time + late) in the
        # problems/reason. (The manifest contract is
        # additionalProperties:false wherever a provenance field would
        # sit and the evidence-cli schema is out of this work order's
        # scope — the honest note is the diag, never a smuggled schema
        # violation.)
        install_facts = dict(native.install_time_facts)
        install_diag = list(native.install_time_facts_diag)
        facts: dict[str, Any] = {}
        pkg_diag: list[str] = []
        if all(key in install_facts
               for key in ("version_name", "version_code")):
            facts = install_facts
            request.emit(
                "  reference: package facts from the install-time stash ("
                + _facts_brief(facts)
                + ") — install-time ground truth (captured at "
                "provision after registry verify; the late dumpsys read "
                "is not required — CAMSCAN-010C)")
        else:
            if install_facts:
                request.emit(
                    "  reference: install-time stash partial ("
                    + _facts_brief(install_facts)
                    + ") — the late read fills the gap (the 010B "
                    "fallback, seeded with the stashed facts)")
            elif install_diag:
                request.emit(
                    "  reference: install-time stash empty (early read "
                    "blind) — the late read runs as the 010B fallback")
            facts, pkg_diag, late_expired = self._package_facts(
                native.provider, native.env_id, native.adb,
                application["package"], request.emit,
                phase=PKG_FACTS_PHASE_LATE, seed=install_facts)
        application.update(facts)
        if len(facts) < 2:
            missing = [f"application.{key}"
                       for key in ("version_name", "version_code")
                       if key not in facts]
            if late_expired:
                # CAMSCAN-010D: the late read hit the sandbox-death
                # signature and the fast-abort stopped the retries —
                # this is NOT the probe-33 blind-transient story, so
                # the honest-fail reason names the expiry with age
                # context (the 20260925T110418Z root cause, readable)
                # instead of the bounded-attempt counts (which would
                # be a lie: no retries settled on the corpse).
                problems.append(
                    f"sandbox expired (E2B total-lifetime cap "
                    f"{E2B_TOTAL_LIFETIME_CAP_S}s) at age "
                    f"~{self._sandbox_age_s(native):.0f}s — reads "
                    "rejected instantly with the set_timeout-advice "
                    "signature (run-005 lesson b: never hammer a "
                    "corpse — no retries settled) — "
                    + " and ".join(missing)
                    + " unreadable; the evidence bundle requires the "
                    "discovered facts (never an empty placeholder "
                    "into the validator)")
            else:
                problems.append(
                    f"package facts unreadable after "
                    f"{PKG_FACTS_MAX_ATTEMPTS} bounded attempts at install "
                    f"time AND {PKG_FACTS_MAX_ATTEMPTS} at execute time "
                    "(probe-33 blindness tolerance; CAMSCAN-010C combined "
                    "install-time + late diagnostics) — "
                    + " and ".join(missing)
                    + " blind; the evidence bundle requires the discovered "
                    "facts (never an empty placeholder into the validator)")
            problems.append(
                f"package-facts diagnostics ("
                f"{len(install_diag) + len(pkg_diag)} lines: "
                f"{len(install_diag)} install-time + {len(pkg_diag)} "
                "late): " + " | ".join(install_diag + pkg_diag))
        metadata = {
            "run_id": request.run_id,
            "scenario": request.scenario.id,
            "subject": request.subject,
            "provider": {
                "slug": handle.provider_slug,
                "capabilities": handle.capabilities,
                "environment_id": handle.environment_id,
            },
            "application": application,
            "device": handle.device,
            "fixtures": request.fixtures,
            "action_trace": trace,
            "started_at": started_real,
            "finished_at": request.clock() or request.finished_at,
        }
        from tools.evidence_cli.jsonio import dump as jsonio_dump
        jsonio_dump(Path(request.stage_dir) / "run-metadata.json", metadata)
        return SubjectRunResult(
            subject=request.subject, ok=not problems,
            steps_executed=executed, problems=problems,
            reason="; ".join(problems))

    def teardown(self, handle: DriverHandle, error: str = "",
                 emit: Callable[[str], None] = print) -> None:
        native = handle.native
        if native is None:
            return
        try:
            native.provider.stop(native.env_id)
        except Exception as exc:  # noqa: BLE001 — teardown never raises
            emit(f"  {handle.subject}: stop failed ({exc})")
        try:
            native.provider.destroy(native.env_id)
        except Exception as exc:  # noqa: BLE001
            emit(f"  {handle.subject}: destroy failed ({exc})")
        suffix = f" (error: {error})" if error else ""
        emit(f"  {handle.subject}: destroyed {native.env_id} "
             f"[paid environment released]{suffix}")

    # ------------------------------------- lifetime budget (CAMSCAN-010D)

    def _sandbox_age_s(self, native: _Native) -> float:
        """CAMSCAN-010D: the sandbox's age in seconds — the monotonic
        delta from ``_Native.sandbox_t0`` (the birth mark captured in
        provision() immediately BEFORE provider.provision, so the
        sandbox create/bootstrap minutes count toward the cap; the
        ordering is pinned by test). The 20260925T110418Z-S001-live
        postmortem: envd went UNAVAILABLE at age exactly
        E2B_TOTAL_LIFETIME_CAP_S — this is the clock that hit
        3600 s."""
        return self._monotonic() - native.sandbox_t0

    def _sandbox_budget_remaining_s(self, native: _Native) -> float:
        """CAMSCAN-010D: the sandbox seconds left under the E2B Hobby
        total-lifetime cap (E2B_TOTAL_LIFETIME_CAP_S — the SDK
        docstring's "1 hour (3_600 seconds) for Hobby users" + the
        20260925T110418Z-S001-live postmortem: envd UNAVAILABLE at
        age exactly 3600 s). Renewal cannot extend it under Hobby
        (set_timeout(3600) at age N still caps TOTAL age at 3600 —
        the provider's renewal machinery is a no-op beyond the
        create-time deadline), so every wait governor in this driver
        reads this remaining budget."""
        return E2B_TOTAL_LIFETIME_CAP_S - self._sandbox_age_s(native)

    # ------------------------------------------------------ recipe internals

    def _boot_settle(self, provider: Any, env_id: str, adb: str,
                     emit: Callable[[str], None]) -> None:
        """probe-17 step 1: wait for the package service to answer
        (`pm list packages`), then +60 s settle."""
        settled, last = self._wait_package_service(
            provider, env_id, adb, SERVICE_SETTLE_MAX_PROBES,
            SERVICE_SETTLE_POLL_S, emit, what="boot settle")
        if not settled:
            raise LabCliError(
                "reference: package service did not settle after boot "
                f"(last: {last[:200]})")
        emit(f"  reference: package service settled (+{BOOT_SETTLE_S}s)")
        self._sleep(BOOT_SETTLE_S)

    def _wait_package_service(self, provider: Any, env_id: str, adb: str,
                              max_probes: int, poll_s: float,
                              emit: Callable[[str], None],
                              what: str = "service re-settle") \
            -> tuple[bool, str]:
        """Probe `pm list packages` until it answers (run-003 lesson:
        the service can die MID-STREAM and recover minutes later).
        Returns (settled, last_output)."""
        last = ""
        for _ in range(max_probes):
            res = provider.execute(
                env_id, f"{adb} shell pm list packages 2>&1 | head -1",
                timeout=POLL_TIMEOUT_S)
            if _sandbox_dead(res):
                raise LabCliError(
                    f"reference: sandbox death during {what}: "
                    f"{(res.stderr or res.stdout or '').strip()[-300:]}")
            last = (res.stdout or "").strip()
            if last.startswith("package:"):
                return True, last
            self._sleep(poll_s)
        return False, last

    def _apply_dexopt_filter(self, provider: Any, env_id: str, adb: str,
                             emit: Callable[[str], None]) -> None:
        """probe-17 step 2: `setprop pm.dexopt.install verify` BEFORE any
        install — the cheap filter eliminates the dexopt monitor storm
        that killed system_server in probe 9 (root once, setprop,
        unroot: shell identity for the install)."""
        res = provider.execute(env_id, f"""
{adb} root 2>&1 | head -1
sleep 5
{adb} wait-for-device
{adb} shell setprop pm.dexopt.install verify && echo SETPROP_OK
{adb} unroot 2>&1 | head -1
sleep 5
{adb} wait-for-device
""", timeout=DEXOPT_TIMEOUT_S)
        if _sandbox_dead(res):
            raise LabCliError(
                "reference: sandbox death during dexopt setprop: "
                f"{(res.stderr or res.stdout or '').strip()[-300:]}")
        if "SETPROP_OK" not in (res.stdout or ""):
            raise LabCliError(
                "reference: dexopt filter setprop failed (no SETPROP_OK) — "
                "refusing to install without the cheap filter (probe-9 "
                "system_server death); output: "
                f"{(res.stdout or res.stderr or '')[-200:]}")
        emit("  reference: dexopt filter set (pm.dexopt.install=verify)")

    def _quiesce_gms(self, provider: Any, env_id: str, adb: str,
                     emit: Callable[[str], None]) -> None:
        """probe-22 step 2.5: disable the GMS churn sources (gms,
        wellbeing, vending) BEFORE the install ladder — the fragile
        install window runs without the background binder storm that
        bg-ANRs the networkstack module. Best-effort per package (a
        missing vending image is fine); sandbox death still aborts
        cleanly (never hammer a corpse)."""
        for pkg in GMS_QUIESCE_PKGS:
            res = provider.execute(
                env_id,
                f"{adb} shell pm disable-user --user 0 {pkg} 2>&1 | tail -1",
                timeout=GMS_QUIESCE_TIMEOUT_S)
            if _sandbox_dead(res):
                raise LabCliError(
                    "reference: sandbox death during GMS quiesce "
                    f"({pkg}): "
                    f"{(res.stderr or res.stdout or '').strip()[-300:]} "
                    "(clean abort — a fresh run gets a fresh 60-min "
                    "window)")
            emit(f"  reference: GMS quiesce {pkg}: "
                 f"{(res.stdout or '').strip()[:80] or '(no output)'}")
        emit("  reference: GMS churn sources quiesced for the install "
             "window (probe-22)")

    def _restore_gms(self, provider: Any, env_id: str, adb: str,
                     emit: Callable[[str], None]) -> None:
        """probe-22: re-enable the quiesced GMS packages after the
        install lands (registry verified) so scenario observation runs
        with GMS present, then settle so the re-enabled processes spin
        up BEFORE the app launch (their binder churn lands outside the
        launch window). Best-effort: failures are emitted, never fatal
        (the app itself does not require GMS to launch — probe-17)."""
        for pkg in GMS_QUIESCE_PKGS:
            res = provider.execute(
                env_id, f"{adb} shell pm enable {pkg} 2>&1 | tail -1",
                timeout=POLL_TIMEOUT_S)
            if _sandbox_dead(res):
                raise LabCliError(
                    "reference: sandbox death during GMS restore "
                    f"({pkg}): "
                    f"{(res.stderr or res.stdout or '').strip()[-300:]} "
                    "(clean abort — a fresh run gets a fresh 60-min "
                    "window)")
            emit(f"  reference: GMS restore {pkg}: "
                 f"{(res.stdout or '').strip()[:80] or '(no output)'}")
        emit("  reference: GMS restored for observation (+"
             f"{BOOT_SETTLE_S}s settle)")
        self._sleep(BOOT_SETTLE_S)

    def _acquire_bundle(self, provider: Any, env_id: str, adb: str,
                        apk: Path | None, apk_url: str,
                        emit: Callable[[str], None]) \
            -> tuple[list[str], str, str]:
        """Stage the bundle's APKs as SANDBOX-side files; returns
        (remote_files, install_cmd, delivery).

        URL mode (CAMSCAN_APK_URL — the proven delivery, observe.py's
        2026-09-23 note): in-sandbox curl from the presigned URL,
        sha256-verified in-sandbox (against the local --apk's hash when
        present, else the recorded archive hash), splits unzipped to
        /root/xapk/. Local mode: host-side unzip + provider push (the
        files-API stall makes push the fallback). A plain .apk (not a
        split bundle) pushes + installs single.
        """
        suffix = Path(apk).suffix.lower() if apk is not None else ".xapk"
        if apk_url and suffix in (".xapk", ".apks"):
            want = (_file_sha256(apk)
                    if apk is not None and Path(apk).is_file()
                    else RECORDED_XAPK_SHA256)
            res = provider.execute(env_id, f"""
cd /root
curl -fsSL -o bundle.dl '{apk_url}'
sha256sum bundle.dl
mkdir -p xapk
cd xapk
unzip -o ../bundle.dl '*.apk' 2>&1 | tail -3
ls -la /root/xapk/*.apk
""", timeout=CURL_TIMEOUT_S)
            if _sandbox_dead(res):
                raise LabCliError(
                    "reference: sandbox death during XAPK download: "
                    f"{(res.stderr or res.stdout or '').strip()[-300:]}")
            if res.exit_code != 0:
                raise LabCliError(
                    "reference: in-sandbox XAPK download failed (exit "
                    f"{res.exit_code}): "
                    f"{((res.stderr or res.stdout) or '').strip()[-300:]}")
            got = ""
            for line in (res.stdout or "").splitlines():
                if re.match(r"^[0-9a-f]{64}\s+bundle\.dl", line):
                    got = line.split()[0]
                    break
            if not got or got != want:
                raise LabCliError(
                    "reference: in-sandbox download sha256 mismatch (want "
                    f"{want[:16]}…, got {got[:16]}…)")
            splits = [line.strip().split("/")[-1]
                      for line in (res.stdout or "").splitlines()
                      if line.strip().endswith(".apk")
                      and line.startswith("-")]
            if not splits:
                raise LabCliError(
                    "reference: no split APKs found after in-sandbox unzip")
            remote_files = [f"/root/xapk/{name}" for name in splits]
            emit(f"  reference: XAPK delivered in-sandbox via presigned "
                 f"URL ({len(splits)} splits, sha256 verified)")
            return (remote_files,
                    f"{adb} install-multiple -r " + " ".join(remote_files),
                    "in-sandbox-url-download")
        if suffix in (".xapk", ".apks"):
            if apk is None or not Path(apk).is_file():
                raise LabCliError(f"reference: local XAPK not found: {apk}")
            remote_files: list[str] = []
            with tempfile.TemporaryDirectory(
                    prefix="camscan-ref-xapk-") as tmp:
                splits = extract_split_bundle(Path(apk), Path(tmp))
                for split in splits:
                    remote = f"/root/{split.name}"
                    provider.transfer(env_id, "push", split, remote)
                    remote_files.append(remote)
            emit(f"  reference: XAPK extracted + pushed locally "
                 f"({len(remote_files)} splits — files-API fallback)")
            return (remote_files,
                    f"{adb} install-multiple -r " + " ".join(remote_files),
                    "local-push")
        # plain APK: push + single install
        if apk is None or not Path(apk).is_file():
            raise LabCliError(f"reference: local APK not found: {apk}")
        remote = f"/root/{Path(apk).name}"
        provider.transfer(env_id, "push", Path(apk), remote)
        emit("  reference: single APK pushed (files-API fallback)")
        return [remote], f"{adb} install -r {remote}", "local-push"

    def _install_ladder(self, provider: Any, env_id: str, adb: str,
                        install_cmd: str,
                        emit: Callable[[str], None]) -> None:
        """The GMS-churn install retry ladder (probe-17 step 3 + the
        run-003/run-005 lessons): each attempt runs in the BACKGROUND
        with an EXIT_n marker file (probe-21b pattern) and a bounded
        outcome window; the window edge checks the REGISTRY before
        declaring a timeout (probe-23: a landed commit outlives the adb
        stream under degraded TCG); failed attempts are separated by
        package-service re-settle probes; sandbox death aborts cleanly;
        Success is followed by the caller's explicit registry
        verification."""
        attempts = 0
        while attempts < INSTALL_MAX_ATTEMPTS:
            attempts += 1
            # diagnostics (run-001 lesson: verdicts that die on stderr
            # are invisible otherwise)
            diag = provider.execute(env_id, f"""
echo "=== install attempt {attempts} diagnostics ==="
ls -la /root/xapk/*.apk /root/*.apk 2>&1 | head -8
{adb} shell pm list packages 2>&1 | head -1
""", timeout=DIAG_TIMEOUT_S)
            if _sandbox_dead(diag):
                raise LabCliError(
                    f"reference: sandbox death before install attempt "
                    f"{attempts}: "
                    f"{(diag.stderr or diag.stdout or '').strip()[-300:]} "
                    "(clean abort — a fresh run gets a fresh 60-min "
                    "window)")
            # background install with EXIT marker (probe-21b pattern)
            launcher = provider.execute(env_id, f"""
rm -f /root/install.out
({install_cmd} > /root/install.out 2>&1; echo "EXIT_$?" >> /root/install.out) &
echo LAUNCHED
""", timeout=INSTALL_LAUNCH_TIMEOUT_S)
            if _sandbox_dead(launcher):
                raise LabCliError(
                    f"reference: sandbox death launching install attempt "
                    f"{attempts}: "
                    f"{(launcher.stderr or launcher.stdout or '').strip()[-300:]} "
                    "(clean abort — a fresh run gets a fresh 60-min "
                    "window)")
            install_ok = False
            outcome_seen = False
            code = -1
            deadline = self._monotonic() + OUTCOME_WINDOW_S
            while True:
                poll = provider.execute(
                    env_id, "cat /root/install.out 2>&1 | tail -4",
                    timeout=POLL_TIMEOUT_S)
                if _sandbox_dead(poll):
                    raise LabCliError(
                        f"reference: sandbox death during install attempt "
                        f"{attempts} outcome poll: "
                        f"{(poll.stderr or poll.stdout or '').strip()[-300:]} "
                        "(clean abort)")
                tail = poll.stdout or ""
                if "EXIT_" in tail:
                    match = re.search(r"EXIT_(-?\d+)", tail)
                    code = int(match.group(1)) if match else -1
                    full = provider.execute(
                        env_id, "cat /root/install.out 2>&1",
                        timeout=POLL_TIMEOUT_S)
                    body = (full.stdout or tail).strip()
                    install_ok = "Success" in body and code == 0
                    outcome_seen = True
                    break
                if self._monotonic() >= deadline:
                    break
                self._sleep(OUTCOME_POLL_S)
            if outcome_seen:
                if install_ok:
                    emit(f"  reference: install attempt {attempts}: "
                         "Success")
                else:
                    # run-001 lesson, applied to the ladder (2026-09-24
                    # forensics: the adb stderr explaining the failure is
                    # in install.out — emit it, verdicts that die on
                    # stderr are invisible otherwise)
                    body_tail = " | ".join(
                        body.strip().splitlines()[-2:])[-160:]
                    emit(f"  reference: install attempt {attempts}: "
                         f"failed (exit {code}) — {body_tail}")
            else:
                # probe-23 (2026-09-24): under degraded TCG the package-
                # manager COMMIT can land minutes before the adb client
                # stream returns (observed live: the registry showed the
                # package ~2 min before the outcome-window edge while adb
                # was still streaming). Check the registry at the window
                # edge BEFORE declaring the timeout — a committed package
                # IS success; killing the lingering adb stream afterwards
                # is harmless (the commit is durable).
                reg = provider.execute(
                    env_id,
                    f"{adb} shell pm path {REFERENCE_PACKAGE} 2>&1 "
                    "| head -1",
                    timeout=POLL_TIMEOUT_S)
                if _sandbox_dead(reg):
                    raise LabCliError(
                        "reference: sandbox death at window-edge registry "
                        f"check: "
                        f"{(reg.stderr or reg.stdout or '').strip()[-300:]} "
                        "(clean abort)")
                if (reg.stdout or "").strip().startswith("package:/"):
                    provider.execute(
                        env_id, "pkill -f 'adb install' 2>&1; echo KILLED",
                        timeout=POLL_TIMEOUT_S)
                    emit(f"  reference: install attempt {attempts}: "
                         "Success (probe-23: registry-verified at window "
                         "edge, lingering adb stream killed)")
                    return
                # run-005 lesson (a): the outcome window elapsed with no
                # EXIT marker — kill the zombie stream (cheap, no hang)
                provider.execute(
                    env_id, "pkill -f 'adb install' 2>&1; echo KILLED",
                    timeout=POLL_TIMEOUT_S)
                emit(f"  reference: install attempt {attempts}: "
                     "outcome-window timeout (zombie killed)")
            if install_ok:
                return
            # run-003 lesson: the service may be down MID-STREAM —
            # re-settle before the next attempt (probe 17: attempt 2)
            settled, last = self._wait_package_service(
                provider, env_id, adb, SERVICE_SETTLE_MAX_PROBES,
                SERVICE_SETTLE_POLL_S, emit)
            if not settled:
                emit(f"  reference: package service still down after "
                     f"{SERVICE_SETTLE_MAX_PROBES} probes (last: "
                     f"{last[:120]}) — retrying anyway (bounded)")
            self._sleep(RETRY_BACKOFF_S)
        raise LabCliError(
            f"reference: install failed after {INSTALL_MAX_ATTEMPTS} "
            "bounded attempts (GMS-churn lottery not won — see "
            "lab/substrate/REFERENCE-INSTALL-2026-09-22.md)")

    def _registry_verify(self, provider: Any, env_id: str, adb: str,
                         emit: Callable[[str], None]) -> None:
        """probe-15 lesson: an adb 'Success' does NOT prove the package
        landed in the registry (the commit phase can die silently after
        a broken pipe) — verify `pm path` explicitly."""
        res = provider.execute(
            env_id, f"{adb} shell pm path {REFERENCE_PACKAGE} 2>&1",
            timeout=REGISTRY_TIMEOUT_S)
        if _sandbox_dead(res):
            raise LabCliError(
                "reference: sandbox death at registry check: "
                f"{(res.stderr or res.stdout or '').strip()[-300:]}")
        if not (res.stdout or "").strip().startswith("package:/"):
            raise LabCliError(
                "reference: install Success but the package did not land "
                "in the registry (pm path: "
                f"{(res.stdout or res.stderr or '').strip()[:200]}) — "
                "probe-15 silent-commit-death")
        emit(f"  reference: package registered ({REFERENCE_PACKAGE})")

    def _resolve_launcher(self, provider: Any, env_id: str, adb: str,
                          emit: Callable[[str], None]) -> str:
        """probe-17 step 4: resolve the launcher component DYNAMICALLY —
        never trust statically-derived component names. Recorded as
        observed evidence; since CAMSCAN-010A this resolved component
        is the launch step's component source (the handle carries the
        provision-time resolution; the launch step re-resolves through
        this same probe only when that resolution came back empty)."""
        res = provider.execute(
            env_id,
            f"{adb} shell cmd package resolve-activity --brief "
            f"-c android.intent.category.LAUNCHER {REFERENCE_PACKAGE} "
            f"| tail -2",
            timeout=LAUNCHER_RESOLVE_TIMEOUT_S)
        component = ""
        for line in reversed((res.stdout or "").splitlines()):
            line = line.strip()
            if line and "/" in line:
                component = line
                break
        if component:
            emit(f"  reference: launcher resolved dynamically → {component}")
        else:
            emit("  reference: launcher resolution empty — the launch "
                 "verb resolves at launch time (monkey)")
        return component

    def _grant_preconditions(self, provider: Any, env_id: str, adb: str,
                             scenario: Scenario,
                             emit: Callable[[str], None]) -> None:
        """Precondition permission baselines (S004-class preconditions):
        `pm grant <pkg> <perm>` — best-effort with a loud warning (the
        runtime-dialog flow is scenario S003's own grant step)."""
        for pre in scenario.preconditions:
            permission = _PRECONDITION_PERMISSIONS.get(pre)
            if not permission:
                continue
            res = provider.execute(
                env_id,
                f"{adb} shell pm grant {REFERENCE_PACKAGE} {permission}",
                timeout=GRANT_TIMEOUT_S)
            if res.exit_code == 0:
                emit(f"  reference: precondition granted {permission}")
            else:
                emit(f"  reference: precondition grant {permission} "
                     f"failed (best-effort): "
                     f"{(res.stderr or '').strip()[:160]}")

    @staticmethod
    def _destroy_quietly(provider: Any, env_id: str,
                         emit: Callable[[str], None]) -> None:
        """Best-effort destroy on a failed provision — a paid sandbox is
        never leaked by a raising install path (the runner's teardown
        only runs after a successful provision; provision cleans up its
        own failures)."""
        try:
            provider.destroy(env_id)
        except Exception as exc:  # noqa: BLE001 — never raises
            emit(f"  reference: destroy after failure failed ({exc})")

    # ---------------------------------------------------------------- verbs

    def _post_launch(self, bridge: Any, native: _Native,
                     step_timeout: int,
                     emit: Callable[[str], None]) -> None:
        """probe-17 steps 5-6: wait for the app's process (ndk_translation
        under TCG is slow), then run the SystemUI-ANR dismissal ladder —
        the first launch of a heavy app trips a SystemUI ANR dialog over
        the splash; the app task stays alive behind it.

        CAMSCAN-010D budget governor: the wait is capped not only by
        PROCESS_WAIT_ROUNDS but by the sandbox's remaining lifetime —
        before each round the remaining budget (against
        E2B_TOTAL_LIFETIME_CAP_S via _Native.sandbox_t0) is checked,
        and when it drops below LAUNCH_PROC_BUDGET_RESERVE_S the wait
        is cut EARLY with a timestamped line so the evidence phases
        (ANR ladder + remaining steps + captures + facts + logcat)
        get the reserve. The ANR ladder itself still runs after the
        cut — it is REQUIRED for subsequent steps (the SystemUI
        dialog blocks the app UI). The 20260925T110418Z-S001-live
        killer was exactly this class: this 400 s wait ran at sandbox
        age ~3200→3600 s and starved the evidence phases of the
        sandbox's final minutes."""
        provider, env_id, adb = native.provider, native.env_id, native.adb
        proc_line = ""
        wait_cut = False
        for _ in range(PROCESS_WAIT_ROUNDS):
            remaining = self._sandbox_budget_remaining_s(native)
            if remaining < LAUNCH_PROC_BUDGET_RESERVE_S:
                # CAMSCAN-010D: cut the wait — the reserve is the
                # evidence phases' money, and the ANR ladder below
                # still runs (required for subsequent steps).
                age = E2B_TOTAL_LIFETIME_CAP_S - remaining
                emit("  reference: " + time.strftime(
                    "%H:%M:%S ", time.gmtime(self._wall_time()))
                    + f"process-wait cut at age {age:.0f}s — reserving "
                    f"{LAUNCH_PROC_BUDGET_RESERVE_S}s for the evidence "
                    f"phases — E2B Hobby total-lifetime cap "
                    f"{E2B_TOTAL_LIFETIME_CAP_S}s")
                wait_cut = True
                break
            self._sleep(PROCESS_WAIT_S)
            res = provider.execute(
                env_id,
                f"{adb} shell ps -A | grep {REFERENCE_PACKAGE} | head -1",
                timeout=POLL_TIMEOUT_S)
            if _sandbox_dead(res):
                emit("  reference: sandbox death while waiting for the "
                     "app process (continuing — evidence first, teardown "
                     "owns the cleanup)")
                break
            if REFERENCE_PACKAGE in (res.stdout or ""):
                proc_line = (res.stdout or "").strip().splitlines()[0]
                break
        if proc_line:
            fields = proc_line.split()
            pid = fields[1] if len(fields) > 1 else ""
            emit(f"  reference: app process up (pid {pid})")
        elif not wait_cut:
            # (a cut already told the story — the wait ended on the
            # budget governor, not on absence)
            emit("  reference: app process not observed within "
                 f"{PROCESS_WAIT_ROUNDS * PROCESS_WAIT_S}s (TCG slow "
                 "path)")
        self._anr_ladder(bridge, emit)

    def _anr_ladder(self, bridge: Any,
                    emit: Callable[[str], None]) -> None:
        """The proven dump-tap ladder (observe.py 6b): uiautomator dump →
        find the Wait button's bounds → tap its center; up to 3 rounds;
        last resort the camera-campaign-proven (540, 1244)."""
        attempts = 0
        dismissed = False
        for _ in range(ANR_MAX_ROUNDS):
            dump = bridge.ui_dump()
            match = _ANR_WAIT_RE.search(dump.text or "")
            if not match:
                dismissed = True   # no Wait button — no dialog present
                break
            x = (int(match.group(1)) + int(match.group(3))) // 2
            y = (int(match.group(2)) + int(match.group(4))) // 2
            bridge.tap(x, y)
            attempts += 1
            emit(f"  reference: ANR Wait dismissed at ({x},{y}) "
                 f"[round {attempts}]")
            self._sleep(ANR_ROUND_SETTLE_S)
        if not dismissed and attempts >= ANR_MAX_ROUNDS:
            bridge.tap(*ANR_FALLBACK_TAP)
            emit(f"  reference: ANR Wait dismissed at fallback "
                 f"{ANR_FALLBACK_TAP} (camera-campaign-proven)")
        elif dismissed and attempts:
            emit("  reference: ANR dialog dismissed")

    def _onboarding_complete(self, bridge: Any, native: _Native, app: str,
                             step_timeout: int,
                             emit: Callable[[str], None]) -> bool:
        """CAMSCAN-010F — the complete-onboarding verb: dump-first
        discovery through the shared ladder
        (:func:`onboarding_discovery_loop` — the _anr_ladder shape).
        The 20260925T180736Z-S002-live attempt-1 killer was the old
        mapping's registry tap: "unknown semantic target 'next_button'
        (looked in app scope 'com.intsig.camscanner' and global; known
        ids there: permission_allow, permission_allow_this_time,
        permission_deny)" — an id that cannot exist in the reference
        scope, ever (discovery is runtime, never registry invention).
        The step's screenshot+dump evidence pair is captured by the
        existing per-step observe machinery; budget exhaustion is the
        honest False (the driver's existing failure path does the
        rest)."""
        return onboarding_discovery_loop(
            bridge, step_timeout=step_timeout, sleep=self._sleep,
            emit=emit, label="reference")

    # ------------------------------------------- launch step (CAMSCAN-010A)

    def _launch_call(self, bridge: Any, native: _Native, app: str,
                     step_timeout: int,
                     emit: Callable[[str], None]) -> tuple[bool, list[str]]:
        """One launch verb call through the probe-26..31 machinery —
        the proven observe.py first-run launch path this driver never
        received (the 2026-09-25 S001 run died at STEP 01 on the
        probe-24 single-shot monkey form while the driver already held
        the resolved component).

        Order: component (probe-26: the handle's provision-time
        resolution, re-resolved only when empty) → dex2oat quiescence
        gate (probe-29) → the probe-27 retry ladder (component form:
        background ``am start -W -n`` + EXIT_n marker; monkey ONLY as
        the no-component fallback) → the patient process poll
        (probe-24 budget, probe-25 death forensics, probe-30 blindness
        + am-confirmed precedence, probe-31 cap). Returns (ok,
        launch_diag): the diag carries every timestamped (probe-29
        HH:MM:SS) ladder line and, on failure, the death forensics —
        the caller surfaces them in problems/reason + the action
        trace."""
        provider, env_id, adb = native.provider, native.env_id, native.adb
        diag: list[str] = []

        def _ldiag(message: str) -> None:
            # probe-29: HH:MM:SS prefixes on every ladder diagnostic
            # line — the attempt-2 postmortem had to reconstruct the
            # timeline from file mtimes; timestamps make the next
            # postmortem a read, not an inference. (UTC — host-
            # independent, unlike observe.py's local time; the wall
            # clock is constructor-injected for the hermetic tests.)
            diag.append(time.strftime(
                "%H:%M:%S ", time.gmtime(self._wall_time())) + message)

        # probe-26: launch via the PROVEN form — `am start -W -n
        # <resolved component>` (the exact form bridge.launch(app,
        # activity=component) drives), never component-less monkey
        # (it only proves event INJECTION; under ANR churn the intent
        # sits unprocessed while the resolved component is already in
        # hand). Component source: the handle's provision-time
        # resolution; re-resolve ONLY when it is empty.
        component = native.launcher_component
        if not component:
            component = self._resolve_launcher(provider, env_id, adb, emit)
        # the bridge's app scope (launch()'s own side effect on the
        # monkey path) is preserved on the component path through the
        # bridge's public setter — later semantic-target verbs resolve
        # against it
        bridge.set_app(app)

        # probe-29: dex2oat quiescence gate BEFORE the ladder
        self._dex2oat_gate(provider, env_id, adb, _ldiag, emit)

        am_confirmed = ""
        if component:
            emit(f"  reference: launch via am start -W -n {component} "
                 "(ladder)")
            am_confirmed = self._launch_ladder(provider, env_id, adb,
                                               component, _ldiag, emit)
        else:
            emit("  reference: no launcher component resolved — monkey "
                 "fallback (probe-26: single-shot, event injection only)")
            self._monkey_fallback(bridge, app, step_timeout, _ldiag)

        # CAMSCAN-010D: the poll's budget governor reads the remaining
        # sandbox lifetime against _Native.sandbox_t0 /
        # E2B_TOTAL_LIFETIME_CAP_S through a remaining-seconds callable
        # (None would mean ungoverned; the live call always threads it).
        ok = self._launch_process_poll(
            provider, env_id, adb, am_confirmed, _ldiag, emit,
            budget_remaining_s=lambda: self._sandbox_budget_remaining_s(
                native))
        return ok, diag

    def _dex2oat_gate(self, provider: Any, env_id: str, adb: str,
                      _ldiag: Callable[[str], None],
                      emit: Callable[[str], None]) -> None:
        """probe-29: the install's background dexopt (verify filter on
        a 162 MB base + 59 MB arm64 split under ~130 MB free) steals
        the exact CPU the cold start needs — gate the ladder on
        dex2oat quiescence, bounded (
        DEX2OAT_GATE_MAX_POLLS x DEX2OAT_GATE_POLL_S ≈ 4 min); proceed
        on gate failure (a read that errors or comes back blind also
        proceeds — the gate is best-effort, never a verdict)."""
        for _ in range(DEX2OAT_GATE_MAX_POLLS):
            try:
                dx = provider.execute(
                    env_id, f"{adb} shell ps -A | grep -c dex2oat",
                    timeout=POLL_TIMEOUT_S)
            except Exception:  # noqa: BLE001 — best-effort gate
                return
            if (dx.stdout or "").strip() in ("0", ""):
                return
            self._sleep(DEX2OAT_GATE_POLL_S)
        minutes = DEX2OAT_GATE_MAX_POLLS * DEX2OAT_GATE_POLL_S // 60
        _ldiag(f"dex2oat still running after {minutes} min — proceeding "
               "anyway")
        emit(f"  reference: dex2oat still running after {minutes} min — "
             "proceeding anyway (probe-29: the gate is best-effort)")

    def _launch_ladder(self, provider: Any, env_id: str, adb: str,
                       component: str, _ldiag: Callable[[str], None],
                       emit: Callable[[str], None]) -> str:
        """probe-27: the launch retry ladder — the launch binder call
        dies EXACTLY like the install one ('cmd: Failure calling
        service activity: Broken pipe (32)') while the single-shot
        launch burned whole runs; mirror the install ladder: am start
        in the BACKGROUND with an EXIT_n marker (a blocked am start
        costs one bounded window, not an 8-min hang), a bounded
        outcome window per attempt, the activity-service gate +
        probe-29 gap-cadence settle between attempts,
        LAUNCH_MAX_ATTEMPTS attempts, 'brought to the front' counts as
        up (the task already exists); ladder exhaustion falls through
        to the caller's patient poll + death forensics.

        probe-28 doctrine: a single in-band wrapper transient
        (request_timeout text arriving in stdout/stderr, the LAUNCHED
        echo missing) NEVER aborts the ladder — fall through to the
        poll (it either finds launch.out — the wrapper DID run
        server-side despite the client-side timeout — or burns one
        bounded window; only a genuinely dead activity service aborts
        the ladder).

        Returns the probe-30 am_confirmed tail ("" when am never
        reported our activity up)."""
        am_confirmed = ""
        for attempt in range(1, LAUNCH_MAX_ATTEMPTS + 1):
            # background am start with EXIT marker (the install
            # ladder's probe-21b pattern, applied to the launch binder)
            bg = None
            try:
                bg = provider.execute(env_id, f"""
rm -f /root/launch.out
({adb} shell am start -W -n {component} > /root/launch.out 2>&1; echo "EXIT_$?" >> /root/launch.out) &
echo LAUNCHED
""", timeout=LAUNCH_LAUNCHER_TIMEOUT_S)
            except Exception as exc:  # noqa: BLE001 — transport death ≠ verdict
                _ldiag(f"[attempt {attempt}] TRANSPORT-DEAD: "
                       f"{type(exc).__name__}: {exc}"[:200])
            if bg is not None and "LAUNCHED" not in (bg.stdout or ""):
                # probe-28: in-band wrapper transient — record it, fall
                # through to the poll (never abort the ladder)
                _ldiag(f"[attempt {attempt}] launcher-echo missing: "
                       f"{((bg.stdout or bg.stderr) or '').strip()[-150:]!r}")
            poll = None
            outcome_seen = False
            body = ""
            exit_code = -1
            deadline = self._monotonic() + LAUNCH_OUTCOME_WINDOW_S
            while self._monotonic() < deadline:
                self._sleep(OUTCOME_POLL_S)
                try:
                    poll = provider.execute(
                        env_id, "cat /root/launch.out 2>&1 | tail -6",
                        timeout=POLL_TIMEOUT_S)
                except Exception as exc:  # noqa: BLE001 — poll transient
                    _ldiag(f"[attempt {attempt}] POLL-DEAD: "
                           f"{type(exc).__name__}"[:120])
                    poll = None
                if poll is None:
                    break
                if "EXIT_" in (poll.stdout or ""):
                    match = re.search(r"EXIT_(-?\d+)", poll.stdout or "")
                    exit_code = int(match.group(1)) if match else -1
                    try:
                        full = provider.execute(
                            env_id, "cat /root/launch.out 2>&1",
                            timeout=POLL_TIMEOUT_S)
                        body = (full.stdout or poll.stdout or "").strip()
                    except Exception:  # noqa: BLE001
                        body = (poll.stdout or "").strip()
                    outcome_seen = True
                    break
            if outcome_seen:
                _ldiag(f"[attempt {attempt}] exit={exit_code} "
                       f"stdout={body[-250:]!r}")
                # probe-29: 'Error type 3 / does not exist' means the
                # am tool's OWN PackageManager query came back blind —
                # while resolve-activity had answered minutes earlier
                # and the activity-service gate never dropped (the PM
                # binder endpoint flaps on the same minutes cadence as
                # the install broken-pipe bursts). Capture the
                # blind-state evidence so the next postmortem can see
                # it directly.
                if "does not exist" in body or "Error type 3" in body:
                    for label, cmd in (
                        ("pm-path",
                         (f"{adb} shell pm path {REFERENCE_PACKAGE} "
                          f"| head -2")),
                        ("resolve-again",
                         (f"{adb} shell cmd package resolve-activity "
                          f"--brief -a android.intent.action.MAIN "
                          f"-c android.intent.category.LAUNCHER "
                          f"{REFERENCE_PACKAGE} | tail -1")),
                        ("activity-table",
                         (f"{adb} shell dumpsys package "
                          f"{REFERENCE_PACKAGE} | grep -m2 mainactivity")),
                    ):
                        try:
                            res = provider.execute(env_id, cmd,
                                                   timeout=POLL_TIMEOUT_S)
                            _ldiag(f"[attempt {attempt}] blind-evidence "
                                   f"{label}="
                                   f"{(res.stdout or '').strip()[:120]!r}")
                        except Exception:  # noqa: BLE001
                            _ldiag(f"[attempt {attempt}] blind-evidence "
                                   f"{label}=EXCEPTION")
                # probe-30: am start -W's own output is AUTHORITATIVE
                # launch evidence — 'Status: ok' with 'Activity:
                # <pkg>/...' reports the AMS's own state (the app's
                # activity cannot be top-most without its process).
                # Track it: the patient ps poll below can be
                # transport-blind and must not veto the AMS.
                if "Status: ok" in body:
                    if f"Activity: {REFERENCE_PACKAGE}" in body:
                        am_confirmed = body[-400:]
                    emit(f"  reference: launch attempt {attempt}: am start "
                         "ok (Status: ok)")
                    break
                if "brought to the front" in body:
                    _ldiag(f"[attempt {attempt}] task already fronted — "
                           "treating as up")
                    emit(f"  reference: launch attempt {attempt}: task "
                         "already fronted (up)")
                    break
                emit(f"  reference: launch attempt {attempt}: failed "
                     f"(exit {exit_code}) — "
                     f"{' | '.join(body.splitlines()[-2:])[-160:]}")
            elif poll is not None:
                # bounded window elapsed with no EXIT marker — kill
                # the blocked am start (a hang costs one window, not
                # 8 min)
                try:
                    provider.execute(
                        env_id, "pkill -f 'am start' 2>&1; echo KILLED",
                        timeout=POLL_TIMEOUT_S)
                except Exception as exc:  # noqa: BLE001 — best-effort kill
                    _ldiag(f"[attempt {attempt}] pkill-dead: "
                           f"{type(exc).__name__}")
                _ldiag(f"[attempt {attempt}] outcome-window-timeout "
                       "(blocked am start)")
                emit(f"  reference: launch attempt {attempt}: outcome-window "
                     "timeout (blocked am start killed)")
            # gated backoff: wait for the activity service to respond
            # again (am get-current-user isdigit check), then the
            # probe-29 gap-cadence settle
            svc_up = False
            for _ in range(LAUNCH_GATE_MAX_PROBES):
                try:
                    gate = provider.execute(
                        env_id,
                        f"{adb} shell am get-current-user 2>&1 | head -1",
                        timeout=POLL_TIMEOUT_S)
                    if ((gate.stdout or "").strip()).isdigit():
                        svc_up = True
                        break
                except Exception as exc:  # noqa: BLE001 — best-effort gate
                    _ldiag(f"[attempt {attempt}] gate-dead: "
                           f"{type(exc).__name__}")
                self._sleep(LAUNCH_GATE_POLL_S)
            _ldiag(f"[attempt {attempt}] activity-service gate: "
                   f"{'up' if svc_up else 'down'}")
            if not svc_up:
                emit("  reference: launch ladder: activity service down "
                     "after the gate — falling through to the patient "
                     "poll (probe-27)")
                break
            self._sleep(LAUNCH_SETTLE_S)
        return am_confirmed

    def _monkey_fallback(self, bridge: Any, app: str, step_timeout: int,
                         _ldiag: Callable[[str], None]) -> None:
        """probe-26's fallback ONLY: no component resolved — the
        bridge's component-less monkey form (its existing semantics:
        exit 0 + the injected-events confirmation). Monkey proves
        event INJECTION, never that the intent was processed; the
        caller's patient process poll decides the verdict."""
        result = bridge.launch(app, timeout=step_timeout)
        if result.ok:
            _ldiag("[monkey] events injected (bridge-confirmed)")
        else:
            _ldiag(f"[monkey] failed: {(result.error or '')[-160:]}")

    def _launch_process_poll(self, provider: Any, env_id: str, adb: str,
                             am_confirmed: str,
                             _ldiag: Callable[[str], None],
                             emit: Callable[[str], None], *,
                             budget_remaining_s: Callable[[], float]
                             | None = None) -> bool:
        """The patient post-ladder process poll (observe.py's
        first-run poll, the shared verdict machinery for the ladder
        AND the monkey fallback):

        - probe-24 budget: PROCESS_WAIT_ROUNDS x PROCESS_WAIT_S ≈
          6.7 min (the night-TCG cold start took > 120 s; 120 s was
          NOT enough);
        - probe-30 blindness: a ps read whose exit is -1 or whose text
          carries the SDK timeout signature is NOT an absence
          observation — skip it, count it; and when am already
          confirmed the launch ('Status: ok' + 'Activity: <pkg>/…')
          its evidence outranks the (blind) ps poll — the AMS itself
          reported the app top-most, and a blind window cannot veto
          it;
        - probe-31 cap: when am already confirmed, the poll is a
          nice-to-have — cap it at LAUNCH_PROC_POLL_CAP_CONFIRMED ≈
          2 min and leave the transport's minutes for the evidence
          phases;
        - CAMSCAN-010D budget governor: before each round the
          remaining sandbox lifetime (``budget_remaining_s``, a
          remaining-seconds callable threaded from _launch_call
          against _Native.sandbox_t0 / E2B_TOTAL_LIFETIME_CAP_S;
          None = ungoverned) is checked, and when it drops below
          LAUNCH_PROC_BUDGET_RESERVE_S the poll cuts early on BOTH
          paths — am-confirmed (the AMS evidence already won: spend
          the reserve on the evidence phases) and unconfirmed (the
          honest-fail path: the probe-25 death forensics run and the
          step fails — the 20260925T110418Z-S001-live postmortem: the
          unconfirmed 400 s path burned the sandbox's final minutes
          on a run that would honestly fail);
        - probe-25 death forensics on failure: canary echo (is the
          adb stream alive at all?), system ps head (is the process
          list itself listing?), logcat tail (system_server ANR /
          suicide screams here) — infra death vs app death must not
          look alike.
        """
        proc_line = ""
        n_blind = 0
        n_clean_absent = 0
        poll_cap = (LAUNCH_PROC_POLL_CAP_CONFIRMED if am_confirmed
                    else PROCESS_WAIT_ROUNDS)
        for _ in range(poll_cap):
            # CAMSCAN-010D: the total-lifetime budget governor — the
            # remaining-seconds callable (None = ungoverned: +inf,
            # never cuts) is checked BEFORE every round; the age in
            # the cut line is derived from the callable's remaining
            # value against E2B_TOTAL_LIFETIME_CAP_S.
            remaining = (budget_remaining_s() if budget_remaining_s
                         is not None else float("inf"))
            if remaining < LAUNCH_PROC_BUDGET_RESERVE_S:
                age = E2B_TOTAL_LIFETIME_CAP_S - remaining
                _ldiag(f"process-poll cut at age {age:.0f}s — reserving "
                       f"{LAUNCH_PROC_BUDGET_RESERVE_S}s for the evidence "
                       f"phases — E2B Hobby total-lifetime cap "
                       f"{E2B_TOTAL_LIFETIME_CAP_S}s")
                emit(f"  reference: process-poll cut at age {age:.0f}s — "
                     f"reserving {LAUNCH_PROC_BUDGET_RESERVE_S}s for the "
                     "evidence phases — E2B Hobby total-lifetime cap "
                     f"{E2B_TOTAL_LIFETIME_CAP_S}s")
                break
            self._sleep(PROCESS_WAIT_S)
            ps = provider.execute(
                env_id,
                f"{adb} shell ps -A | grep {REFERENCE_PACKAGE} | head -1",
                timeout=LAUNCH_PS_TIMEOUT_S)
            last_ps = (ps.stdout or "").strip()[:200]
            last_ps_err = (getattr(ps, "stderr", "") or "").strip()[-200:]
            last_ps_exit = getattr(ps, "exit_code", None)
            if (last_ps_exit == -1
                    or "timeout" in last_ps_err.lower()
                    or "You can modify" in last_ps
                    or "request_timeout" in last_ps):
                n_blind += 1
                continue
            if REFERENCE_PACKAGE in (ps.stdout or ""):
                proc_line = last_ps.splitlines()[0]
                break
            n_clean_absent += 1
        if proc_line:
            _ldiag(f"process poll: app process up "
                   f"({proc_line.split()[1] if len(proc_line.split()) > 1
                       else proc_line[:24]})")
            return True
        if am_confirmed:
            # probe-30: the AMS itself reported the app top-most with
            # Status: ok — a blind ps window cannot overrule it.
            # Record the am evidence and continue to the evidence
            # phases (best-effort: if the transport stays blind the
            # captures fail and the run FAILs honestly; if it
            # recovers, the run earns its PASS).
            _ldiag(f"process poll: {n_blind} blind / {n_clean_absent} "
                   "clean-absent reads — am-confirmed evidence "
                   "outranks the blind ps poll")
            emit("  reference: launch am-confirmed (Status: ok, top-most "
                 f"{REFERENCE_PACKAGE} instance) — blind ps overruled "
                 "(probe-30)")
            return True
        # probe-25: death forensics — infra death vs app death must
        # not look alike
        for label, cmd, budget in (
            ("canary", f"{adb} shell echo __canary_ok__", POLL_TIMEOUT_S),
            ("system-ps-head", f"{adb} shell ps -A | head -3",
             POLL_TIMEOUT_S),
            ("logcat-tail",
             f"{adb} shell logcat -d -t 200 2>&1 | tail -15",
             DIAG_TIMEOUT_S),
        ):
            try:
                res = provider.execute(env_id, cmd, timeout=budget)
                _ldiag(f"death-forensics {label}="
                       f"{(res.stdout or '').strip()[:120]!r} "
                       f"(exit {getattr(res, 'exit_code', None)})")
            except Exception as exc:  # noqa: BLE001 — best-effort forensics
                _ldiag(f"death-forensics {label}=EXCEPTION "
                       f"({type(exc).__name__})")
        emit("  reference: launch failed — ladder + death forensics "
             "recorded (see problems / run-metadata action trace)")
        return False

    def _invoke_plan(self, bridge: Any, plan: StepPlan, app: str,
                     step_timeout: int, native: _Native,
                     emit: Callable[[str], None]) -> tuple[bool, list[str]]:
        """Dispatch one planned step's verb calls (per-step budget).

        Launch steps route through the CAMSCAN-010A ladder
        (:meth:`_launch_call` — the probe-26..31 machinery; component
        form preferred, monkey ONLY as the no-component fallback);
        every other verb is unchanged. Returns (ok, launch_diag): the
        diag is the timestamped ladder forensics for launch steps,
        empty otherwise."""
        if not isinstance(plan, StepPlan):
            raise TypeError(
                f"step plans must be StepPlan instances "
                f"(got {type(plan).__name__}; runner bug)")
        ok = True
        launch_diag: list[str] = []
        for call in plan.calls:
            params = {k: (app if v == APP else v)
                      for k, v in call.params.items()}
            verb = call.verb
            if verb == "observe":
                # refresh the dump cache — the per-step capture below
                # pairs screenshot + ui dump as the step's evidence
                result = bridge.ui_dump(timeout=step_timeout)
                ok = ok and result.ok
                continue
            if verb == "launch":
                # CAMSCAN-010A: the probe-26..31 launch machinery
                # (component launch via am start -W -n under the
                # probe-27 ladder; monkey ONLY when no component
                # resolved — the bridge verb's existing semantics)
                launched, launch_diag = self._launch_call(
                    bridge, native, app, step_timeout, emit)
                ok = ok and launched
                continue
            if verb == "onboarding_complete":
                # CAMSCAN-010F: dump-first onboarding discovery — the
                # reference registry scope is RESERVED-EMPTY by design
                # (ids come from observed live dumps, never invention),
                # so a registry tap can never resolve here; discovery
                # walks the live dumps (permission-aware).
                ok = ok and self._onboarding_complete(
                    bridge, native, app, step_timeout, emit)
                continue
            if verb == "tap_semantic":
                result = bridge.tap_semantic(params["target"],
                                             timeout=step_timeout)
            elif verb == "swipe":
                result = bridge.swipe(params["x1"], params["y1"],
                                      params["x2"], params["y2"],
                                      params.get("ms", 300),
                                      timeout=step_timeout)
            elif verb == "type_text":
                result = bridge.type_text(params["text"],
                                          timeout=step_timeout)
            elif verb in ("back", "home", "wake"):
                result = getattr(bridge, verb)(timeout=step_timeout)
            elif verb == "key":
                result = bridge.key(params["code"], timeout=step_timeout)
            elif verb == "grant":
                result = bridge.grant(app, params["permission"],
                                      timeout=step_timeout)
            elif verb == "wait_for":
                result = bridge.wait_idle(timeout=step_timeout)
            else:  # pragma: no cover — mapping-table bug, fail loud
                raise LabCliError(
                    f"live driver cannot dispatch verb {verb!r}")
            ok = ok and result.ok
        return ok, launch_diag

    def _capture_step(self, bridge: Any, subject_dir: Path, index: int,
                      action: str) -> None:
        name = f"{index:02d}-{action}"
        shot = bridge.screenshot()
        if shot.ok and shot.data:
            (subject_dir / "screenshots" / f"{name}.png").write_bytes(
                shot.data)
        dump = bridge.ui_dump()
        if dump.ok and dump.text:
            (subject_dir / "ui" / f"{name}.xml").write_text(
                dump.text, encoding="utf-8")

    def _package_facts(self, provider: Any, env_id: str, adb: str,
                       package: str, emit: Callable[[str], None],
                       *, phase: str,
                       seed: dict[str, Any] | None = None) \
            -> tuple[dict[str, Any], list[str], bool]:
        """``{adb} shell dumpsys package`` facts (versionName/versionCode)
        — discovered, never statically derived; blindness-tolerant
        (CAMSCAN-010B, the probe-33 port of observe.py commit eeeeb0b).
        CAMSCAN-010E: the reads carry the ``{adb} shell `` prefix —
        a bare Linux-side ``dumpsys`` is deterministically
        "command not found" (the 2026-09-25 16:34 run postmortem,
        see _read_package_fact). CAMSCAN-010C: the
        same machinery now serves BOTH read stages — the install-time
        stash read (phase ``install-time``, invoked by provision right
        after the registry verify) and the late execute-time fallback
        (phase ``late``, invoked only for facts the stash could not
        land).

        The 20260925T091135Z-S001-live postmortem: one blind transport
        read (the post-restore flap class) left version_name EMPTY and
        the evidence-cli bundle rejected the whole finished run. A read
        that errors or answers without the expected ``version*=`` line
        is BLIND, never final (probe-33 doctrine: a blind read is not
        an absence observation); blind reads retry on observe.py's own
        package-facts cadence (PKG_FACTS_MAX_ATTEMPTS x
        PKG_FACTS_SETTLE_S), each recording a timestamped diag line
        (the CAMSCAN-010A _ldiag style; since 010C every diag and
        console line carries its ``phase`` label so the combined
        both-blind failure names both diag sets honestly).
        ``seed`` carries facts another stage already landed (the 010C
        stash seeding the late read) — a landed fact is never re-read.

        CAMSCAN-010D dead-sandbox fast-abort: a read whose result
        carries a SANDBOX-DEATH signature (``_sandbox_dead`` — the
        "sandbox timeout" marker is the exact signature the
        20260925T110418Z-S001-live postmortem observed on the
        expired-sandbox reads: exit=-1, ~0.5 s per read, stderr tail
        "…ling '.set_timeout' on the sandbox with the desired
        timeout.") is NOT a probe-33 blind transient — the retries
        abort AT ONCE (run-005 lesson b: never hammer a corpse) and
        the third return element flags the expiry so the caller's
        honest-fail reason names it with age context. The
        combined-diagnostics doctrine keeps covering genuinely blind
        (alive-sandbox) reads.

        Returns ``(facts, diag, sandbox_expired)``; the caller fails
        the run honestly — readable reason naming the blind reads (or
        the expiry) — when the facts do not land.
        """
        facts: dict[str, Any] = dict(seed or {})
        diag: list[str] = []
        sandbox_expired = False

        def _pdiag(message: str) -> None:
            # the CAMSCAN-010A _ldiag style (probe-29): HH:MM:SS prefix
            # on every diagnostic line — the next postmortem is a read,
            # not an inference. (UTC; the wall clock is
            # constructor-injected so the hermetic tests stay
            # deterministic — pinned 00:00:00.)
            diag.append(time.strftime(
                "%H:%M:%S ", time.gmtime(self._wall_time())) + message)

        for attempt in range(1, PKG_FACTS_MAX_ATTEMPTS + 1):
            blind: list[str] = []
            for fact_key, marker in (("version_name", "versionName"),
                                     ("version_code", "versionCode")):
                if fact_key in facts:
                    # landed on an earlier attempt or seeded by the
                    # other stage (the 010C stash) — never re-read
                    continue
                reason, dead = self._read_package_fact(
                    provider, env_id, adb, package, marker, fact_key,
                    facts)
                if dead:
                    # CAMSCAN-010D fast-abort: the sandbox itself is
                    # dead (the '.set_timeout'-advice rejection the
                    # expired Hobby sandbox serves) — NOT a probe-33
                    # blind transient: stop retrying at once (run-005
                    # lesson b: never hammer a corpse) and let the
                    # caller fail honestly naming the expiry.
                    _pdiag(f"[{phase} attempt {attempt}] {marker} read "
                           f"DEAD — sandbox expired (E2B Hobby "
                           f"total-lifetime cap "
                           f"{E2B_TOTAL_LIFETIME_CAP_S}s): {reason}")
                    sandbox_expired = True
                    break
                if reason:
                    # probe-33: a read that errors or answers without
                    # the expected version*= line is BLIND, never an
                    # absence observation — one timestamped diag line
                    # per blind read (010C: phase-labeled so the
                    # combined failure names both diag sets)
                    _pdiag(f"[{phase} attempt {attempt}] {marker} read "
                           f"blind ({reason})")
                    blind.append(marker)
            if sandbox_expired:
                emit(f"  reference: {phase} package facts: sandbox-death "
                     f"signature on the read — aborting the reads at "
                     f"once (the sandbox expired against the E2B "
                     f"total-lifetime cap {E2B_TOTAL_LIFETIME_CAP_S}s; "
                     "never hammer a corpse — run-005 lesson b; the "
                     "run fails honestly naming the expiry)")
                break
            if not blind:
                emit(f"  reference: {phase} package facts landed "
                     f"(version_name={facts['version_name']!r}, "
                     f"version_code={facts['version_code']})")
                break
            if attempt < PKG_FACTS_MAX_ATTEMPTS:
                emit(f"  reference: {phase} package facts blind on "
                     f"attempt {attempt}/{PKG_FACTS_MAX_ATTEMPTS} ("
                     + ", ".join(f"{m} unreadable" for m in blind)
                     + f") — settle {PKG_FACTS_SETTLE_S}s and retry "
                     "(probe-33 blindness tolerance)")
                self._sleep(PKG_FACTS_SETTLE_S)
        return facts, diag, sandbox_expired

    @staticmethod
    def _read_package_fact(provider: Any, env_id: str, adb: str,
                           package: str, marker: str, fact_key: str,
                           facts: dict[str, Any]) -> tuple[str, bool]:
        """One ``{adb} shell dumpsys package <package> | grep -m1
        "<marker>"`` read; returns ``(reason, dead)`` — reason ``""``
        when the fact landed (recorded into ``facts``), else the
        blind-read reason; ``dead`` True when the result carries a
        SANDBOX-DEATH signature (CAMSCAN-010D: ``_sandbox_dead`` —
        SANDBOX_DEATH_MARKERS already includes "sandbox timeout", the
        exact signature the 20260925T110418Z-S001-live postmortem
        observed on the expired-sandbox reads; a raising transport
        stays the blind class — the live death signature is the
        CommandResult shape: exit=-1, stderr carrying the
        '.set_timeout'-advice text).

        CAMSCAN-010E: the command carries the ``{adb} shell `` prefix
        — provider.execute runs commands in the sandbox's LINUX bash
        and dumpsys is an ANDROID binary reachable only through the
        adb client, so a bare Linux-side ``dumpsys package …`` is
        ALWAYS ``/bin/bash: line 1: dumpsys: command not found`` —
        deterministic, never the probe-33 blind-transient class (it
        can never succeed on any sandbox). Provenance: the 2026-09-25
        16:34 UTC campaign run (started 16:34:43, sandbox
        e2b-5822073f — the first live run with 010A–010D complete
        upstream): all 16 facts reads (4 install-time reads
        16:53:34–16:54:22 + 4 late reads x 2 facts
        17:30:51–17:31:39) failed with the IDENTICAL signature
        exit=1, stderr tail '/bin/bash: line 1: dumpsys: command not
        found'; the lead audited all 26 provider.execute call sites —
        line 2230 was the sole bare-Android-command bug (the only
        other bare commands are intentional Linux-side pkill of the
        adb CLIENT process)."""
        try:
            res = provider.execute(
                env_id,
                f'{adb} shell dumpsys package {package} '
                f'| grep -m1 "{marker}"',
                timeout=PKG_FACTS_TIMEOUT_S)
        except Exception as exc:  # noqa: BLE001 — blindness, not death
            return ((f"transport error {type(exc).__name__}: "
                     f"{str(exc)[-80:]}"), False)
        if _sandbox_dead(res):
            return ((f"exit={res.exit_code} stderr tail="
                     f"{(res.stderr or res.stdout or '')[-120:]!r}"),
                    True)
        value = ""
        for line in (res.stdout or "").splitlines():
            if f"{marker}=" not in line:
                continue
            tail = line.split(f"{marker}=")[1].split()
            if tail:
                value = tail[0]
                break
        if not value:
            # completed but empty/malformed — the same probe-33
            # blindness class (never an absence observation)
            return ((f"exit={res.exit_code} stdout tail="
                     f"{(res.stdout or '')[-60:]!r} stderr tail="
                     f"{(res.stderr or '')[-60:]!r}"), False)
        if fact_key == "version_code":
            if not value.isdigit():
                return f"unparseable versionCode {value!r}", False
            facts[fact_key] = int(value)
        else:
            facts[fact_key] = value
        return "", False
