"""The live e2b driver — the real substrate path (CAMSCAN-007).

Composes the pieces the lead already landed: the e2b LabProvider
(``lab/providers/e2b/``, CAMSCAN-008 — baked TCG recipe, all 13
contract operations) and the adb-bridge verb layer
(``tools/adb-bridge/``). This module is **lab-exercised, never
pytest-exercised** (live-provider network calls are out of pytest
scope) — the in-tool tests pin the driver *contract* through the
RecordingDriver and the API-key preflight only.

Honesty gates, in order:

1. ``E2B_API_KEY`` must be present BEFORE anything is provisioned
   (credentials live in the environment only — never in git, manifests,
   reports, evidence, or logs; see SECURITY.md);
2. the implementation env needs an APK: an explicit ``--apk`` path
   (pushed + installed through the provider contract) — the in-sandbox
   gradle build path is the provider's ``gradle`` capability and stays
   an operator choice, never a silent fallback;
3. teardown runs on EVERY path (the runner enforces it in ``finally``)
   — paid environments are never leaked.

Timeout layering (SCENARIO-DSL, never conflated): provider
bootstrap/boot budgets stay provider-owned
(``E2BProviderConfig.boot_budget_s``); ``meta.timeout_seconds`` is the
scenario execution wall-clock enforced here from env-ready to
evidence-complete; ``meta.step_timeout_seconds`` is the per-step budget
passed to every bridge verb.
"""
from __future__ import annotations

import os
import time
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
from tools.lab_cli.scenarios import LabCliError
from tools.lab_cli.steps import APP, StepPlan

#: Environment variable holding the E2B credential (provider contract).
API_KEY_ENV = "E2B_API_KEY"

#: Implementation app facts (launch component resolved dynamically at
#: runtime — never statically derived, per the reference-install lesson).
IMPLEMENTATION_PACKAGE = "org.payswap.camscan"

#: Camera permission map for preconditions → ResetSpec.permissions.
_PRECONDITION_PERMISSIONS = {
    "camera-permission-granted": "android.permission.CAMERA",
}


def _require_api_key() -> str:
    """Preflight the credential BEFORE any provisioning (no network)."""
    key = os.environ.get(API_KEY_ENV)
    if not key:
        raise LabCliError(
            f"{API_KEY_ENV} is not set — the live e2b driver refuses to "
            "provision without it (credentials come from the environment "
            "only; pass --driver recording for substrate-free runs)")
    return key


class _Native:
    """Driver-private state carried on the handle (never serialized)."""

    def __init__(self, provider: Any, env_id: str) -> None:
        self.provider = provider
        self.env_id = env_id


class E2bLiveDriver:
    """Live implementation-env driver over the e2b LabProvider."""

    slug = "e2b-live"

    def __init__(self, apk: Path | None = None) -> None:
        self.apk = Path(apk) if apk is not None else None

    # ------------------------------------------------------------- lifecycle

    def provision(self, request: ProvisionRequest) -> DriverHandle:
        _require_api_key()
        if request.subject != "implementation":
            raise LabCliError(
                "the live e2b driver is on record for env=implementation "
                "only (the reference env's driver lands with CAMSCAN-009)")
        apk = self.apk or request.apk
        if apk is None:
            raise LabCliError(
                "live implementation runs need --apk <path-to-apk> (push + "
                "install through the provider contract; the in-sandbox "
                "gradlew build is an operator choice, never a silent "
                "fallback)")

        # Lazy import: the e2b SDK is only needed from here on.
        from lab.providers.e2b import E2BProvider
        from lab.providers.types import EnvironmentSpec, ResetSpec

        camera = (request.scenario.fixture or {}).get("camera")
        spec = EnvironmentSpec(
            purpose=request.subject,
            camera_poster=(str(camera) if isinstance(camera, str) and camera
                           else None),
            tag=request.run_id,
        )
        request.emit(f"  {request.subject}: provisioning e2b sandbox "
                     f"(TCG boot budget is provider-owned)…")
        provider = E2BProvider()
        env = provider.provision(spec)
        provider.start(env.env_id)
        permissions = {
            perm: True
            for pre in request.scenario.preconditions
            if (perm := _PRECONDITION_PERMISSIONS.get(pre))
        }
        provider.reset(env.env_id, ResetSpec(
            wipe_data=True,
            reinstall_apk=str(apk) if apk else None,
            permissions=permissions,
        ))
        report = provider.report(env.env_id) or {}
        device = {
            "model": str(report.get("device_model") or env.spec.device_profile),
            "android_version": str(report.get("android_version") or ""),
            "screen": str(report.get("resolution") or ""),
            "locale": str(report.get("locale") or env.spec.locale),
            "timezone": str(report.get("timezone") or env.spec.timezone),
            "permission_baseline": permissions,
        }
        application = {
            "package": IMPLEMENTATION_PACKAGE,
            # discovered at execution time (dumpsys package facts) — the
            # handle carries placeholders that execute() replaces.
            "version_name": "",
            "version_code": 0,
            "installer_sha256": _file_sha256(apk),
        }
        handle = DriverHandle(
            subject=request.subject,
            provider_slug="e2b",
            environment_id=env.env_id,
            capabilities=provider_capabilities(request.provider_report),
            application=application,
            device=device,
            native=_Native(provider, env.env_id),
        )
        request.emit(f"  {request.subject}: environment ready "
                     f"({env.env_id})")
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
        deadline = time.monotonic() + request.scenario.timeout_seconds
        started_real = request.clock() or request.started_at

        for plan in request.step_plans:
            if time.monotonic() > deadline:
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
            self._capture_step(bridge, subject_dir, plan.index,
                               plan.step.action)
            if not outcome:
                problems.append(f"step {plan.index:02d} "
                                f"{plan.step.label()} failed")

        logcat = bridge.logcat()
        (subject_dir / "logs" / "logcat.txt").write_text(
            logcat.text or "", encoding="utf-8")

        application = dict(handle.application)
        facts = self._package_facts(bridge, application["package"])
        application.update(facts)
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

    # ---------------------------------------------------------------- verbs

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
                # refresh the dump cache — the per-step capture below pairs
                # screenshot + ui dump as the step's evidence
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
                timeout=120)
            for line in (res.stdout or "").splitlines():
                if "versionName=" in line:
                    facts["version_name"] = line.split("versionName=")[1] \
                        .split()[0]
            res = provider.execute(
                env_id,
                f'dumpsys package {package} | grep -m1 "versionCode"',
                timeout=120)
            for line in (res.stdout or "").splitlines():
                if "versionCode=" in line:
                    code = line.split("versionCode=")[1].split()[0]
                    if code.isdigit():
                        facts["version_code"] = int(code)
        except Exception:  # noqa: BLE001, S110 — facts are best-effort, never fatal
            pass
        return facts


def _file_sha256(path: Path) -> str:
    import hashlib

    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()
