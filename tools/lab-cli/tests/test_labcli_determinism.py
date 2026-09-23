"""Determinism (the 005 rules): identical inputs ⇒ byte-identical
outputs.

- two recording runs of the same scenario with the same --stamp into
  different runs dirs produce byte-identical run trees (every file,
  including both manifests and the reconciliation outputs the run
  subcommand wrote through parity-cli);
- the plan output is byte-stable (also pinned in test_plan.py);
- ``report --json`` is byte-stable (also pinned in test_report.py).
"""
from __future__ import annotations

import hashlib
from pathlib import Path

from labcli_helpers import run_cli


def _tree_digest(root: Path) -> dict[str, str]:
    """relpath → sha256 for every file under root (deterministic)."""
    out: dict[str, str] = {}
    for path in sorted(root.rglob("*")):
        if path.is_file():
            out[str(path.relative_to(root))] = hashlib.sha256(
                path.read_bytes()).hexdigest()
    return out


def test_recording_runs_are_byte_identical(tmp_path):
    digests = []
    for name in ("first", "second"):
        runs = tmp_path / name / "runs"
        proc = run_cli([
            "run", "S003", "--driver", "recording",
            "--runs-dir", str(runs),
            "--gaps-dir", str(tmp_path / name / "gaps"),
            "--stamp", "20260923T160000Z",
        ])
        assert proc.returncode == 0, proc.stdout + proc.stderr
        digests.append(_tree_digest(runs))
    assert digests[0] == digests[1]
    # and the pair actually reconciled (not an empty tree)
    assert any(name.startswith("20260923T160000Z-S003-recording")
               for name in digests[0])
    assert any(name.endswith("reconciliation/verdict.json")
               for name in digests[0])


def test_report_json_byte_identical(tmp_path):
    runs = tmp_path / "runs"
    proc = run_cli([
        "run", "S002", "--driver", "recording",
        "--runs-dir", str(runs), "--stamp", "20260923T170000Z",
    ])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    outputs = []
    for _ in range(2):
        report = run_cli(["report", "--runs-dir", str(runs),
                          "--repo-root", str(tmp_path), "--json"])
        assert report.returncode == 0, report.stdout + report.stderr
        outputs.append(report.stdout)
    assert outputs[0] == outputs[1]
