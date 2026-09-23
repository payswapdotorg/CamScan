"""Provider-neutral contract types for the CamScan parity lab.

These are the machine-readable form of the LabProvider interface contract in
`lab/providers/LABPROVIDER.md`. Pure stdlib — importable from CI validators
and the provider scheduler without any substrate SDK installed.

Semantics notes (v0.2 — CLI-first, operator directive 2026-09-23):

- CLI-first Android: the canonical worker environment is terminal-only
  (android_sdk / android_cli / android_emulator / adb / gradle).
  `android_studio` is NOT part of the matchable vocabulary anymore — it
  survives ONLY as optional environment metadata in capability reports
  (see capabilities.schema.json) and MUST never appear in scenario
  `meta.requires` (validate_requirement rejects it as unknown — the
  mechanical enforcement of "never require Android Studio").
- Timeouts: three distinct layers, never conflated:
    * provider-level budgets (bootstrap/boot/renewal) — owned by the provider
      implementation, calibrated for its substrate (e.g. TCG cold boot);
    * scenario `meta.timeout_seconds` — wall-clock budget for the scenario
      EXECUTION phase, measured from environment-ready to evidence-collection
      complete; environment provisioning/boot is NOT counted against it;
    * scenario `meta.step_timeout_seconds` — budget for a single interactive
      step (adb input, UI wait, capture). The provider passes these down as
      the default per-command timeout during scenario execution.
- Capability reports and scenario `meta.requires` are both validated against
  `lab/providers/capabilities.schema.json` (typed mapping, not string lists).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional


# ---------------------------------------------------------------- capability

#: Known capability keys — the vocabulary shared by reports and requirements.
#: v0.2 (CLI-first): android_sdk / android_cli / gradle added as first-class
#: capabilities; android_studio REMOVED from the matchable vocabulary
#: (optional report metadata only — never a scenario requirement).
CAPABILITY_KEYS: tuple[str, ...] = (
    "gui",                 # screen content observable (screenshot + UI hierarchy)
    "persistent",          # environment survives across runs
    "android_sdk",         # Android SDK installed (cmdline-tools + platform-tools
    #                        # + platforms + build-tools) — CLI-installable
    "android_cli",         # terminal-accessible Android CLI tooling
    #                        # (sdkmanager/avdmanager and/or the newer unified
    #                        # `android` CLI) — the CLI-first worker stack
    "android_emulator",    # can run an Android emulator at all
    "emulator_acceleration",  # enum: none | kvm | hvf
    "adb",                 # Android Debug Bridge access
    "gradle",              # Gradle build capability (wrapper distribution usable)
    "camera_fixture",      # deterministic virtual-camera injection
    "screenshots",         # still-frame capture of the device screen
    "recording",           # video capture of the device screen
    "snapshot",            # environment state snapshot/restore
)

#: Enum-valued capabilities and their allowed values.
ENUM_CAPABILITIES: dict[str, tuple[str, ...]] = {
    "emulator_acceleration": ("none", "kvm", "hvf"),
}

#: Boolean-valued capabilities (everything not in ENUM_CAPABILITIES).
BOOL_CAPABILITIES: tuple[str, ...] = tuple(
    k for k in CAPABILITY_KEYS if k not in ENUM_CAPABILITIES
)


def capability_satisfies(requirement: Any, value: Any) -> bool:
    """Typed satisfaction check of one capability requirement against a value.

    Accepted requirement forms:
      - bool          -> provider value must equal it (booleans only)
      - str           -> exact enum match (enum-valued capabilities)
      - {allowed: [...]} -> value must appear in the allowed list (enum caps)
    """
    if isinstance(requirement, bool):
        return isinstance(value, bool) and value is requirement
    if isinstance(requirement, str):
        return value == requirement
    if isinstance(requirement, dict):
        allowed = requirement.get("allowed")
        if isinstance(allowed, list) and allowed:
            return value in allowed
    return False


# ------------------------------------------------------------- environment

@dataclass
class EnvironmentSpec:
    """Declarative description of the environment to provision."""

    purpose: str = "probe"                    # reference | implementation | probe
    android_api: int = 30                     # Android 11 = API 30
    system_image: str = "system-images;android-30;default;x86_64"
    device_profile: str = "pixel_4"
    resolution: str = "1080x2280"
    memory_mb: int = 2048
    cores: int = 4
    locale: str = "en-US"                     # deterministic default (persist.sys.locale)
    timezone: str = "UTC"                     # deterministic default (persist.sys.timezone)
    camera_back: str = "virtualscene"         # emulated back-camera backend
    camera_poster: Optional[str] = None       # sandbox path: image injected as
                                              # virtualscene poster1 (launch flag)
    extra_sdk_packages: tuple[str, ...] = ()  # beyond the provider's known-good base
    tag: Optional[str] = None                 # free-form run correlation


@dataclass
class EnvironmentHandle:
    """Identifier + state for one provisioned environment.

    `env_id` is the EnvironmentId used by every LabProvider operation.
    """

    env_id: str
    sandbox_id: str
    avd_name: str
    adb_serial: str                           # e.g. "emulator-5554"
    provider_slug: str
    spec: EnvironmentSpec
    capabilities: dict[str, Any]
    created_utc: str
    status: str = "provisioning"              # provisioning|provisioned|booting|ready
    #                                          # |stopped|destroyed|error
    last_error: Optional[str] = None
    timings: dict[str, float] = field(default_factory=dict)
    trace_path: str = ""                      # sandbox-side JSONL action trace


@dataclass
class CommandResult:
    exit_code: int
    stdout: str = ""
    stderr: str = ""
    duration_ms: int = 0
    command: str = ""


# ------------------------------------------------------------- interaction

class CaptureKind(str, Enum):
    screenshot = "screenshot"
    ui_hierarchy = "ui_hierarchy"
    logcat = "logcat"
    recording = "recording"


@dataclass
class Interaction:
    """One concrete device interaction (semantic targets are resolved upstream
    by the adb-bridge/target registry; the provider executes concrete actions
    and records them into the action trace)."""

    kind: str                                  # tap|swipe|type|press|key|wake
    x: int = 0
    y: int = 0
    x2: int = 0
    y2: int = 0
    duration_ms: int = 300
    text: str = ""
    button: str = ""                           # press: back|home
    keycode: str = ""                          # key: KEYCODE_* name
    label: str = ""                            # semantic target name for the trace

    def to_cmd(self, adb: str) -> str:
        if self.kind == "tap":
            return f"{adb} shell input tap {self.x} {self.y}"
        if self.kind == "swipe":
            return (f"{adb} shell input swipe {self.x} {self.y} "
                    f"{self.x2} {self.y2} {self.duration_ms}")
        if self.kind == "type":
            return f"{adb} shell input text {self.text}"
        if self.kind == "press":
            code = {"back": "KEYCODE_BACK", "home": "KEYCODE_HOME"}[self.button]
            return f"{adb} shell input keyevent {code}"
        if self.kind == "key":
            return f"{adb} shell input keyevent {self.keycode}"
        if self.kind == "wake":
            return f"{adb} shell input keyevent KEYCODE_WAKEUP"
        raise ValueError(f"unknown interaction kind: {self.kind}")


@dataclass
class TraceEvent:
    """One recorded action — the action-trace unit compared by parity-cli."""

    ts_utc: str
    env_id: str
    kind: str                                   # interact|capture|execute|boot|lifecycle
    label: str = ""
    detail: dict[str, Any] = field(default_factory=dict)


# ---------------------------------------------------------------- artifacts

@dataclass
class Artifact:
    kind: str                                   # CaptureKind value or "file"
    sandbox_path: str
    sha256: str = ""
    bytes: int = 0
    duration_ms: int = 0
    taken_utc: str = ""
    local_path: Optional[str] = None            # set once pulled


@dataclass
class EvidenceBundle:
    run_id: str
    env_id: str
    sandbox_id: str
    provider_slug: str
    local_path: str                             # local dir the bundle was pulled into
    manifest_path: str
    files: list[dict[str, Any]] = field(default_factory=list)
    trace_events: int = 0


@dataclass
class ResetSpec:
    """Deterministic preconditions — established by the provider, never by hand."""

    wipe_data: bool = True                      # fresh userdata (fresh-install state)
    reinstall_apk: Optional[str] = None         # sandbox-side APK path to (re)install
    permissions: dict[str, bool] = field(default_factory=dict)  # "android.permission.CAMERA": True
    settings: dict[str, str] = field(default_factory=dict)      # settings namespace put


@dataclass
class HealthReport:
    env_id: str
    sandbox_id: str
    generated_utc: str
    ok: bool
    sandbox_alive: bool = False
    emulator_processes: int = 0
    adb_devices: str = ""
    boot_completed: bool = False
    android_version: str = ""
    device_model: str = ""
    resolution: str = ""
    density: str = ""
    locale: str = ""
    timezone: str = ""
    avd_name: str = ""
    uptime_s: float = 0.0
    disk_free_mb: int = 0
    mem_free_mb: int = 0
    capabilities: dict[str, Any] = field(default_factory=dict)
    timings: dict[str, float] = field(default_factory=dict)
    ops_recorded: int = 0
    last_error: Optional[str] = None
    probes: dict[str, Any] = field(default_factory=dict)   # extra acceptance probe results
