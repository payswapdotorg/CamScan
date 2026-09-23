"""Validator parity: the ``validate`` subcommand IS the lead's validator.

Pinned properties (the 006 lesson — gates are only ever extended):

1. green plane: ``python3 tools/lab-cli/main.py validate`` and
   ``python3 tools/lab-cli/validate.py`` (the unchanged CI entry)
   produce byte-identical stdout and the same exit code (0);
2. known-bad control plane: both fail with byte-identical stdout and
   exit code 1 — the subcommand fails **exactly as before** (the
   corruption is a dropped required key, so the lead's own gate fires);
3. extension only ever adds: with the lead's gates green but the
   lab-cli composition surface broken, the SUBCOMMAND fails while the
   lead's validate.py alone stays green — an extension, never a
   loosening;
4. on the known-bad plane the extension is silent (output byte-equal to
   the lead's).
"""
from __future__ import annotations

import shutil

from labcli_helpers import (
    REPO_ROOT,
    copy_control_plane,
    corrupt_scenario,
    run_cli,
    run_validate_py,
)


def test_green_plane_byte_identical():
    direct = run_validate_py()
    subcommand = run_cli(["validate"])
    assert direct.returncode == 0, direct.stdout + direct.stderr
    assert subcommand.returncode == 0, subcommand.stdout + subcommand.stderr
    assert subcommand.stdout == direct.stdout
    assert subcommand.stderr == direct.stderr


def test_known_bad_control_plane_fails_exactly_as_before(tmp_path):
    repo = copy_control_plane(tmp_path / "bad-plane")
    corrupt_scenario(repo, "S001")
    copied_main = repo / "tools" / "lab-cli" / "main.py"

    direct = run_validate_py(cwd=repo)
    subcommand = run_cli(["validate"], cwd=repo, main=copied_main)

    assert direct.returncode == 1
    assert subcommand.returncode == 1
    # the lead's own gate fired with the same message...
    assert "title mirror mismatch" in direct.stdout
    assert "S001-application-launch.yaml" in direct.stdout
    assert "Corrupted title for the known-bad plane" in direct.stdout
    # ...and the subcommand output is byte-identical (extension silent)
    assert subcommand.stdout == direct.stdout
    assert subcommand.stderr == direct.stderr


def test_extension_composition_surface_only_ever_adds(tmp_path):
    repo = copy_control_plane(tmp_path / "broken-surface")
    copied_main = repo / "tools" / "lab-cli" / "main.py"
    # lead's gates stay green on this plane...
    shutil.rmtree(repo / "tools" / "parity-cli")
    direct = run_validate_py(cwd=repo)
    assert direct.returncode == 0, direct.stdout

    # ...but the subcommand's composition-surface extension fires.
    subcommand = run_cli(["validate"], cwd=repo, main=copied_main)
    assert subcommand.returncode == 1
    assert "VALIDATION FAILED" in subcommand.stdout
    assert "tools/parity-cli/main.py" in subcommand.stdout
    assert "composition surface" in subcommand.stdout
