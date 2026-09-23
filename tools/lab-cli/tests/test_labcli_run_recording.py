"""RecordingDriver end-to-end: a paired run dir both envs → parity-cli
compare consumed it (the 005 synthetic pair shows the fixture pattern).

Pinned properties:

- ``run S00X --driver recording`` with a fixed ``--stamp`` produces the
  paired EVIDENCE.md layout: ``scenario.yaml`` at the run root, both
  subject subtrees carrying their OWN manifest.json (schema-valid per
  the evidence-cli validator), ``reconciliation/{diff,verdict}.json``;
- parity-cli's own staleness gate (``compare --check``) passes on the
  produced reconciliation outputs — they are up to date, not stale;
- the would-be calls are logged (``would:`` lines, per subject);
- teardown runs for every subject (``tore down`` lines);
- ``--env implementation`` alone → single-env run dir, compare skipped;
- S004 (no capable provider) → planned NO-OP, exit 0, no run dir;
- the teardown invariant: a driver whose execute RAISES still gets its
  teardown called and the run fails closed (runner-owned invariant,
  pinned with a stub driver in-process).
"""
from __future__ import annotations

from pathlib import Path

from labcli_helpers import REPO_ROOT, run_cli
from tools.evidence_cli.jsonio import load as jsonio_load
from tools.evidence_cli.schema import validate_manifest
from tools.lab_cli.drivers import (
    DriverHandle,
    ProvisionRequest,
)
from tools.lab_cli.evidence import execution_window
from tools.lab_cli.run import orchestrate_subject
from tools.lab_cli.scenarios import resolve_scenario
from tools.lab_cli.steps import plan_steps

STAMP = "20260923T140000Z"


def _run_recording(tmp_path: Path, scenario: str, *extra: str):
    runs = tmp_path / "runs"
    gaps = tmp_path / "gaps"
    proc = run_cli([
        "run", scenario, "--driver", "recording",
        "--runs-dir", str(runs), "--gaps-dir", str(gaps),
        "--stamp", STAMP, *extra,
    ])
    return proc, runs, gaps


def test_recording_run_end_to_end_paired_and_reconciled(tmp_path):
    proc, runs, gaps = _run_recording(tmp_path, "S001")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    out = proc.stdout

    run_dir = runs / f"{STAMP}-S001-recording"
    assert (run_dir / "scenario.yaml").is_file()
    for subject in ("reference", "implementation"):
        manifest_path = run_dir / subject / "manifest.json"
        assert manifest_path.is_file()
        doc = jsonio_load(manifest_path)
        assert validate_manifest(doc) == []
        assert doc["subject"] == subject
        assert doc["scenario"] == "application-launch"
        assert doc["run_id"] == f"{STAMP}-S001-recording"
        # deterministic evidence-derived timestamps (never wall clock)
        started, finished = execution_window(STAMP, 1)
        assert doc["started_at"] == started
        assert doc["finished_at"] == finished
    # reference = CamScanner, implementation = CamScan
    ref = jsonio_load(run_dir / "reference" / "manifest.json")
    impl = jsonio_load(run_dir / "implementation" / "manifest.json")
    assert ref["application"]["package"] == "com.intsig.camscanner"
    assert impl["application"]["package"] == "org.payswap.camscan"

    # parity-cli compare consumed the pair (via the run subcommand)
    verdict = jsonio_load(run_dir / "reconciliation" / "verdict.json")
    assert verdict["verdict"] == "PASS"
    assert verdict["scenario"] == "application-launch"
    assert verdict["counts"] == {"critical": 0, "high": 0,
                                 "medium": 0, "low": 0}
    diff = jsonio_load(run_dir / "reconciliation" / "diff.json")
    assert diff["entries"] == []

    # the would-be calls were logged per subject
    assert "reference: step 01 launch → would: launch(app=com.intsig.camscanner)" in out
    assert "implementation: step 01 launch → would: launch(app=org.payswap.camscan)" in out
    # teardown ran for both subjects
    assert out.count("tore down env-e2b-recording-") == 2
    # staging cleaned up
    assert not (runs / ".staging").exists()
    # no gap files for a PASS pair (gaps dir only exists when gaps were
    # written — parity-cli creates it on first write)
    assert not (gaps.exists() and list(gaps.glob("*.gap.yaml")))


def test_parity_cli_check_gate_passes_on_recording_outputs(tmp_path):
    proc, runs, _ = _run_recording(tmp_path, "S012")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    run_id = f"{STAMP}-S012-recording"
    check = run_cli_check(runs, run_id)
    assert check.returncode == 0, check.stdout + check.stderr
    assert "compare check ok" in check.stdout


def run_cli_check(runs: Path, run_id: str):
    import subprocess
    import sys

    return subprocess.run(
        [sys.executable, str(REPO_ROOT / "tools" / "parity-cli" / "main.py"),
         "compare", run_id, "--runs-dir", str(runs), "--check"],
        capture_output=True, text=True, timeout=300, check=False,
        cwd=str(REPO_ROOT))


def test_recording_run_single_env_skips_compare(tmp_path):
    proc, runs, _gaps = _run_recording(tmp_path, "S001", "--env",
                                       "implementation")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    run_dir = runs / f"{STAMP}-S001-recording"
    assert (run_dir / "implementation").is_dir()
    assert not (run_dir / "reference").exists()
    assert not (run_dir / "reconciliation").exists()
    assert "compare skipped — needs both envs" in proc.stdout


def test_recording_run_s004_noop_no_run_dir(tmp_path):
    proc, runs, _ = _run_recording(tmp_path, "S004")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert proc.stdout.count("NO-OP (no capable provider on record") == 2
    assert list(runs.glob(f"{STAMP}-S004-*")) == []


def test_recording_run_refuses_existing_run_dir(tmp_path):
    proc, runs, _ = _run_recording(tmp_path, "S001")
    assert proc.returncode == 0, proc.stdout
    again = run_cli([
        "run", "S001", "--driver", "recording",
        "--runs-dir", str(runs), "--stamp", STAMP,
    ])
    assert again.returncode == 1
    assert "run dir exists" in again.stdout


def test_recording_run_fixture_binding(tmp_path):
    proc, runs, _ = _run_recording(tmp_path, "S012")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    run_dir = runs / f"{STAMP}-S012-recording"
    for subject in ("reference", "implementation"):
        doc = jsonio_load(run_dir / subject / "manifest.json")
        assert doc["fixtures"] == [{
            "id": "clean-a4",
            "sha256": "d0b13b27d7ede73deff0a7a704fa63c2b6e2b7f53d2ff2fcadb"
                      "01faad1514b0e",
        }]


# ------------------------------------------------- teardown invariant (stub)

def test_live_driver_requires_api_key_before_any_provisioning(monkeypatch):
    """The credential gate fires BEFORE the lazy e2b import — no
    network, no SDK needed to prove the refusal (credentials come from
    the environment only, never from code/git)."""
    import pytest
    from tools.lab_cli.drivers import ProvisionRequest
    from tools.lab_cli.e2b_live import E2bLiveDriver
    from tools.lab_cli.scenarios import LabCliError

    monkeypatch.delenv("E2B_API_KEY", raising=False)
    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    driver = E2bLiveDriver()
    request = ProvisionRequest(
        run_id="20260923T140000Z-S001-live", subject="implementation",
        scenario=scenario, provider_report={"slug": "e2b"},
        step_timeout_s=120, timeout_s=1800)
    with pytest.raises(LabCliError) as excinfo:
        driver.provision(request)
    assert "E2B_API_KEY is not set" in str(excinfo.value)
    assert "refuses to provision" in str(excinfo.value)


def test_live_driver_env_guard(monkeypatch):
    """The live driver is on record for env=implementation only — a
    reference-env live run is refused loudly (the NO-OP planned: line
    in the runner covers it before the driver is even constructed)."""
    import pytest
    from tools.lab_cli.drivers import ProvisionRequest
    from tools.lab_cli.e2b_live import E2bLiveDriver
    from tools.lab_cli.scenarios import LabCliError

    # a placeholder (not a credential) so the earlier api-key gate passes
    monkeypatch.setenv("E2B_API_KEY", "placeholder-not-a-credential")
    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    driver = E2bLiveDriver()
    request = ProvisionRequest(
        run_id="20260923T140000Z-S001-live", subject="reference",
        scenario=scenario, provider_report={"slug": "e2b"},
        step_timeout_s=120, timeout_s=1800)
    with pytest.raises(LabCliError) as excinfo:
        driver.provision(request)
    assert "env=implementation" in str(excinfo.value)


class _ExplodingDriver:
    """A stub driver whose execute raises — pins the runner's invariant."""

    slug = "exploding-stub"
    torn_down = False

    def provision(self, request: ProvisionRequest) -> DriverHandle:
        return DriverHandle(subject=request.subject,
                            provider_slug="stub",
                            environment_id="env-stub")

    def execute(self, handle, request):
        raise RuntimeError("substrate exploded")

    def teardown(self, handle, error: str = "", emit=print) -> None:
        self.torn_down = True
        self.torn_down_error = error


def test_teardown_runs_when_execute_raises(tmp_path):
    scenario = resolve_scenario("S001", REPO_ROOT / "lab" / "scenarios")
    plans = plan_steps(scenario.steps, None)
    driver = _ExplodingDriver()
    lines: list[str] = []

    from tools.lab_cli.run import RunOptions

    opts = RunOptions(repo_root=REPO_ROOT, runs_dir=tmp_path / "runs",
                      gaps_dir=tmp_path / "gaps", registry=None,
                      emit=lines.append)
    result = orchestrate_subject(
        driver, scenario, "implementation", "20260923T140000Z-S001-stub",
        {"slug": "stub"}, plans, [], tmp_path, opts)

    assert result.ok is False
    assert "RuntimeError: substrate exploded" in result.reason
    assert driver.torn_down is True
    assert "substrate exploded" in driver.torn_down_error
