"""CamScan DeviceProvider seam — lead lab-code (final TL handoff 2026-10-03 §5).

The provider abstraction keeps device-specific mechanics out of product logic:

    DeviceProvider
    ├── provisionDevice()
    ├── installApk()
    ├── launchApp()
    ├── executeAction()
    ├── executeInstrumentation()
    ├── collectScreenshot()
    ├── collectVideo()
    ├── collectUiDump()
    ├── collectLogcat()
    ├── collectArtifacts()
    └── releaseDevice()

Implementations (see __init__.py exports):
  - LocalEmulatorProvider — the on-box emulator via adb (deterministic device
    surface; androidTest/instrumentation; free; the station's own gate level).
    Justification (recorded): the board's open surface requires an
    emulator-backed station for the androidTest surface, and burning the
    operator's 30 no-cost Device Streaming minutes on runs an emulator does
    deterministically for free would be waste. NOT a product dependency.
  - GoogleDeviceProvider — Android Device Streaming driven through the replay
    browser (operator-authenticated interactive session; the primary external
    real-device route per the handoff). Not hard-wired to Firebase Test Lab
    (scheduled to shut down 2027-09-30); the seam stays replaceable.
  - E2BBaselineProvider — the controlled baseline (deterministic,
    non-device-specific battery: JVM unit tests + pytest); device-surface
    methods raise DeviceSurfaceUnavailable honestly (E2B has no Android
    device).

Escalation policy (handoff §16/§17): a provider raising ProviderCapability
must name the EXACT missing capability; the harness then walks
Google → next suitable provider, keeping evidence provider-neutral.
"""

from __future__ import annotations

import json
import subprocess
import time
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any, Optional


class ProviderCapability(Exception):
    """A provider cannot serve a requested capability. `capability` names the
    EXACT missing capability (never a generic 'infrastructure blocked')."""

    def __init__(self, capability: str, provider: str, remedy: str = ""):
        self.capability = capability
        self.provider = provider
        self.remedy = remedy
        super().__init__(
            f"{provider} lacks capability: {capability}"
            + (f" — {remedy}" if remedy else "")
        )


class DeviceSurfaceUnavailable(ProviderCapability):
    """The provider is a baseline (no real device surface)."""


@dataclass
class DeviceFacts:
    """Handoff §6 'device facts' — recorded with every evidence package."""

    provider: str
    device_model: str
    android_version: str
    api_level: Optional[int] = None
    screen: Optional[str] = None  # "WxH@dpi"
    abis: list[str] = field(default_factory=list)
    extra: dict[str, Any] = field(default_factory=dict)


@dataclass
class ActionResult:
    ok: bool
    action: str
    detail: str = ""
    at_ms: int = field(default_factory=lambda: int(time.time() * 1000))


@dataclass
class EvidenceRecord:
    """Handoff §15 — one accepted scenario's evidence package (reproducible)."""

    scenario_id: str
    commit_sha: str
    apk_sha256: str
    provider: str
    device: DeviceFacts
    timestamp: str  # ISO-8601 UTC
    action_trace: list[ActionResult] = field(default_factory=list)
    result: str = "UNKNOWN"  # UNKNOWN / OBSERVED / EVIDENCED / RECONCILED / PASS / FAIL
    artifacts: list[dict[str, str]] = field(default_factory=list)  # {kind, path, sha256}

    def to_json(self) -> str:
        d = asdict(self)
        return json.dumps(d, indent=2, sort_keys=True)

    @staticmethod
    def write(record: "EvidenceRecord", out_dir: Path) -> Path:
        out_dir.mkdir(parents=True, exist_ok=True)
        path = out_dir / f"{record.scenario_id}-{record.provider}-{uuid.uuid4().hex[:8]}.json"
        path.write_text(record.to_json(), encoding="utf-8")
        return path


class DeviceProvider(ABC):
    """The replaceable device seam (handoff §2/§5). Product logic NEVER sees a
    concrete provider; the harness selects one and records it in evidence."""

    name: str = "abstract"

    # -- lifecycle ---------------------------------------------------------
    @abstractmethod
    def provisionDevice(self, *, timeout_s: int = 300) -> DeviceFacts:
        """Provision (or attach to) a device; returns its facts."""

    @abstractmethod
    def releaseDevice(self) -> None:
        """Release the device/session (idempotent)."""

    # -- app surface -------------------------------------------------------
    @abstractmethod
    def installApk(self, apk_path: Path) -> ActionResult:
        """Install the built APK onto the provisioned device."""

    @abstractmethod
    def launchApp(self, activity: str = "org.payswap.camscan/.MainActivity") -> ActionResult:
        """Cold-launch the app."""

    @abstractmethod
    def executeAction(self, action: str, **params: Any) -> ActionResult:
        """One user-level action on the device (tap/swipe/type/wait/back…)."""

    # -- verification surface ----------------------------------------------
    @abstractmethod
    def executeInstrumentation(self, test_filter: str = "") -> ActionResult:
        """Run androidTest instrumentation on-device (or the baseline battery
        for baseline providers — the harness treats both as 'the battery')."""

    # -- evidence collectors (handoff §6/§15) --------------------------------
    @abstractmethod
    def collectScreenshot(self, out_path: Path) -> ActionResult:
        """Screen capture."""

    @abstractmethod
    def collectVideo(self, out_path: Path) -> ActionResult:
        """Session video where the provider exposes it (else ProviderCapability)."""

    @abstractmethod
    def collectUiDump(self, out_path: Path) -> ActionResult:
        """UI hierarchy dump."""

    @abstractmethod
    def collectLogcat(self, out_path: Path, *, since_ms: Optional[int] = None) -> ActionResult:
        """logcat capture."""

    @abstractmethod
    def collectArtifacts(self, out_dir: Path) -> list[dict[str, str]]:
        """Pull provider-specific artifacts (traces, ANRs, device logs)."""


def sha256_of(path: Path) -> str:
    import hashlib

    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd: list[str], *, timeout_s: int = 120) -> tuple[int, str]:
    """Bounded subprocess run used by all providers (never unbounded)."""
    p = subprocess.run(
        cmd, capture_output=True, text=True, timeout=timeout_s, check=False
    )
    return p.returncode, (p.stdout or "") + (p.stderr or "")
