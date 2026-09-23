"""CLI surface: exit codes and usage errors (subprocess, script mode).

Exit-code doctrine (mirrors the siblings): 0 ok — including honest
planned NO-OPs and FAIL/BLOCKED verdicts (a successful comparison is
not a tool error); 1 operational failure; 2 usage (argparse). The CI
entry ``python3 tools/lab-cli/validate.py`` keeps working verbatim.
"""
from __future__ import annotations

from labcli_helpers import LAB_CLI_MAIN, VALIDATE_PY, run_cli, run_validate_py


def test_no_command_is_usage_error():
    proc = run_cli([])
    assert proc.returncode == 2


def test_help_exits_zero():
    proc = run_cli(["--help"])
    assert proc.returncode == 0
    for word in ("validate", "run", "report"):
        assert word in proc.stdout
    proc = run_cli(["run", "--help"])
    assert proc.returncode == 0
    assert "--plan" in proc.stdout
    assert "--driver" in proc.stdout


def test_run_requires_scenario_or_all():
    proc = run_cli(["run"])
    assert proc.returncode == 2


def test_run_unknown_scenario_is_operational_failure():
    proc = run_cli(["run", "S999", "--plan"])
    assert proc.returncode == 1
    assert "unknown scenario 'S999'" in proc.stderr


def test_run_bad_env_choice_is_usage_error():
    proc = run_cli(["run", "S001", "--env", "staging"])
    assert proc.returncode == 2


def test_run_bad_stamp_is_usage_error():
    proc = run_cli(["run", "S001", "--driver", "recording",
                    "--stamp", "2026-09-23"])
    assert proc.returncode == 2


def test_run_bad_driver_choice_is_usage_error():
    proc = run_cli(["run", "S001", "--driver", "phantom"])
    assert proc.returncode == 2


def test_validate_ci_entry_unchanged_and_green():
    proc = run_validate_py()
    assert proc.returncode == 0
    assert proc.stdout.startswith("OK: 18 scenarios valid")
    assert "gates green" in proc.stdout


def test_script_mode_direct_invocation():
    """python3 tools/lab-cli/main.py works with no package context."""
    import subprocess
    import sys

    proc = subprocess.run(
        [sys.executable, str(LAB_CLI_MAIN), "run", "S001", "--plan"],
        capture_output=True, text=True, timeout=120, check=False,
        cwd="/tmp")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "planned: S001 application-launch" in proc.stdout


def test_validate_py_path_is_the_lead_file():
    """The CI entry is byte-identical to the lead's validator — we never
    forked or wrapped it (import, not copy)."""
    text = VALIDATE_PY.read_text(encoding="utf-8")
    assert "check_scenarios" in text
    assert "KNOWN_FIXTURE_IDS" in text
