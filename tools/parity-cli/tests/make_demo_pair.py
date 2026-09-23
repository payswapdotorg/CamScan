#!/usr/bin/env python3
"""Regenerate the committed synthetic demo pair (NOT a pytest module).

Builds ``runs/20260923T120000Z-S004-synth01/`` — the verification
fixture pair for CAMSCAN-005 — through the REAL evidence pipeline:

1. two runner-style single-subject run trees (tiny deterministic
   artifacts, real S004 scenario copy, run-metadata.json) are bundled
   by ``tools.evidence_cli.bundle_run`` (the reference manifest
   writer; fixtures checked against the real lab/fixtures corpus);
2. the two subject subtrees + their manifests are assembled into the
   paired run dir per the EVIDENCE.md layout decision (each subject
   subtree carries its own manifest.json; scenario.yaml at the run
   root);
3. a SYNTHETIC-RUN.md marker documents provenance (no device, no app
   run — parity-cli test fixture data only).

The pair deliberately carries ONE medium divergence (the
implementation is missing the step-03 ui dump) and two low output
sha divergences, and exercises the shipped premium-upsell mask
(reference trace has a 6th, CamScanner-only step) → verdict PARTIAL.

Usage:
    python3 tools/parity-cli/tests/make_demo_pair.py [--out DIR]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zlib
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[3]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from tools.evidence_cli.bundle import bundle_run
from tools.evidence_cli.jsonio import load as jsonio_load

RUN_ID = "20260923T120000Z-S004-synth01"
SCENARIO_FILE = (_REPO_ROOT / "lab" / "scenarios"
                 / "S004-single-document-capture.yaml")
CORPUS = _REPO_ROOT / "lab" / "fixtures" / "manifest.json"
DEMO_NOTE = """\
# SYNTHETIC RUN — parity-cli verification fixture (CAMSCAN-005)

This run dir is **fixture data for tools/parity-cli**, not a device
observation: the two bundles were produced by
`tools/evidence-cli bundle` from tiny synthetic artifacts (no emulator,
no app, no network) and assembled into the paired layout per
`lab/evidence/EVIDENCE.md`. Regenerate with:

    python3 tools/parity-cli/tests/make_demo_pair.py

The pair deliberately carries one medium divergence (implementation
missing the step-03 ui dump) and two low output sha divergences, and
exercises the shipped reference-app step mask (`masks/
single-document-capture.json`: the reference trace has a 6th
CamScanner-only premium-upsell step) → verdict PARTIAL.
"""

REFERENCE_TRACE = [
    {"t_ms": 0, "action": "launch", "target": "",
     "result": "activity-resumed"},
    {"t_ms": 1400, "action": "tap", "target": "scan", "result": "ok"},
    {"t_ms": 3200, "action": "capture", "target": "camera",
     "result": "document-detected"},
    {"t_ms": 9800, "action": "accept-document", "target": "crop-confirm",
     "result": "ok"},
    # reference-app-specific step (masked in parity comparison):
    {"t_ms": 12400, "action": "tap", "target": "premium-upsell-dismiss",
     "result": "ok"},
    {"t_ms": 13600, "action": "save", "target": "save-button",
     "result": "saved"},
]

IMPLEMENTATION_TRACE = [
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


def tiny_jpg(marker: str) -> bytes:
    return (b"\xff\xd8\xff\xe0\x00\x10JFIF" + marker.encode()
            + b"\xff\xd9")


def tiny_pdf(marker: str) -> bytes:
    return (b"%PDF-1.4\n% " + marker.encode()
            + b"\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< >>\n"
            b"%%EOF\n")


def corpus_fixture(fixture_id: str) -> dict:
    doc = jsonio_load(CORPUS)
    for group, files_key in (("documents", "files"), ("sequences",
                                                      "frames")):
        for entry in doc.get(group, []):
            if entry["id"] == fixture_id:
                return {"id": fixture_id,
                        "sha256": entry[files_key][0]["sha256"]}
    raise KeyError(fixture_id)


def _metadata(subject: str) -> dict:
    is_reference = subject == "reference"
    trace = REFERENCE_TRACE if is_reference else IMPLEMENTATION_TRACE
    return {
        "run_id": RUN_ID,
        "scenario": "single-document-capture",
        "subject": subject,
        "provider": {
            "slug": "e2b",
            "capabilities": {
                "gui": True, "adb": True, "android_emulator": True,
                "android_sdk": True, "android_cli": True,
                "emulator_acceleration": "none", "camera_fixture": True,
                "screenshots": True, "recording": True, "snapshot": True,
                "persistent": False, "android_studio": False,
            },
            "environment_id": f"env-e2b-synth-{'ref' if is_reference
                                                 else 'impl'}",
        },
        "application": {
            "package": ("com.intsig.camscanner" if is_reference
                        else "org.payswap.camscan"),
            "version_name": "7.25.5" if is_reference else "0.1.0",
            "version_code": 2609020000 if is_reference else 1,
            "installer_sha256": (
                "7ef8e46525a1210bbaa332d5f5d560ce"
                "30d768b4375ea92265d2e292d25dba65"
                if is_reference else
                hashlib.sha256(b"camscan-synth01-debug-apk").hexdigest()),
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
        "action_trace": [dict(step) for step in trace],
        "started_at": "2026-09-23T12:00:00Z",
        "finished_at": "2026-09-23T12:01:04Z",
    }


def _write_step_artifacts(stage: Path, subject: str) -> None:
    sub = stage / subject
    (sub / "screenshots").mkdir(parents=True)
    (sub / "ui").mkdir()
    (sub / "logs").mkdir()
    (sub / "recordings").mkdir()
    (sub / "outputs").mkdir()
    trace = REFERENCE_TRACE if subject == "reference" \
        else IMPLEMENTATION_TRACE
    for number, step in enumerate(trace, start=1):
        name = f"{number:02d}-{step['action']}"
        (sub / "screenshots" / f"{name}.png").write_bytes(
            tiny_png(f"{subject}-{name}"))
        if subject == "implementation" and number == 3:
            continue  # deliberate: step-03 ui dump missing (→ medium)
        (sub / "ui" / f"{name}.xml").write_bytes(tiny_xml(name))
    (sub / "logs" / "logcat.txt").write_bytes(
        f"09-23 12:00:00.000 I/{subject}: launch\n".encode())
    (sub / "logs" / "app-events.json").write_bytes(
        b'[{"t_ms": 0, "event": "launch"}]\n')
    (sub / "recordings" / "screen.mp4").write_bytes(
        b"\x00\x00\x00\x08ftypmp42" + subject.encode())
    # outputs: same types + counts both sides, different bytes (→ low)
    (sub / "outputs" / "document.pdf").write_bytes(
        tiny_pdf(f"{subject}-document"))
    (sub / "outputs" / "page-01.jpg").write_bytes(
        tiny_jpg(f"{subject}-page-01"))


def build(out_root: Path) -> Path:
    staging = out_root / ".demo-staging"
    if staging.exists():
        raise SystemExit(f"staging dir exists: {staging} (remove first)")
    for subject in ("reference", "implementation"):
        stage = staging / subject / RUN_ID
        _write_step_artifacts(stage, subject)
        (stage / "scenario.yaml").write_text(
            SCENARIO_FILE.read_text(encoding="utf-8"), encoding="utf-8")
        (stage / "run-metadata.json").write_text(
            json.dumps(_metadata(subject), indent=2, sort_keys=True)
            + "\n", encoding="utf-8")
        result = bundle_run(stage, fixtures_ref=_REPO_ROOT)
        if not result.ok:
            for problem in result.problems:
                print(f"  - {problem}", file=sys.stderr)
            raise SystemExit(f"evidence-cli bundle FAILED for {subject}")
        print(f"bundled {subject}: {len(result.artifacts)} artifacts "
              f"({result.total_bytes} bytes)")

    run_dir = out_root / "runs" / RUN_ID
    if run_dir.exists():
        raise SystemExit(f"run dir exists: {run_dir} (remove first)")
    run_dir.mkdir(parents=True)
    for subject in ("reference", "implementation"):
        stage = staging / subject / RUN_ID
        (stage / subject).rename(run_dir / subject)
        (stage / "manifest.json").rename(run_dir / subject /
                                         "manifest.json")
    (staging / "reference" / RUN_ID / "scenario.yaml").rename(
        run_dir / "scenario.yaml")
    (run_dir / "SYNTHETIC-RUN.md").write_text(DEMO_NOTE, encoding="utf-8")
    import shutil
    shutil.rmtree(staging)
    print(f"assembled {run_dir}")
    return run_dir


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=str(_REPO_ROOT),
                        help="repo root whose runs/ receives the pair "
                             "(default: this repo)")
    args = parser.parse_args(argv)
    build(Path(args.out))
    print("next: python3 tools/parity-cli/main.py compare "
          f"{RUN_ID}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
