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
   an adb Success does NOT prove the package landed).
4. LAUNCH — dynamic launcher resolution (cmd package resolve-activity —
   never statically-derived components), patient process wait
   (ndk_translation interpreting arm64 under TCG is slow), and the
   SystemUI-ANR dismissal ladder after launch (uiautomator dump →
   Wait-button bounds → center tap; the proven (540, 1244) fallback).
5. EXECUTE — scenario steps through adb-bridge verbs, per-step
   screenshot + ui-dump captures, final logcat, discovered dumpsys
   package facts, the EVIDENCE.md single-subject metadata.
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
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import time
import zipfile
from collections.abc import Callable
from pathlib import Path
from typing import Any

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

#: observe.py L529: "up to ~120 s for first process start under TCG"
#: = 12 polls x 10 s (ndk_translation interpreting arm64 is slow).
PROCESS_WAIT_ROUNDS = 12
PROCESS_WAIT_S = 10

#: observe.py L552 / L567: ANR-dismissal ladder — up to 3 dump→tap
#: rounds, 8 s settle between rounds.
ANR_MAX_ROUNDS = 3
ANR_ROUND_SETTLE_S = 8

#: observe.py L570: the camera-campaign-proven Wait-button coordinates
#: (last resort when the dump-tap ladder cannot dismiss the dialog).
ANR_FALLBACK_TAP = (540, 1244)

#: run-005 lesson (b) (observe.py L361-365): these phrases mean the
#: SANDBOX died — not a retryable install failure. Abort at once; a
#: fresh run gets a fresh 60-min window (the E2B hard cap).
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
                 launcher_component: str) -> None:
        self.provider = provider
        self.env_id = env_id
        self.adb = adb
        self.launcher_component = launcher_component


class ReferenceDriver:
    """Live reference-env driver: official CamScanner on the e2b TCG
    substrate, per the probe-17 recipe (module docstring)."""

    slug = "reference-live"

    def __init__(self, apk: "str | Path | None" = None, *,
                 provider: Any = None,
                 sleep: Callable[[float], None] = time.sleep,
                 monotonic: Callable[[], float] = time.monotonic) -> None:
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
        env = provider.provision(spec)
        env_id = env.env_id
        try:
            boot = provider.start(env_id)
            emit(f"  reference: booted {env.avd_name} "
                 f"(boot_s={boot.get('boot_s')})")
            self._boot_settle(provider, env_id, ADB, emit)
            self._apply_dexopt_filter(provider, env_id, ADB, emit)
            remote_files, install_cmd, delivery = self._acquire_bundle(
                provider, env_id, ADB, apk, apk_url, emit)
            self._install_ladder(provider, env_id, ADB, install_cmd, emit)
            self._registry_verify(provider, env_id, ADB, emit)
            launcher = self._resolve_launcher(provider, env_id, ADB, emit)
            self._grant_preconditions(provider, env_id, ADB,
                                      request.scenario, emit)
        except LabCliError:
            self._destroy_quietly(provider, env_id, emit)
            raise
        except Exception as exc:  # noqa: BLE001 — wrap for the CLI layer
            self._destroy_quietly(provider, env_id, emit)
            raise LabCliError(
                f"reference provisioning failed: {type(exc).__name__}: "
                f"{exc}") from exc

        report = provider.report(env_id) or {}
        device = {
            "model": str(report.get("device_model") or spec.device_profile),
            "android_version": str(report.get("android_version") or ""),
            "screen": str(report.get("resolution") or ""),
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
            # discovered at execution time (dumpsys package facts) — the
            # handle carries placeholders that execute() replaces.
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
            native=_Native(provider, env_id, ADB, launcher),
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
            outcome = self._invoke_plan(bridge, plan,
                                        handle.application["package"],
                                        request.scenario.step_timeout_seconds)
            trace.append({
                "t_ms": 1400 * (plan.index - 1),
                "action": plan.step.action,
                "target": (str(plan.step.arg)
                           if plan.step.arg is not None else ""),
                "result": "ok" if outcome else "failed",
            })
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

        logcat = bridge.logcat()
        (subject_dir / "logs" / "logcat.txt").write_text(
            logcat.text or "", encoding="utf-8")

        application = dict(handle.application)
        application.update(self._package_facts(bridge,
                                               application["package"]))
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

    def _acquire_bundle(self, provider: Any, env_id: str, adb: str,
                        apk: "Path | None", apk_url: str,
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
        outcome window; failed attempts are separated by package-service
        re-settle probes; sandbox death aborts cleanly; Success is
        followed by the caller's explicit registry verification."""
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
                emit(f"  reference: install attempt {attempts}: "
                     f"{'Success' if install_ok else 'failed'} "
                     f"(exit {code if not install_ok else 0})")
            else:
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
        observed evidence; the launch verb itself goes through the
        bridge's monkey form (also component-less)."""
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
        except Exception as exc:  # noqa: BLE001, S110 — never raises
            emit(f"  reference: destroy after failure failed ({exc})")

    # ---------------------------------------------------------------- verbs

    def _post_launch(self, bridge: Any, native: _Native,
                     step_timeout: int,
                     emit: Callable[[str], None]) -> None:
        """probe-17 steps 5-6: wait for the app's process (ndk_translation
        under TCG is slow), then run the SystemUI-ANR dismissal ladder —
        the first launch of a heavy app trips a SystemUI ANR dialog over
        the splash; the app task stays alive behind it."""
        provider, env_id, adb = native.provider, native.env_id, native.adb
        proc_line = ""
        for _ in range(PROCESS_WAIT_ROUNDS):
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
        else:
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

    def _invoke_plan(self, bridge: Any, plan: StepPlan, app: str,
                     step_timeout: int) -> bool:
        """Dispatch one planned step's verb calls (per-step budget)."""
        if not isinstance(plan, StepPlan):
            raise TypeError(
                f"step plans must be StepPlan instances "
                f"(got {type(plan).__name__}; runner bug)")
        ok = True
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
            elif verb == "launch":
                result = bridge.launch(app, timeout=step_timeout)
            else:  # pragma: no cover — mapping-table bug, fail loud
                raise LabCliError(
                    f"live driver cannot dispatch verb {verb!r}")
            ok = ok and result.ok
        return ok

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

    @staticmethod
    def _package_facts(bridge: Any, package: str) -> dict[str, Any]:
        """dumpsys package facts (versionName/versionCode) — discovered,
        never statically derived."""
        provider = bridge.provider
        env_id = bridge.env_id
        facts: dict[str, Any] = {}
        try:
            res = provider.execute(
                env_id,
                f'dumpsys package {package} | grep -m1 "versionName"',
                timeout=PKG_FACTS_TIMEOUT_S)
            for line in (res.stdout or "").splitlines():
                if "versionName=" in line:
                    facts["version_name"] = line.split("versionName=")[1] \
                        .split()[0]
            res = provider.execute(
                env_id,
                f'dumpsys package {package} | grep -m1 "versionCode"',
                timeout=PKG_FACTS_TIMEOUT_S)
            for line in (res.stdout or "").splitlines():
                if "versionCode=" in line:
                    code = line.split("versionCode=")[1].split()[0]
                    if code.isdigit():
                        facts["version_code"] = int(code)
        except Exception:  # noqa: BLE001, S110 — facts are best-effort
            pass
        return facts
