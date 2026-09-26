"""CAMSCAN-010K — the pairing guard: subject-collision semantics.

The 2026-09-26 11:41:34 UTC implementation-campaign launch (console
sequence, verbatim) paired the implementation subject into the EXISTING
reference run dir by reusing the reference run's stamp — exactly the
flow the evidence layer is built for (``assemble_subject`` reuses the
run dir, refuses only a same-subject subtree, shares ``scenario.yaml``
at the run root once) — and the CAMSCAN-007 blanket
``run_dir.exists()`` refusal killed it as an "operational failure"
before a single step ran:

    [2026-09-26 11:41:34 UTC] S001 attempt 1 starting (stamp
      20260925T180736Z, pairing into
      /home/z/camscan/runs/20260925T180736Z-S001-live)
    run: S001 application-launch env=implementation
      runs-dir=/home/z/camscan/runs
    run: run dir exists: /home/z/camscan/runs/20260925T180736Z-S001-live
      (pass --stamp/--suffix for a fresh id — never silently
      overwritten)
    [2026-09-26 11:41:34 UTC] S001 attempt 1: operational failure —
      fresh retry

Pinned here (hermetic — the recording driver, fixed stamps, no
substrate, no credentials, no network):

- pairing open: env=reference then env=implementation with the SAME
  ``--stamp`` → the second invocation emits the honest pairing line,
  runs ONLY the new subject, lands the implementation subtree beside
  the untouched reference subtree, and fires the partial→complete
  transition (parity-cli compare + verdict.json on the second
  invocation);
- the reference subject's manifest survives the pairing byte-identical
  (never silently overwritten) and the run root keeps exactly one
  ``scenario.yaml``, untouched by the second assemble (content + a
  pinned mtime — the shared-once contract, end to end);
- collision still refused: a third invocation env=reference with the
  same stamp exits 1 with the "run dir exists" message (now naming the
  colliding subjects) and leaves the pair untouched; the CAMSCAN-007
  regression (both envs into a fully-populated run dir —
  ``test_labcli_run_recording.py::test_recording_run_refuses_existing_
  run_dir``) stays green unchanged;
- env=both into a half-populated run dir is refused too (ANY colliding
  subject about to be run refuses the whole invocation — never a
  partial overwrite of the surviving subject);
- fresh-run regression: a first invocation on a clean runs dir never
  emits the pairing line.
"""
from __future__ import annotations

import os
from pathlib import Path

from labcli_helpers import run_cli
from tools.evidence_cli.jsonio import load as jsonio_load
from tools.evidence_cli.schema import validate_manifest

#: The campaign's own stamp (the 2026-09-26 11:41:34 UTC launch paired
#: with stamp 20260925T180736Z — the attempt this work order unblocks).
STAMP = "20260925T180736Z"
RUN_ID = f"{STAMP}-S001-recording"

#: A fixed old mtime for the shared-once pin: any rewrite by the second
#: assemble (``shutil.copyfile`` refreshes mtime) would move it.
_TOUCHED_EPOCH = 1600000000


def _run(tmp_path: Path, env: str):
    runs = tmp_path / "runs"
    gaps = tmp_path / "gaps"
    proc = run_cli([
        "run", "S001", "--driver", "recording", "--env", env,
        "--runs-dir", str(runs), "--gaps-dir", str(gaps),
        "--stamp", STAMP,
    ])
    return proc, runs, gaps


def test_pairing_second_env_opens_run_dir_and_reconciles(tmp_path):
    # --- invocation 1: env=reference, fresh run dir (no pairing) ---
    first, runs, _gaps = _run(tmp_path, "reference")
    assert first.returncode == 0, first.stdout + first.stderr
    run_dir = runs / RUN_ID
    assert (run_dir / "reference" / "manifest.json").is_file()
    assert not (run_dir / "implementation").exists()
    assert not (run_dir / "reconciliation").exists()
    # fresh-run regression: a clean runs dir never emits the pairing line
    assert "pairing into existing run dir" not in first.stdout
    # the honest partial line (compare skipped — needs both envs)
    assert f"run: {RUN_ID} partial (present: reference" in first.stdout
    assert "compare skipped — needs both envs" in first.stdout
    reference_manifest_before = (run_dir / "reference" /
                                 "manifest.json").read_bytes()
    scenario_root = run_dir / "scenario.yaml"
    assert scenario_root.is_file()
    scenario_bytes_before = scenario_root.read_bytes()
    os.utime(scenario_root, (_TOUCHED_EPOCH, _TOUCHED_EPOCH))

    # --- invocation 2: env=implementation, SAME stamp → pairing ---
    second, _runs, _gaps = _run(tmp_path, "implementation")
    assert second.returncode == 0, second.stdout + second.stderr
    out = second.stdout
    # the honest pairing line, exact shape (the campaign log records it)
    assert (f"run: pairing into existing run dir: {run_dir} "
            "(new subjects: implementation)") in out
    # only the NEW subject ran (the reference subject is not re-run)
    assert ("implementation: step 01 launch → would: "
            "launch(app=org.payswap.camscan)") in out
    assert "reference: step 01" not in out
    # both subjects present, each manifest schema-valid + own subject
    for subject in ("reference", "implementation"):
        manifest_path = run_dir / subject / "manifest.json"
        assert manifest_path.is_file()
        doc = jsonio_load(manifest_path)
        assert validate_manifest(doc) == []
        assert doc["subject"] == subject
        assert doc["run_id"] == RUN_ID
        assert doc["scenario"] == "application-launch"
    # the reference manifest survived the pairing byte-identical
    assert (run_dir / "reference" / "manifest.json").read_bytes() \
        == reference_manifest_before
    # exactly one scenario.yaml at the run root, untouched by the
    # second assemble (shared once — neither overwritten nor raised on)
    assert scenario_root.read_bytes() == scenario_bytes_before
    assert os.stat(scenario_root).st_mtime == _TOUCHED_EPOCH
    assert list(run_dir.glob("scenario*.yaml")) == [scenario_root]
    # the partial→complete transition fired on the SECOND invocation
    assert f"compare: {RUN_ID} verdict=PASS" in out
    assert f"run: {RUN_ID} complete (both envs, reconciled)" in out
    verdict = jsonio_load(run_dir / "reconciliation" / "verdict.json")
    assert verdict["verdict"] == "PASS"
    assert verdict["scenario"] == "application-launch"
    # staging cleaned up
    assert not (runs / ".staging").exists()


def test_pairing_collision_third_invocation_refused(tmp_path):
    first, runs, _gaps = _run(tmp_path, "reference")
    assert first.returncode == 0, first.stdout
    second, _runs, _gaps = _run(tmp_path, "implementation")
    assert second.returncode == 0, second.stdout
    run_dir = runs / RUN_ID
    verdict_before = (run_dir / "reconciliation" /
                      "verdict.json").read_bytes()

    # the campaign's retry shape: the SAME subject again → refused
    third, _runs, _gaps = _run(tmp_path, "reference")
    assert third.returncode == 1
    assert (f"run: run dir exists: {run_dir} (subjects present: "
            "reference — pass --stamp/--suffix for a fresh id — never "
            "silently overwritten)") in third.stdout
    assert "pairing into existing run dir" not in third.stdout
    # nothing ran: both subjects intact, reconciliation untouched, no
    # staging left behind
    assert (run_dir / "reference").is_dir()
    assert (run_dir / "implementation").is_dir()
    assert (run_dir / "reconciliation" /
            "verdict.json").read_bytes() == verdict_before
    assert not (runs / ".staging").exists()


def test_pairing_both_envs_into_half_populated_dir_refused(tmp_path):
    first, runs, _gaps = _run(tmp_path, "reference")
    assert first.returncode == 0, first.stdout
    run_dir = runs / RUN_ID
    reference_manifest_before = (run_dir / "reference" /
                                 "manifest.json").read_bytes()

    # env=both with reference already present: ANY colliding subject
    # about to be run refuses the whole invocation (the surviving
    # subject is never partially overwritten)
    both, _runs, _gaps = _run(tmp_path, "both")
    assert both.returncode == 1
    assert (f"run: run dir exists: {run_dir} (subjects present: "
            "reference — pass --stamp/--suffix for a fresh id — never "
            "silently overwritten)") in both.stdout
    assert "pairing into existing run dir" not in both.stdout
    assert not (run_dir / "implementation").exists()
    assert (run_dir / "reference" / "manifest.json").read_bytes() \
        == reference_manifest_before
    assert not (runs / ".staging").exists()
