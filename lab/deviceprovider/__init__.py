"""CamScan DeviceProvider package (lead lab-code, final TL handoff §5).

Usage (harness-side only — product code never imports this):

    from lab.deviceprovider import LocalEmulatorProvider, GoogleDeviceProvider, E2BBaselineProvider, EvidenceRecord

    p = LocalEmulatorProvider()
    facts = p.provisionDevice()
    p.installApk(Path("app/build/outputs/apk/debug/app-debug.apk"))
    p.launchApp()
    ...p.executeAction("tap", x=..., y=...)
    ev = EvidenceRecord(scenario_id="S001", commit_sha=..., apk_sha256=...,
                        provider=p.name, device=facts, timestamp=...,
                        action_trace=trace, result="OBSERVED")
    EvidenceRecord.write(ev, Path("lab/parity-ledger/evidence"))
"""

from .provider import (
    ActionResult,
    DeviceFacts,
    DeviceProvider,
    DeviceSurfaceUnavailable,
    EvidenceRecord,
    ProviderCapability,
    run,
    sha256_of,
)
from .local_emulator import LocalEmulatorProvider
from .google_stream import GoogleDeviceProvider
from .e2b_baseline import E2BBaselineProvider

PROVIDERS = {
    LocalEmulatorProvider.name: LocalEmulatorProvider,
    GoogleDeviceProvider.name: GoogleDeviceProvider,
    E2BBaselineProvider.name: E2BBaselineProvider,
}

__all__ = [
    "ActionResult", "DeviceFacts", "DeviceProvider", "DeviceSurfaceUnavailable",
    "EvidenceRecord", "ProviderCapability", "run", "sha256_of",
    "LocalEmulatorProvider", "GoogleDeviceProvider", "E2BBaselineProvider",
    "PROVIDERS",
]
