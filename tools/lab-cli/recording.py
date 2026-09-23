"""The RecordingDriver — the deterministic, substrate-free driver
(CAMSCAN-007).

Logs the would-be adb-bridge/provider calls (stdout, one
``would:`` line per call — the bundle layout's ``logs/`` vocabulary is
pinned by evidence-cli: ``logcat.txt`` + ``app-events.json`` only, so
the call log is operator output, not a bundled artifact) and writes
**synthetic** evidence per EVIDENCE.md: tiny deterministic artifacts
(the same tiny-PNG/tiny-XML technique as the committed demo pair,
``tools/parity-cli/tests/make_demo_pair.py``) and a complete
single-subject ``run-metadata.json``.

Honesty rules (the lab's doctrine):

- it fabricates **no substrate claims**: the manifest records the
  provider/capabilities of the report the scheduler resolved, and the
  environment_id is explicitly marked ``-recording-``;
- both subjects get **identical step traces** (every step ``ok``), so a
  recording pair reconciles to verdict PASS — the deliberate-divergence
  fixtures are parity-cli's own (the 005 demo pair), not this driver's;
- no network, no device, no credentials — pytest scope. The live path
  is :mod:`tools.lab_cli.e2b_live` (exercised by the lab later).
"""
from __future__ import annotations

import hashlib
import json
import struct
import zlib
from pathlib import Path
from typing import Any, Callable

from tools.evidence_cli.jsonio import dump as jsonio_dump
from tools.lab_cli.drivers import (
    APP_PACKAGES,
    DriverHandle,
    ExecutionRequest,
    ProvisionRequest,
    SubjectRunResult,
)
from tools.lab_cli.evidence import provider_capabilities
from tools.lab_cli.scenarios import Scenario
from tools.lab_cli.steps import APP, StepPlan, render_call

#: Deterministic installer identities (never a real APK hash).
_INSTALLER_SEED = {
    "reference": b"camscan-recording-reference-installer",
    "implementation": b"camscan-recording-implementation-installer",
}

#: Application facts per subject (provenance: make_demo_pair /
#: reference-observe package facts — CamScanner 7.25.5 vs CamScan 0.1.0).
_APPLICATION: dict[str, dict[str, Any]] = {
    "reference": {
        "package": APP_PACKAGES["reference"],
        "version_name": "7.25.5",
        "version_code": 2609020000,
    },
    "implementation": {
        "package": APP_PACKAGES["implementation"],
        "version_name": "0.1.0",
        "version_code": 1,
    },
}

#: Device profile (the pinned lab profile — same both sides, EVIDENCE.md
#: run-pair requirement).
_DEVICE: dict[str, Any] = {
    "model": "Pixel 4 (AVD pixel_4)",
    "android_version": "11",
    "screen": "1080x2280@440dpi",
    "locale": "en-US",
    "timezone": "UTC",
}


def tiny_png(marker: str) -> bytes:
    """Deterministic 1x1 PNG carrying ``marker`` in its IDAT."""
    def chunk(kind: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)
    idat = zlib.compress(b"\x00" + marker.encode())
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", idat) + chunk(b"IEND", b""))


def tiny_xml(step: str) -> bytes:
    """Deterministic UI-hierarchy dump carrying the step name."""
    return (f'<?xml version="1.0"?><hierarchy rotation="0">'
            f'<node step="{step}" bounds="[0,0][1080,2280]"/>'
            f"</hierarchy>\n").encode()


class RecordingDriver:
    """Logs would-be calls; writes deterministic synthetic evidence."""

    slug = "recording"

    # ------------------------------------------------------------- lifecycle

    def provision(self, request: ProvisionRequest) -> DriverHandle:
        app = dict(_APPLICATION[request.subject])
        app["installer_sha256"] = hashlib.sha256(
            _INSTALLER_SEED[request.subject]).hexdigest()
        device = dict(_DEVICE)
        device["permission_baseline"] = self._permission_baseline(
            request.scenario)
        handle = DriverHandle(
            subject=request.subject,
            provider_slug=str(request.provider_report.get("slug", "unknown")),
            environment_id=(f"env-{request.provider_report.get('slug', 'x')}"
                            f"-recording-{request.subject}"),
            capabilities=provider_capabilities(request.provider_report),
            application=app,
            device=device,
            native=None,
        )
        request.emit(
            f"  {request.subject}: provisioned {handle.environment_id} "
            "(recording driver — no substrate calls)")
        return handle

    def execute(self, handle: DriverHandle,
                request: ExecutionRequest) -> SubjectRunResult:
        subject = request.subject
        app_package = APP_PACKAGES[subject]
        subject_dir = Path(request.stage_dir) / subject
        (subject_dir / "screenshots").mkdir(parents=True, exist_ok=True)
        (subject_dir / "ui").mkdir(parents=True, exist_ok=True)
        (subject_dir / "logs").mkdir(parents=True, exist_ok=True)

        calls_seen: list[dict[str, Any]] = []
        trace: list[dict[str, Any]] = []
        artifacts = 0
        events: list[dict[str, Any]] = []
        for plan in request.step_plans:
            if not isinstance(plan, StepPlan):
                raise TypeError(
                    f"step plans must be StepPlan instances "
                    f"(got {type(plan).__name__}; runner bug)")
            name = f"{plan.index:02d}-{plan.step.action}"
            (subject_dir / "screenshots" / f"{name}.png").write_bytes(
                tiny_png(f"camscan-recording-{subject}-{name}"))
            (subject_dir / "ui" / f"{name}.xml").write_bytes(
                tiny_xml(name))
            artifacts += 2
            trace.append({
                "t_ms": 1400 * (plan.index - 1),
                "action": plan.step.action,
                "target": (str(plan.step.arg)
                           if plan.step.arg is not None else ""),
                "result": "ok",
            })
            for call in plan.calls:
                rendered = render_call(call)
                if call.verb == "launch":
                    rendered = rendered.replace(APP, app_package)
                calls_seen.append({"verb": call.verb, "call": rendered})
                request.emit(f"  {subject}: step {plan.index:02d} "
                             f"{plan.step.label()} → would: {rendered}")
            events.append({"t_ms": 1400 * (plan.index - 1),
                           "event": plan.step.action})

        (subject_dir / "logs" / "logcat.txt").write_bytes(
            f"recording-driver: {request.run_id} subject={subject}\n".encode())
        (subject_dir / "logs" / "app-events.json").write_bytes(
            json.dumps(events, sort_keys=True).encode() + b"\n")
        artifacts += 2

        metadata = {
            "run_id": request.run_id,
            "scenario": request.scenario.id,
            "subject": subject,
            "provider": {
                "slug": handle.provider_slug,
                "capabilities": handle.capabilities,
                "environment_id": handle.environment_id,
            },
            "application": handle.application,
            "device": handle.device,
            "fixtures": request.fixtures,
            "action_trace": trace,
            "started_at": request.started_at,
            "finished_at": request.finished_at,
        }
        jsonio_dump(Path(request.stage_dir) / "run-metadata.json", metadata)
        return SubjectRunResult(subject=subject, ok=True,
                                steps_executed=len(request.step_plans),
                                artifacts=artifacts)

    def teardown(self, handle: DriverHandle, error: str = "",
                 emit: Callable[[str], None] = print) -> None:
        # no substrate was provisioned — nothing to stop or destroy
        suffix = f" (error: {error})" if error else ""
        emit(f"  {handle.subject}: tore down {handle.environment_id} "
             f"[recording: no substrate to destroy]{suffix}")

    # ---------------------------------------------------------------- utils

    @staticmethod
    def _permission_baseline(scenario: Scenario) -> dict[str, bool]:
        baseline: dict[str, bool] = {}
        for pre in scenario.preconditions:
            if pre == "camera-permission-granted":
                baseline["android.permission.CAMERA"] = True
        return baseline
