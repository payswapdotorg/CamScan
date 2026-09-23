"""Shared fixtures/helpers for the lab-cli test suite (hermetic).

The known-bad control plane + composition-surface planes are built by
copying ``lab/`` + ``tools/`` into a tmp dir (self-contained: the copied
``tools/lab-cli/validate.py`` resolves its ROOT from its own location,
exactly like the real one), so tests never touch the repo's control
plane. No network, no device, no credentials anywhere in this suite.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
LAB_CLI_MAIN = REPO_ROOT / "tools" / "lab-cli" / "main.py"
VALIDATE_PY = REPO_ROOT / "tools" / "lab-cli" / "validate.py"

#: The committed synthetic demo pair (CAMSCAN-005 fixture data).
DEMO_PAIR_ID = "20260923T120000Z-S004-synth01"
DEMO_PAIR = REPO_ROOT / "runs" / DEMO_PAIR_ID


def run_cli(args: list[str], cwd: Path | None = None,
            timeout: int = 300,
            main: Path | None = None) -> subprocess.CompletedProcess[str]:
    """Run tools/lab-cli/main.py in a subprocess (script mode).

    ``main`` overrides the entry path (copied trees run their OWN
    main.py so the whole tool resolves inside the copy)."""
    entry = main or LAB_CLI_MAIN
    return subprocess.run(
        [sys.executable, str(entry), *args],
        capture_output=True, text=True, timeout=timeout, check=False,
        cwd=str(cwd or REPO_ROOT))


def run_validate_py(cwd: Path | None = None,
                    timeout: int = 300) -> subprocess.CompletedProcess[str]:
    """Run the CI entry ``python3 tools/lab-cli/validate.py`` verbatim
    (in a copied tree, pass the copy as cwd — the validator anchors to
    its own file location, so a copied validator validates the copy)."""
    target = (cwd / "tools" / "lab-cli" / "validate.py") if cwd \
        else VALIDATE_PY
    return subprocess.run(
        [sys.executable, str(target)],
        capture_output=True, text=True, timeout=timeout, check=False,
        cwd=str(cwd or REPO_ROOT))


def copy_control_plane(dest: Path) -> Path:
    """Copy lab/ + tools/ into ``dest`` (a self-contained repo plane)."""
    dest.mkdir(parents=True, exist_ok=True)
    for name in ("lab", "tools"):
        shutil.copytree(REPO_ROOT / name, dest / name,
                        ignore=shutil.ignore_patterns("__pycache__",
                                                      ".pytest_cache"))
    return dest


def corrupt_scenario(dest: Path, stem: str) -> Path:
    """Make the copied control plane known-bad: break the ledger↔yaml
    title mirror (a clean gate failure — the validator's own error
    path, deterministic message).

    Note: dropping a REQUIRED key (e.g. ``steps:``) makes the lead's
    ``check_ledger`` crash with a TypeError instead of printing a gate
    message (``file_stems`` then contains ``None``) — a latent
    validator bug filed in the CAMSCAN-007 report, not fixed here (the
    lead's file, gates only ever extended by the lead).
    """
    matches = sorted((dest / "lab" / "scenarios").glob(f"{stem}-*.yaml"))
    assert matches, f"no scenario file for {stem}"
    path = matches[0]
    lines = path.read_text(encoding="utf-8").splitlines()
    out = []
    for line in lines:
        if line.startswith("title:"):
            out.append("title: Corrupted title for the known-bad plane")
        else:
            out.append(line)
    path.write_text("\n".join(out) + "\n", encoding="utf-8")
    return path
