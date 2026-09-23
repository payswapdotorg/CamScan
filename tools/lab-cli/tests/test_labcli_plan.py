"""``run --plan`` on the REAL scenario corpus (no provider needed).

Pinned properties:

- S001 (schedulable): a planned line naming the e2b provider + the
  live driver for env=implementation, and — since CAMSCAN-009 — the
  live reference driver resolution for env=reference
  (driver=reference-live);
- S004 (camera_fixture): NO-OP for BOTH envs — no capable provider on
  record, with the scheduler's unsatisfied reason printed verbatim;
- ``--driver recording`` plans BOTH envs (the recording driver is on
  record everywhere) — while S004 still NO-OPs (no provider, no run);
- ``--all`` covers the whole corpus (18 scenarios), deterministically;
- the step→verb mapping resolves EVERY step of EVERY corpus scenario
  (unknown actions would raise — plan lines exist for all steps);
- ``--plan`` writes NOTHING under runs/ and is byte-stable across
  invocations.
"""
from __future__ import annotations

import pytest
import yaml
from labcli_helpers import REPO_ROOT, run_cli

SCEN_DIR = REPO_ROOT / "lab" / "scenarios"
RUNS_DIR = REPO_ROOT / "runs"


def _corpus() -> list[tuple[str, dict]]:
    """(stem, doc) pairs, deterministic order."""
    out = []
    for path in sorted(SCEN_DIR.glob("*.yaml")):
        out.append((path.name.split("-", 1)[0],
                    yaml.safe_load(path.read_text(encoding="utf-8"))))
    return out


def test_plan_s001_live_default():
    proc = run_cli(["run", "S001", "--plan"])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    out = proc.stdout
    assert "plan: S001 application-launch status=UNKNOWN" in out
    assert "providers on record: e2b" in out
    # reference env: the live reference driver resolves (CAMSCAN-009)
    assert ("planned: S001 application-launch env=reference provider=e2b "
            "driver=reference-live app=com.intsig.camscanner "
            "steps=1") in out
    # implementation env: provider + live driver on record
    assert ("planned: S001 application-launch env=implementation "
            "provider=e2b driver=e2b-live app=org.payswap.camscan "
            "steps=1") in out
    # the one step maps to a verb
    assert "step 01 launch → launch(app=<app>)" in out
    assert "budget: timeout_seconds=1800 step_timeout_seconds=120" in out


def test_plan_s004_no_capable_provider():
    proc = run_cli(["run", "S004", "--plan"])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    out = proc.stdout
    for env in ("reference", "implementation"):
        assert (f"planned: S004 single-document-capture env={env} "
                "provider=none NO-OP (no capable provider on record: "
                "e2b: camera_fixture: requirement True not satisfied by "
                "False; e2b-reference: camera_fixture: requirement True "
                "not satisfied by False)") in out
    assert "driver=e2b-live" not in out


def test_plan_recording_driver_plans_both_envs():
    proc = run_cli(["run", "S001", "--plan", "--driver", "recording"])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    out = proc.stdout
    assert ("planned: S001 application-launch env=reference provider=e2b "
            "driver=recording app=com.intsig.camscanner steps=1") in out
    assert ("planned: S001 application-launch env=implementation "
            "provider=e2b driver=recording app=org.payswap.camscan "
            "steps=1") in out
    assert "NO-OP" not in out


def test_plan_recording_driver_s004_still_noop():
    proc = run_cli(["run", "S004", "--plan", "--driver", "recording"])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "NO-OP" in proc.stdout
    assert "no capable provider on record" in proc.stdout
    assert "driver=recording" not in proc.stdout


def test_plan_all_covers_corpus():
    corpus = _corpus()
    assert len(corpus) == 18
    camera = [(stem, doc) for stem, doc in corpus
              if (doc["meta"].get("requires") or {}).get("camera_fixture")]
    plain = [pair for pair in corpus if pair not in camera]
    assert len(camera) == 9 and len(plain) == 9

    proc = run_cli(["run", "--all", "--plan"])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    out = proc.stdout
    for stem, doc in plain:
        assert f"plan: {stem} {doc['id']} status=" in out
        assert (f"planned: {stem} {doc['id']} env=implementation "
                "provider=e2b driver=e2b-live") in out
        assert (f"planned: {stem} {doc['id']} env=reference provider=e2b "
                "driver=reference-live") in out
    for stem, doc in camera:
        assert (f"planned: {stem} {doc['id']} env=implementation "
                "provider=none NO-OP") in out
        assert (f"planned: {stem} {doc['id']} env=reference provider=none "
                "NO-OP") in out


def test_plan_writes_nothing_and_is_deterministic(tmp_path):
    before = sorted(p.name for p in RUNS_DIR.iterdir()) \
        if RUNS_DIR.is_dir() else []
    first = run_cli(["run", "--all", "--plan",
                     "--runs-dir", str(tmp_path / "runs")])
    second = run_cli(["run", "--all", "--plan",
                      "--runs-dir", str(tmp_path / "runs")])
    after = sorted(p.name for p in RUNS_DIR.iterdir()) \
        if RUNS_DIR.is_dir() else []
    assert first.returncode == 0 and second.returncode == 0
    assert first.stdout == second.stdout  # byte-stable plan output
    assert before == after                # the repo's runs/ untouched
    assert not (tmp_path / "runs").exists()  # --plan provisions NOTHING


def test_plan_scenario_id_spellings():
    for spelling in ("S001", "application-launch",
                     "S001-application-launch.yaml"):
        proc = run_cli(["run", spelling, "--plan"])
        assert proc.returncode == 0, proc.stdout + proc.stderr
        assert "plan: S001 application-launch" in proc.stdout


@pytest.mark.parametrize("scenario_id", [doc["id"] for _, doc in _corpus()])
def test_every_corpus_scenario_plans(scenario_id):
    proc = run_cli(["run", scenario_id, "--plan"])
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert proc.stdout.startswith("plan: ")
    assert "providers on record: e2b" in proc.stdout
    # every step of the scenario rendered a step line with a verb
    doc = next(d for _, d in _corpus() if d["id"] == scenario_id)
    assert proc.stdout.count(" → ") >= len(doc["steps"])
