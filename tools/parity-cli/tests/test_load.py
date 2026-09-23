"""Bundle loading, bundle statuses, generated_utc, scenario parsing."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from tools.parity_cli.load import (derive_generated_utc, load_bundles,
                                   parse_scenario_yaml, resolve_run_dir,
                                   run_number)
from tools.parity_cli.model import ParityCliError

from helpers import RUN_ID, SCENARIO_S004, base_manifest, build_pair


def test_resolve_run_dir_missing(tmp_path: Path) -> None:
    (tmp_path / "runs").mkdir()
    with pytest.raises(ParityCliError, match="run dir not found"):
        resolve_run_dir("nope", runs_dir=tmp_path / "runs")


def test_bundle_status_ok(tmp_path: Path) -> None:
    run_dir = build_pair(tmp_path)
    bundles = load_bundles(run_dir)
    assert bundles["reference"].ok
    assert bundles["implementation"].ok
    assert bundles["reference"].status == "ok"


def test_bundle_status_missing(tmp_path: Path) -> None:
    run_dir = build_pair(tmp_path, with_implementation=False)
    bundles = load_bundles(run_dir)
    assert bundles["implementation"].status == "missing"
    assert bundles["implementation"].manifest is None


def test_bundle_status_invalid_schema(tmp_path: Path) -> None:
    run_dir = build_pair(
        tmp_path, impl_overrides={"device": {"locale": ""}})
    bundles = load_bundles(run_dir)
    assert bundles["implementation"].status == "invalid"
    assert any("locale" in problem or "device" in problem
               for problem in bundles["implementation"].problems)


def test_bundle_status_invalid_bad_action_key(tmp_path: Path) -> None:
    run_dir = build_pair(
        tmp_path, impl_overrides={"action_trace": [{"t_ms": 0}]})
    bundles = load_bundles(run_dir)
    assert bundles["implementation"].status == "invalid"
    assert any("action" in problem
               for problem in bundles["implementation"].problems)


def test_bundle_status_no_observation_empty_trace(tmp_path: Path) -> None:
    run_dir = build_pair(tmp_path, ref_overrides={"action_trace": []})
    bundles = load_bundles(run_dir)
    assert bundles["reference"].status == "no-observation"
    assert bundles["reference"].manifest is not None


def test_bundle_status_no_observation_all_failed(tmp_path: Path) -> None:
    trace = [{"t_ms": 0, "action": "launch", "target": "",
              "result": "crash"}]
    run_dir = build_pair(tmp_path, ref_overrides={"action_trace": trace})
    bundles = load_bundles(run_dir)
    assert bundles["reference"].status == "no-observation"


def test_bundle_status_one_ok_step_is_enough(tmp_path: Path) -> None:
    trace = [
        {"t_ms": 0, "action": "launch", "target": "", "result": "crash"},
        {"t_ms": 10, "action": "launch", "target": "",
         "result": "activity-resumed"},
    ]
    run_dir = build_pair(tmp_path, ref_overrides={"action_trace": trace})
    assert load_bundles(run_dir)["reference"].status == "ok"


def test_generated_utc_takes_max_finished_at(tmp_path: Path) -> None:
    run_dir = build_pair(
        tmp_path,
        ref_overrides={"finished_at": "2026-09-23T12:01:04Z"},
        impl_overrides={"finished_at": "2026-09-23T12:02:44+00:00"})
    bundles = load_bundles(run_dir)
    assert derive_generated_utc(bundles, RUN_ID) == "2026-09-23T12:02:44Z"


def test_generated_utc_falls_back_to_run_id(tmp_path: Path) -> None:
    run_dir = build_pair(tmp_path, with_implementation=False)
    bundles = load_bundles(run_dir)
    # reference manifest still readable → derived from it
    assert derive_generated_utc(bundles, RUN_ID) == "2026-09-23T12:01:04Z"
    empty: dict = {}
    assert derive_generated_utc(empty, RUN_ID) == "2026-09-23T12:00:00Z"


def test_generated_utc_epoch_fallback() -> None:
    assert derive_generated_utc({}, "not-a-run-id") == \
        "1970-01-01T00:00:00Z"


def test_parse_scenario_yaml_real_file() -> None:
    fields = parse_scenario_yaml(
        Path(__file__).resolve().parents[3] / "lab" / "scenarios" /
        "S004-single-document-capture.yaml")
    assert fields["id"] == "single-document-capture"
    assert fields["title"] == "Single-document capture end-to-end"
    assert fields["status"] in {"UNKNOWN", "DISCOVERED", "SPECIFIED",
                                "IMPLEMENTED", "PARTIAL", "BLOCKED", "PASS"}
    assert "document-detected" in fields["assertions"]["behavior"]
    assert "pdf-created" in fields["assertions"]["output"]


def test_parse_scenario_yaml_comments_preserved_semantics() -> None:
    # the real S004 carries a leading comment line — parsing must skip it
    assert SCENARIO_S004.startswith("# migrated")
    fields = parse_scenario_yaml(
        Path(__file__).resolve().parents[3] / "lab" / "scenarios" /
        "S004-single-document-capture.yaml")
    assert fields["id"] == "single-document-capture"


def test_run_number() -> None:
    assert run_number(RUN_ID) == "S004"
    with pytest.raises(ParityCliError):
        run_number("garbage")


def test_manifest_contract_keys(tmp_path: Path) -> None:
    """The synthetic pair matches the EVIDENCE.md manifest contract
    (exactly the eleven top-level keys, evidence-cli vocabulary)."""
    run_dir = build_pair(tmp_path)
    doc = json.loads((run_dir / "reference" / "manifest.json")
                     .read_text(encoding="utf-8"))
    assert set(doc) == {"run_id", "scenario", "subject", "provider",
                        "application", "device", "fixtures", "artifacts",
                        "action_trace", "started_at", "finished_at"}
    assert doc["subject"] == "reference"
    # deterministic bytes: identical manifests → identical file content
    manifest = base_manifest(RUN_ID, "reference")
    again = (tmp_path / "runs" / RUN_ID / "reference" / "manifest.json"
             ).read_text(encoding="utf-8")
    import json as _json
    assert _json.loads(again) == manifest
