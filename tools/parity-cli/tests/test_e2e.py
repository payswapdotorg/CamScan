"""End-to-end interop: parity-cli consumes exactly what evidence-cli
produces.

Builds two REAL single-subject run trees (tiny but valid artifacts),
runs ``tools.evidence_cli.bundle_run`` on each (the reference manifest
writer), assembles the paired run dir per the EVIDENCE.md layout
decision (each subject subtree carries its own manifest.json), then
compares. Uses the real fixture corpus + the real S004 scenario file,
like the shipped synthetic demo pair.
"""
from __future__ import annotations

import json
import struct
import zlib
from pathlib import Path

from helpers import SCENARIO_S004
from tools.evidence_cli.bundle import bundle_run
from tools.evidence_cli.jsonio import load as jsonio_load
from tools.parity_cli.compare import compare_run

REPO_ROOT = Path(__file__).resolve().parents[3]
CORPUS_MANIFEST = REPO_ROOT / "lab" / "fixtures" / "manifest.json"


def tiny_png(marker: str) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)
    idat = zlib.compress(b"\x00" + marker.encode())
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", idat) + chunk(b"IEND", b""))


def tiny_xml(step: str) -> bytes:
    return (f'<?xml version="1.0"?><hierarchy rotation="0">'
            f'<node step="{step}" bounds="[0,0][1080,2280]"/>'
            f"</hierarchy>\n").encode()


def corpus_fixture(fixture_id: str = "clean-a4") -> dict:
    doc = jsonio_load(CORPUS_MANIFEST)
    for group, files_key in (("documents", "files"), ("sequences",
                                                      "frames")):
        for entry in doc.get(group, []):
            if entry["id"] == fixture_id:
                return {"id": fixture_id,
                        "sha256": entry[files_key][0]["sha256"]}
    raise KeyError(fixture_id)


TRACE = [
    {"t_ms": 0, "action": "launch", "target": "",
     "result": "activity-resumed"},
    {"t_ms": 1400, "action": "tap", "target": "scan", "result": "ok"},
    {"t_ms": 3200, "action": "capture", "target": "camera",
     "result": "document-detected"},
    {"t_ms": 9800, "action": "accept-document", "target": "crop-confirm",
     "result": "ok"},
    {"t_ms": 11200, "action": "save", "target": "save-button",
     "result": "saved"},
]

STEP_NAMES = ["01-launch", "02-tap", "03-capture", "04-accept-document",
              "05-save"]


def _metadata(run_id: str, subject: str) -> dict:
    return {
        "run_id": run_id,
        "scenario": "single-document-capture",
        "subject": subject,
        "provider": {
            "slug": "e2b",
            "capabilities": {
                "gui": True, "adb": True, "android_emulator": True,
                "android_sdk": True, "android_cli": True,
                "emulator_acceleration": "none", "camera_fixture": True,
            },
            "environment_id": f"env-e2b-e2e-{subject}",
        },
        "application": {
            "package": ("com.intsig.camscanner" if subject == "reference"
                        else "org.payswap.camscan"),
            "version_name": "7.25.5" if subject == "reference" else "0.1.0",
            "version_code": 2609020000 if subject == "reference" else 1,
            "installer_sha256": "5b2c8a7fd1e94a52e3a11a76b8c3a8e2"
                                "46c95fd9a01b3c7d82e5a1f0c4d6e8a0",
        },
        "device": {
            "model": "Pixel 4 (AVD pixel_4)",
            "android_version": "11",
            "screen": "1080x2280@440dpi",
            "locale": "en-US",
            "timezone": "UTC",
            "permission_baseline": {"android.permission.CAMERA": True},
        },
        "fixtures": [corpus_fixture("clean-a4")],
        "action_trace": [dict(step) for step in TRACE],
        "started_at": "2026-09-23T12:00:00Z",
        "finished_at": "2026-09-23T12:01:04Z",
    }


def _stage_subject(base: Path, run_id: str, subject: str) -> Path:
    """A runner-style single-subject run dir for evidence-cli bundle."""
    stage = base / "staging" / subject / run_id
    sub = stage / subject
    (sub / "screenshots").mkdir(parents=True)
    (sub / "ui").mkdir()
    (sub / "logs").mkdir()
    (sub / "recordings").mkdir()
    (sub / "outputs").mkdir()
    for name in STEP_NAMES:
        (sub / "screenshots" / f"{name}.png").write_bytes(
            tiny_png(name))            # same bytes both sides
        (sub / "ui" / f"{name}.xml").write_bytes(tiny_xml(name))
    (sub / "logs" / "logcat.txt").write_bytes(b"12:00:00 launch ok\n")
    (sub / "logs" / "app-events.json").write_bytes(b"[]\n")
    (sub / "recordings" / "screen.mp4").write_bytes(b"\x00\x00\x00\x08ftyp")
    (sub / "outputs" / "document.pdf").write_bytes(b"%PDF-1.4 synth\n")
    (sub / "outputs" / "page-01.jpg").write_bytes(
        b"\xff\xd8\xff\xe0\x00\x10JFIFsynth\xff\xd9")
    (stage / "scenario.yaml").write_text(SCENARIO_S004, encoding="utf-8")
    (stage / "run-metadata.json").write_text(
        json.dumps(_metadata(run_id, subject), indent=2, sort_keys=True)
        + "\n", encoding="utf-8")
    return stage


def _assemble_paired_run(base: Path, run_id: str) -> Path:
    """EVIDENCE.md layout decision: each subject subtree carries its own
    manifest.json at the subtree root; scenario.yaml at the run root."""
    run = base / "runs" / run_id
    run.mkdir(parents=True)
    for subject in ("reference", "implementation"):
        stage = base / "staging" / subject / run_id
        (stage / subject).rename(run / subject)
        (stage / "manifest.json").rename(run / subject / "manifest.json")
    (base / "staging" / "reference" / run_id / "scenario.yaml").rename(
        run / "scenario.yaml")
    return run


def test_bundle_produces_consumable_pair(tmp_path: Path) -> None:
    run_id = "20260923T120000Z-S004-e2e01"
    for subject in ("reference", "implementation"):
        stage = _stage_subject(tmp_path, run_id, subject)
        result = bundle_run(stage, fixtures_ref=REPO_ROOT)
        assert result.ok, result.problems
        assert result.subject == subject
        assert result.wrote_manifest
        # the manifest is exactly the 11-key contract
        manifest = jsonio_load(stage / "manifest.json")
        assert set(manifest) == {
            "run_id", "scenario", "subject", "provider", "application",
            "device", "fixtures", "artifacts", "action_trace",
            "started_at", "finished_at"}
    run_dir = _assemble_paired_run(tmp_path, run_id)
    masks = tmp_path / "masks-empty"
    masks.mkdir()
    result = compare_run(run_id, runs_dir=tmp_path / "runs",
                         masks_dir=masks)
    assert result.diff["counts"] == {"critical": 0, "high": 0,
                                     "medium": 0, "low": 0}
    assert result.verdict["verdict"] == "PASS"
    # sidecars were derived by bundle (outputs/ requires them)
    assert (run_dir / "implementation" / "outputs" /
            "document.pdf.sha256").is_file()
    # determinism of the assembled pipeline
    text = (run_dir / "reconciliation" / "diff.json").read_text(
        encoding="utf-8")
    again = compare_run(run_id, runs_dir=tmp_path / "runs",
                        masks_dir=masks)
    assert (run_dir / "reconciliation" / "diff.json").read_text(
        encoding="utf-8") == text
    assert again.verdict["verdict"] == "PASS"


def test_bundle_step_evidence_missing_detected(tmp_path: Path) -> None:
    """A step evidence gap introduced BEFORE bundling surfaces as an
    artifacts-dimension divergence (high for screenshots)."""
    run_id = "20260923T120000Z-S004-e2e02"
    for subject in ("reference", "implementation"):
        stage = _stage_subject(tmp_path, run_id, subject)
        if subject == "implementation":
            (stage / "implementation" / "ui" / "03-capture.xml").unlink()
        result = bundle_run(stage, fixtures_ref=REPO_ROOT)
        assert result.ok, result.problems
    run_dir = _assemble_paired_run(tmp_path, run_id)
    masks = tmp_path / "masks-empty"
    masks.mkdir()
    result = compare_run(run_id, runs_dir=tmp_path / "runs",
                         masks_dir=masks)
    ids = {entry["id"] for entry in result.diff["entries"]}
    assert "artifacts-step-03-implementation-ui" in ids
    assert result.verdict["verdict"] == "PARTIAL"
