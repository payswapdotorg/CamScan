"""Shared test helpers: deterministic fake-fs run trees + tiny artifacts.

Everything here is byte-deterministic (no randomness, no timestamps of
the test's own making) so manifests are reproducible.
"""
from __future__ import annotations

import struct
import zlib
from pathlib import Path

from tools.evidence_cli.jsonio import dumps_deterministic, load

REPO_ROOT = Path(__file__).resolve().parents[3]
CORPUS_MANIFEST = REPO_ROOT / "lab" / "fixtures" / "manifest.json"
SCENARIO_S004 = (REPO_ROOT / "lab" / "scenarios"
                 / "S004-single-document-capture.yaml").read_text()


# ------------------------------------------------------------ tiny bytes

def _png_chunk(kind: bytes, data: bytes) -> bytes:
    return (struct.pack(">I", len(data)) + kind + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))


def tiny_png(marker: str = "camscan") -> bytes:
    """A real 1x1 grayscale PNG (deterministic per marker)."""
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)
    idat = zlib.compress(b"\x00" + marker.encode())
    return (b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", ihdr)
            + _png_chunk(b"IDAT", idat) + _png_chunk(b"IEND", b""))


def tiny_jpg(marker: str = "camscan") -> bytes:
    return b"\xff\xd8\xff\xe0\x00\x10JFIF" + marker.encode() + b"\xff\xd9"


def tiny_mp4(marker: str = "camscan") -> bytes:
    body = b"\x00\x00\x00\x08ftypmp42" + marker.encode()
    return struct.pack(">I", len(body)) + b"ftyp" + body[4:]


def tiny_pdf(marker: str = "camscan") -> bytes:
    return (b"%PDF-1.4\n% " + marker.encode()
            + b"\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< >>\n"
            b"%%EOF\n")


def tiny_xml(step: str) -> bytes:
    return (f'<?xml version="1.0"?><hierarchy rotation="0">'
            f'<node step="{step}" bounds="[0,0][1080,2280]"/>'
            f"</hierarchy>\n").encode()


# ------------------------------------------------------------- metadata

def corpus_fixture(fixture_id: str = "clean-a4") -> dict:
    """A real {id, sha256} entry from the committed fixture corpus."""
    doc = load(CORPUS_MANIFEST)
    for group, files_key in (("documents", "files"), ("sequences", "frames")):
        for entry in doc.get(group, []):
            if entry["id"] == fixture_id:
                return {"id": fixture_id,
                        "sha256": entry[files_key][0]["sha256"]}
    raise KeyError(fixture_id)


def valid_metadata(run_id: str,
                   subject: str = "implementation",
                   scenario: str = "single-document-capture") -> dict:
    """Runner-style run-metadata.json content (schema-valid)."""
    return {
        "run_id": run_id,
        "scenario": scenario,
        "subject": subject,
        "provider": {
            "slug": "e2b",
            "capabilities": {
                "gui": True, "adb": True, "android_emulator": True,
                "emulator_acceleration": "none", "camera_fixture": True,
                "screenshots": True, "recording": True, "snapshot": True,
                "persistent": False, "android_studio": False,
            },
            "environment_id": "env-e2b-test01",
        },
        "application": {
            "package": "org.payswap.camscan",
            "version_name": "0.1.0",
            "version_code": 1,
            "installer_sha256":
                "5b2c8a7fd1e94a52e3a11a76b8c3a8e2"
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
        "action_trace": [
            {"t_ms": 0, "action": "launch", "target": "",
             "result": "activity-resumed"},
            {"t_ms": 1400, "action": "tap", "target": "scan",
             "result": "ok"},
            {"t_ms": 3200, "action": "capture", "target": "camera",
             "result": "document-detected"},
            {"t_ms": 9800, "action": "accept-document",
             "target": "crop-confirm", "result": "ok"},
            {"t_ms": 11200, "action": "save", "target": "save-button",
             "result": "saved"},
        ],
        "started_at": "2026-09-22T10:30:00Z",
        "finished_at": "2026-09-22T10:31:04Z",
    }


# ------------------------------------------------------------- run tree

def build_run_tree(base: Path, *,
                   run_id: str = "20260922T103000Z-S004-unit01",
                   subject: str = "implementation",
                   metadata: dict | None = None,
                   with_scenario: bool = True,
                   with_metadata: bool = True) -> Path:
    """A valid runner-style run dir: scenario + metadata + full tree."""
    run = base / run_id
    sub = run / subject
    (sub / "screenshots").mkdir(parents=True)
    (sub / "ui").mkdir()
    (sub / "logs").mkdir()
    (sub / "recordings").mkdir()
    (sub / "outputs").mkdir()

    (sub / "screenshots" / "01-launch.png").write_bytes(tiny_png("launch"))
    (sub / "screenshots" / "02-scan.png").write_bytes(tiny_png("scan"))
    (sub / "screenshots" / "03-preview.png").write_bytes(tiny_png("preview"))
    (sub / "ui" / "01-launch.xml").write_bytes(tiny_xml("launch"))
    (sub / "ui" / "02-scan.xml").write_bytes(tiny_xml("scan"))
    (sub / "logs" / "logcat.txt").write_bytes(
        b"09-22 10:30:00.000 I/CamScan( 1234): launch\n")
    (sub / "logs" / "app-events.json").write_bytes(
        b'[{"t_ms": 0, "event": "launch"}]\n')
    (sub / "recordings" / "screen.mp4").write_bytes(tiny_mp4("screen"))
    (sub / "outputs" / "document.pdf").write_bytes(tiny_pdf("document"))
    (sub / "outputs" / "page-01.jpg").write_bytes(tiny_jpg("page-01"))
    (sub / "outputs" / "ocr.txt").write_bytes(
        b"CAMSCAN PARITY LAB\nFixture Document A-001\n")

    if with_scenario:
        (run / "scenario.yaml").write_text(SCENARIO_S004, encoding="utf-8")
    if with_metadata:
        (run / "run-metadata.json").write_text(
            dumps_deterministic(metadata or valid_metadata(run_id, subject)),
            encoding="utf-8")
    return run


# ------------------------------------------------------------- r2 env

#: moto interception needs an AWS-shaped endpoint (see README).
MOTO_ENV = {
    "R2_ACCESS_KEY_ID": "test-key-id-000",
    "R2_SECRET_ACCESS_KEY": "test-secret-000",
    "R2_ENDPOINT": "https://s3.us-east-1.amazonaws.com",
    "R2_BUCKET": "camscan-evidence-test",
}


def set_moto_env(monkeypatch) -> None:
    for key, value in MOTO_ENV.items():
        monkeypatch.setenv(key, value)
