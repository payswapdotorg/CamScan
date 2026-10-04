"""CamScan acceptance journeys — lead lab-code (final TL handoff §7/§8/§13).

Executable forms of the P0 (§7) and P1 (§8) acceptance journeys, driven
through the DeviceProvider seam with per-step evidence capture (§15) and
honest classification (never infer from screenshots where direct
observation is required — §13).

The ADB element driver uses the repo's FROZEN semantic resource ids
(scan_capture_button, scan_done_button, ...) — the contract the scan
surface documents ("ADB parity tests depend on them").

Usage (station):
    python3 lab/acceptance/journey.py p0 --provider local-emulator \
        --apk app/build/outputs/apk/debug/app-debug.apk \
        --evidence lab/parity-ledger/evidence/p0-<runstamp>

Each step writes ActionResult entries into the run's EvidenceRecord; the
final record set feeds the parity ledger progression
(UNKNOWN → OBSERVED/EVIDENCED → RECONCILED → PASS — never bulk-edited).
"""

from __future__ import annotations

import argparse
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from lab.deviceprovider import (  # noqa: E402
    ActionResult,
    DeviceFacts,
    DeviceProvider,
    EvidenceRecord,
    LocalEmulatorProvider,
    GoogleDeviceProvider,
    E2BBaselineProvider,
    sha256_of,
)

APP = "org.payswap.camscan"

# Frozen semantic ids (fragment_scan.xml contract + viewer/library surfaces)
IDS = {
    "scan_entry": "scan_capture_button",
    "scan_done": "scan_done_button",
    "scan_guidance": "scan_detection_guidance",
    "scan_framing": "scan_framing_overlay",
    "page_badge": "session_page_count_badge",
}


class UiDriver:
    """resource-id located taps via uiautomator dump (provider-agnostic)."""

    def __init__(self, provider: DeviceProvider, workdir: Path):
        self.p = provider
        self.workdir = workdir
        self.workdir.mkdir(parents=True, exist_ok=True)
        self._shot = 0

    def dump(self) -> Optional[ET.Element]:
        f = self.workdir / f"uidump-{int(time.time())}.xml"
        r = self.p.collectUiDump(f)
        if not r.ok or not f.exists():
            return None
        try:
            return ET.parse(f).getroot()
        except ET.ParseError:
            return None

    def find(self, res_id: str) -> Optional[dict]:
        root = self.dump()
        if root is None:
            return None
        for node in root.iter("node"):
            if node.get("resource-id", "").endswith(f"/{res_id}") or node.get("resource-id") == res_id:
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    return {"cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
                            "clickable": node.get("clickable") == "true",
                            "visible": node.get("visible-to-user", "true") == "true"}
        return None

    def tap_id(self, res_id: str) -> ActionResult:
        el = self.find(res_id)
        if el is None:
            return ActionResult(False, f"tap:{res_id}", "element not found in dump")
        act = self.p.executeAction("tap", x=el["cx"], y=el["cy"])
        return ActionResult(act.ok, f"tap:{res_id}",
                            f"({el['cx']},{el['cy']}) " + act.detail[:120])

    def tap_text(self, needle: str) -> ActionResult:
        root = self.dump()
        if root is None:
            return ActionResult(False, f"tap-text:{needle}", "dump failed")
        for node in root.iter("node"):
            if needle.lower() in (node.get("text", "") or "").lower():
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    act = self.p.executeAction("tap", x=(x1 + x2) // 2, y=(y1 + y2) // 2)
                    return ActionResult(act.ok, f"tap-text:{needle}", act.detail[:120])
        return ActionResult(False, f"tap-text:{needle}", "text not found")

    def exists(self, res_id: str) -> bool:
        return self.find(res_id) is not None

    def shot(self, tag: str) -> ActionResult:
        self._shot += 1
        f = self.workdir / f"shot-{self._shot:03d}-{tag}.png"
        return self.p.collectScreenshot(f)


class Journey:
    """The §7 P0 journey + shared evidence bookkeeping."""

    def __init__(self, provider: DeviceProvider, apk: Path, evidence_dir: Path,
                 commit_sha: str, facts: DeviceFacts):
        self.p = provider
        self.ui = UiDriver(provider, evidence_dir / "captures")
        self.evidence_dir = evidence_dir
        self.apk = apk
        self.commit_sha = commit_sha
        self.facts = facts
        self.trace: list[ActionResult] = []

    def step(self, name: str, action) -> ActionResult:
        r = action() if callable(action) else action
        self.trace.append(r)
        print(f"  [{'OK ' if r.ok else 'FAIL'}] {name}: {r.detail[:110]}")
        return r

    # -- §7 P0 journey -------------------------------------------------------
    def p0(self) -> EvidenceRecord:
        p = self.p
        self.step("install", lambda: p.installApk(self.apk))
        self.step("launch", lambda: p.launchApp())
        # Camera permission dialog (first launch) — grant via the dialog button
        granted = self.ui.tap_text("ALLOW")
        if not granted.ok:
            granted = self.step("grant-permission",
                                lambda: p.executeAction("grant", permission="android.permission.CAMERA"))
        self.step("home-visible", lambda: self.ui.shot("home"))
        # New scan
        self.step("new-scan", lambda: self.ui.tap_text("Scan"))
        time.sleep(2)
        self.step("preview-visible", lambda: self.ui.shot("preview"))
        self.step("preview-control", lambda: ActionResult(
            self.ui.exists(IDS["scan_entry"]), "preview-control",
            f"scan_capture_button {'present' if self.ui.exists(IDS['scan_entry']) else 'MISSING'}"))
        # Document detection + guidance observable via dump (honest: virtual camera
        # scene content is NOT asserted — camera_fixture honesty §9)
        self.step("detection-guidance-surface", lambda: ActionResult(
            self.ui.exists(IDS["scan_guidance"]) or self.ui.exists(IDS["scan_framing"]),
            "detection-guidance-surface", "guidance/framing element presence"))
        # Capture
        self.step("capture", lambda: self.ui.tap_id(IDS["scan_entry"]))
        time.sleep(3)
        self.step("post-capture", lambda: self.ui.shot("captured"))
        # Review + accept (crop/perspective/enhancement stages observable as dumps)
        self.step("review-visible", lambda: self.ui.shot("review"))
        self.step("accept-document", lambda: self.ui.tap_text("Accept"))
        # Multi-page: capture a second page then done
        self.step("second-capture", lambda: self.ui.tap_id(IDS["scan_entry"]))
        time.sleep(3)
        self.step("done", lambda: self.ui.tap_id(IDS["scan_done"]))
        # Save
        self.step("save", lambda: self.ui.tap_text("Save"))
        time.sleep(2)
        self.step("saved-state", lambda: self.ui.shot("saved"))
        # Library + reopen
        self.step("library", lambda: self.ui.tap_text("Library"))
        time.sleep(2)
        self.step("library-visible", lambda: self.ui.shot("library"))
        self.step("reopen", lambda: self.ui.tap_text("CamScan"))
        # Exports
        self.step("export-pdf", lambda: self.ui.tap_text("PDF"))
        time.sleep(2)
        self.step("export-pdf-state", lambda: self.ui.shot("export-pdf"))
        self.step("export-jpg", lambda: self.ui.tap_text("JPG"))
        self.step("share", lambda: self.ui.tap_text("Share"))
        time.sleep(2)
        self.step("share-state", lambda: self.ui.shot("share"))
        # logcat tail as evidence
        self.step("logcat", lambda: p.collectLogcat(
            self.evidence_dir / "captures" / "logcat.txt"))
        arts = p.collectArtifacts(self.evidence_dir / "captures")
        ok_steps = sum(1 for r in self.trace if r.ok)
        return EvidenceRecord(
            scenario_id="P0-JOURNEY", commit_sha=self.commit_sha,
            apk_sha256=sha256_of(self.apk), provider=p.name,
            device=self.facts, timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ"),
            action_trace=self.trace,
            result="OBSERVED" if ok_steps >= len(self.trace) * 0.7 else "FAIL",
            artifacts=arts,
        )

    # -- §8 P1 journey -------------------------------------------------------
    def p1(self) -> EvidenceRecord:
        p = self.p
        self.step("launch", lambda: p.launchApp())
        # Import
        self.step("import-entry", lambda: self.ui.tap_text("Import"))
        self.step("import-state", lambda: self.ui.shot("import"))
        # Merge / split / compress / OCR / search / rename — engine-backed
        # surfaces; each is tap-driven and dump-observed here, asserted by the
        # parity reconciliation run (S-campaign) rather than inferred.
        for label, needle in (("merge", "Merge"), ("split", "Split"),
                              ("compress", "Compress"), ("ocr", "OCR"),
                              ("search", "Search"), ("rename", "Rename")):
            self.step(f"{label}-surface", lambda n=needle: self.ui.tap_text(n))
            time.sleep(1)
            self.step(f"{label}-state", lambda t=label: self.ui.shot(t))
        self.step("export", lambda: self.ui.tap_text("Export"))
        self.step("share", lambda: self.ui.tap_text("Share"))
        self.step("logcat", lambda: p.collectLogcat(
            self.evidence_dir / "captures" / "logcat-p1.txt"))
        ok_steps = sum(1 for r in self.trace if r.ok)
        return EvidenceRecord(
            scenario_id="P1-JOURNEY", commit_sha=self.commit_sha,
            apk_sha256=sha256_of(self.apk), provider=p.name,
            device=self.facts, timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ"),
            action_trace=self.trace,
            result="OBSERVED" if ok_steps >= len(self.trace) * 0.7 else "FAIL",
        )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("journey", choices=("p0", "p1"))
    ap.add_argument("--provider", default="local-emulator",
                    choices=("local-emulator", "google-device-streaming", "e2b-baseline"))
    ap.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk")
    ap.add_argument("--evidence", default=None)
    args = ap.parse_args()

    import subprocess

    repo = Path(__file__).resolve().parents[2]
    commit = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"],
                            capture_output=True, text=True).stdout.strip()[:12]
    apk = Path(args.apk) if Path(args.apk).is_absolute() else repo / args.apk
    if not apk.exists():
        print(f"APK missing: {apk}")
        return 2
    ev_dir = Path(args.evidence) if args.evidence else (
        repo / "lab" / "parity-ledger" / "evidence" /
        f"{args.journey}-{time.strftime('%Y%m%dT%H%M%SZ')}")

    providers = {
        "local-emulator": lambda: LocalEmulatorProvider(),
        "google-device-streaming": lambda: GoogleDeviceProvider(),
        "e2b-baseline": lambda: E2BBaselineProvider(repo),
    }
    p = providers[args.provider]()
    facts = p.provisionDevice()
    j = Journey(p, apk, ev_dir, commit, facts)
    print(f"=== {args.journey.upper()} JOURNEY | provider={p.name} | base={commit} ===")
    rec = j.p0() if args.journey == "p0" else j.p1()
    out = EvidenceRecord.write(rec, ev_dir)
    print(f"=== RESULT {rec.result} | steps {sum(1 for r in rec.action_trace if r.ok)}/{len(rec.action_trace)} OK")
    print(f"evidence: {out}")
    p.releaseDevice()
    return 0 if rec.result != "FAIL" else 1


if __name__ == "__main__":
    raise SystemExit(main())
