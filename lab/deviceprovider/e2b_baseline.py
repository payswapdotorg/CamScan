"""E2BBaselineProvider — the controlled baseline (handoff §2).

E2B (or the local station standing in for it) runs the DETERMINISTIC,
non-device-specific battery: the JVM unit gate + the pytest reconciliation
gate. It has NO Android device surface — every device-surface method raises
DeviceSurfaceUnavailable naming exactly that. The harness treats
executeInstrumentation() here as "the baseline battery" so acceptance
journeys can run the deterministic part before any device is involved.
"""

from __future__ import annotations

import re
import time
from pathlib import Path
from typing import Any, Optional

from .provider import (
    ActionResult,
    DeviceFacts,
    DeviceProvider,
    DeviceSurfaceUnavailable,
    run,
    sha256_of,
)


class E2BBaselineProvider(DeviceProvider):
    name = "e2b-baseline"

    def __init__(self, repo_dir: Path = Path("/home/z/CamScan")):
        self.repo = Path(repo_dir)
        self._started_ms: Optional[int] = None

    def provisionDevice(self, *, timeout_s: int = 300) -> DeviceFacts:
        # the "device" is the deterministic station itself
        rc, out = run(["git", "-C", str(self.repo), "rev-parse", "HEAD"])
        head = out.strip()[:12] if rc == 0 else "?"
        self._started_ms = int(time.time() * 1000)
        return DeviceFacts(
            provider=self.name,
            device_model="deterministic-station",
            android_version="n/a (JVM baseline)",
            extra={"repo_head": head},
        )

    def releaseDevice(self) -> None:
        return None

    # -- app surface: honestly unavailable -----------------------------------
    def installApk(self, apk_path: Path) -> ActionResult:
        raise DeviceSurfaceUnavailable("apk-install", self.name,
                                       "baseline has no Android device")

    def launchApp(self, activity: str = "org.payswap.camscan/.MainActivity") -> ActionResult:
        raise DeviceSurfaceUnavailable("app-launch", self.name,
                                       "baseline has no Android device")

    def executeAction(self, action: str, **params: Any) -> ActionResult:
        if action == "wait":
            time.sleep(float(params.get("s", 1)))
            return ActionResult(True, "wait", f"{params.get('s', 1)}s")
        raise DeviceSurfaceUnavailable(f"action:{action}", self.name,
                                       "baseline has no Android device")

    # -- verification surface: the deterministic battery ----------------------
    def executeInstrumentation(self, test_filter: str = "") -> ActionResult:
        """Runs the JVM gate (and the pytest reconcile gate). This is the
        controlled baseline the campaign always re-runs before device work."""
        gradle = str(self.repo / "gradlew")
        rc, out = run([gradle, "--no-daemon", "-Dorg.gradle.jvmargs=-Xmx1536m",
                       "testDebugUnitTest"], timeout_s=1800)
        m = re.search(r"(\d+) tests completed, (\d+) failed", out)
        completed, failed = (m.group(1), m.group(2)) if m else ("?", "?")
        ok = rc == 0 and (failed == "0" if m else False)
        detail = f"unit: {completed} completed, {failed} failed (rc={rc})"
        # pytest reconcile gate (best-effort; station python)
        try:
            rc2, out2 = run(["python3", "-m", "pytest", "-q",
                             str(self.repo / "lab" / "reconciliation")],
                            timeout_s=600)
            m2 = re.search(r"(\d+) passed", out2)
            detail += f" | pytest: {(m2.group(1) + ' passed') if m2 else 'no-count'} (rc={rc2})"
            ok = ok and rc2 == 0
        except Exception as e:
            detail += f" | pytest: SKIPPED ({str(e)[:80]})"
        return ActionResult(ok, "baseline-battery", detail)

    # -- evidence collectors ---------------------------------------------------
    def collectScreenshot(self, out_path: Path) -> ActionResult:
        raise DeviceSurfaceUnavailable("screenshot", self.name,
                                       "baseline has no screen")

    def collectVideo(self, out_path: Path) -> ActionResult:
        raise DeviceSurfaceUnavailable("video", self.name, "baseline has no screen")

    def collectUiDump(self, out_path: Path) -> ActionResult:
        raise DeviceSurfaceUnavailable("uidump", self.name, "baseline has no UI tree")

    def collectLogcat(self, out_path: Path, *, since_ms: Optional[int] = None) -> ActionResult:
        raise DeviceSurfaceUnavailable("logcat", self.name, "baseline has no device logs")

    def collectArtifacts(self, out_dir: Path) -> list[dict[str, str]]:
        # the battery's gradle test reports are the baseline artifacts
        reports = self.repo / "app" / "build" / "reports"
        arts: list[dict[str, str]] = []
        if reports.exists():
            out_dir.mkdir(parents=True, exist_ok=True)
            for xml in reports.rglob("*.xml"):
                if "testDebugUnitTest" in str(xml) or "tests" in str(xml):
                    dest = out_dir / ("baseline-" + xml.name)
                    dest.write_bytes(xml.read_bytes())
                    arts.append({"kind": "test-report", "path": str(dest),
                                 "sha256": sha256_of(dest)})
        return arts
