"""tools/__init__: generalized dashed-dir alias registration.

adb-bridge's registration must stay behaviorally byte-identical (its 81
tests are the regression gate); evidence-cli gets the same mechanism.
"""
from __future__ import annotations

import subprocess
import sys

import pytest


def test_adb_bridge_alias_unchanged():
    import tools.adb_bridge as pkg
    from tools.adb_bridge.bridge import AdbBridge

    import tools
    assert pkg.AdbBridge is AdbBridge
    assert (sys.modules["tools"].__file__ and
            __import__("pathlib").Path(tools.__file__).parent
            .joinpath("adb-bridge").is_dir())


def test_adb_bridge_export_list_exact():
    from tools import _ALIAS_SPECS
    alias, primary, exports = _ALIAS_SPECS["adb-bridge"]
    assert alias == "tools.adb_bridge"
    assert primary == "bridge"
    assert exports == ("AdbBridge", "VerbResult", "ResolvedTarget",
                       "Predicate", "TargetRegistry", "TargetEntry",
                       "UnknownTargetError", "LabProviderLike",
                       "EnvironmentId", "DEFAULT_VERB_TIMEOUT_S",
                       "DEFAULT_POLL_S")


def test_evidence_cli_alias_reexports_api():
    import tools.evidence_cli as pkg
    from tools.evidence_cli.api import bundle_run
    assert pkg.bundle_run is bundle_run
    with pytest.raises(AttributeError):
        getattr(pkg, "nonexistent_name")  # noqa: B009


def test_parity_cli_alias_registered():
    # CAMSCAN-005 landed parity-cli's implementation → the generalized
    # dashed-dir alias registers it (the README-only placeholder era that
    # the old not-registered assertion pinned is over).
    import tools.parity_cli as pkg
    assert pkg.__name__ == "tools.parity_cli"
    import tools.parity_cli.compare as compare_mod
    assert hasattr(compare_mod, "compare_run")


def test_sibling_aliases_register_and_import():
    import tools.lab_cli.validate as validate_mod
    import tools.reference_observe.observe as observe_mod
    assert hasattr(validate_mod, "main")
    assert observe_mod.__name__ == "tools.reference_observe.observe"


def test_subprocess_script_mode():
    """python3 tools/evidence-cli/main.py --help must work with no
    package context (the lead's verbatim invocation form)."""
    from helpers import REPO_ROOT
    proc = subprocess.run(
        [sys.executable, str(REPO_ROOT / "tools" / "evidence-cli"
                              / "main.py"), "--help"],
        capture_output=True, text=True, timeout=60, check=False)
    assert proc.returncode == 0
    assert "bundle" in proc.stdout and "upload" in proc.stdout \
        and "verify" in proc.stdout
