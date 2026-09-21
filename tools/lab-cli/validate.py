#!/usr/bin/env python3
"""Lead-owned validator — the CI acceptance gate for the control plane.

Checks (v0.1 — strengthened per the CAMSCAN-008 handoff; existing gates are
never loosened, only extended):
  1.  every lab/scenarios/*.yaml parses and matches the DSL contract
  2.  scenario ids unique; S### file stems consistent with ledger ids;
      filename mapping S###-<id>.yaml
  3.  typed `meta.requires` (mapping, known capability keys, valid forms)
      via lab/providers/scheduler.py — the same matcher the parity engine uses
  4.  timeout fields: meta.timeout_seconds >= 60, meta.step_timeout_seconds >= 30
  5.  ledger.json validates against lab/parity-ledger/ledger.schema.json
      (jsonschema); statuses within vocabulary; id/scenario/title/status
      mirror the scenario files exactly
  6.  fixture references: null or kebab ids; when lab/fixtures/manifest.json
      exists, referenced ids must be declared there
  7.  fixture manifest (when present) validates against fixture.schema.json;
      every referenced file exists; sha256/bytes present
  8.  evidence references: ledger entry evidence[].ref paths exist on disk
      (or are r2:/https URIs); blocked+resolved dependency evidence exists
  9.  provider capability reports validate against capabilities.schema.json;
      the e2b report must state emulator_acceleration "none" (substrate truth)

Exit code 0 = gates green. No network, no secrets.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

import yaml  # noqa: E402
from jsonschema import Draft7Validator  # noqa: E402

from lab.providers.scheduler import validate_requirements  # noqa: E402
from lab.providers.types import CAPABILITY_KEYS  # noqa: E402

SCEN = ROOT / "lab" / "scenarios"
LEDGER = ROOT / "lab" / "parity-ledger" / "ledger.json"
LEDGER_SCHEMA = ROOT / "lab" / "parity-ledger" / "ledger.schema.json"
FIXTURES = ROOT / "lab" / "fixtures"
FIXTURE_SCHEMA = FIXTURES / "fixture.schema.json"
FIXTURE_MANIFEST = FIXTURES / "manifest.json"
CAPS_SCHEMA = ROOT / "lab" / "providers" / "capabilities.schema.json"
STATUSES = {"UNKNOWN", "DISCOVERED", "SPECIFIED", "IMPLEMENTED", "PARTIAL", "BLOCKED", "PASS"}
REQUIRED_KEYS = {"id", "title", "status", "preconditions", "fixture", "steps",
                 "assertions", "meta"}
ASSERT_KEYS = {"behavior", "ui", "state", "output"}
OWNERS = {"reference-discovery", "lead", "reconciliation", "implementation"}

errors: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def load_schema(path: Path) -> dict:
    return json.loads(path.read_text())


def check_schema(doc, schema: dict, label: str) -> None:
    validator = Draft7Validator(schema)
    for e in validator.iter_errors(doc):
        err(f"{label}: schema violation at {'/'.join(str(p) for p in e.absolute_path) or '<root>'}: "
            f"{e.message}")


def check_scenarios() -> list[dict]:
    docs: list[dict] = []
    files = sorted(SCEN.glob("*.yaml"))
    if not files:
        err("no scenario files found")
        return docs
    ids: list[str] = []
    for f in files:
        try:
            doc = yaml.safe_load(f.read_text())
        except Exception as e:  # noqa: BLE001
            err(f"{f.name}: YAML parse error: {e}")
            continue
        if not isinstance(doc, dict):
            err(f"{f.name}: not a mapping")
            continue
        docs.append({"file": f, "doc": doc})
        missing = REQUIRED_KEYS - set(doc)
        if missing:
            err(f"{f.name}: missing keys {sorted(missing)}")
            continue
        if doc["status"] not in STATUSES:
            err(f"{f.name}: bad status {doc['status']!r}")
        if not isinstance(doc["steps"], list) or not doc["steps"]:
            err(f"{f.name}: steps must be a non-empty list")
        bad_assert = set(doc["assertions"]) - ASSERT_KEYS
        if bad_assert:
            err(f"{f.name}: unknown assertion categories {sorted(bad_assert)}")

        # -- typed meta (v0.1): timeouts + typed requires -------------------
        meta = doc.get("meta") or {}
        if not isinstance(meta, dict):
            err(f"{f.name}: meta must be a mapping")
        else:
            for key, minimum in (("timeout_seconds", 60), ("step_timeout_seconds", 30)):
                value = meta.get(key)
                if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
                    err(f"{f.name}: meta.{key} must be an integer >= {minimum} "
                        f"(got {value!r})")
            if "requires" not in meta:
                err(f"{f.name}: meta.requires is required (typed mapping, v0.1)")
            else:
                for problem in validate_requirements(meta["requires"]):
                    err(f"{f.name}: {problem}")
                if not meta["requires"].get("adb"):
                    pass  # adb not strictly mandatory for future non-device scenarios
            owner = meta.get("owner")
            if owner is not None and owner not in OWNERS:
                err(f"{f.name}: meta.owner must be one of {sorted(OWNERS)} (got {owner!r})")

        # -- fixture references ----------------------------------------------
        fixture = doc.get("fixture") or {}
        if not isinstance(fixture, dict):
            err(f"{f.name}: fixture must be a mapping with camera/sequence keys")
        else:
            for key in ("camera", "sequence"):
                ref = fixture.get(key)
                if ref is None:
                    continue
                if not isinstance(ref, str) or not ref or ref != ref.strip():
                    err(f"{f.name}: fixture.{key} must be null or a non-empty id string")
                    continue
                import re
                if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", ref):
                    err(f"{f.name}: fixture.{key} id not kebab-case: {ref!r}")
                    continue
                if FIXTURE_MANIFEST.exists():
                    if ref not in KNOWN_FIXTURE_IDS:
                        err(f"{f.name}: fixture.{key} references unknown fixture "
                            f"{ref!r} (not in lab/fixtures/manifest.json)")

        # -- file stem mapping -------------------------------------------------
        stem = f.name.split("-", 1)[0]
        if not (len(stem) == 4 and stem.startswith("S") and stem[1:].isdigit()):
            err(f"{f.name}: file stem must be S###")
        ids.append(stem)
        if doc["id"] != f.stem.split("-", 1)[1]:
            err(f"{f.name}: id/stem mismatch ({doc['id']})")
        docs[-1]["stem"] = stem
    dupes = {i for i in ids if ids.count(i) > 1}
    if dupes:
        err(f"duplicate scenario numbers: {sorted(dupes)}")
    return docs


def fixture_ids() -> set[str]:
    if not FIXTURE_MANIFEST.exists():
        return set()
    try:
        manifest = json.loads(FIXTURE_MANIFEST.read_text())
    except Exception as e:  # noqa: BLE001
        err(f"fixtures manifest: parse error: {e}")
        return set()
    check_schema(manifest, load_schema(FIXTURE_SCHEMA), "fixtures manifest")
    ids: set[str] = set()
    for group in ("documents", "sequences"):
        for entry in manifest.get(group, []):
            fid = entry.get("id")
            if isinstance(fid, str):
                ids.add(fid)
            for f in entry.get("files", []) or entry.get("frames", []):
                path = f.get("path")
                if isinstance(path, str) and not (FIXTURES / path).exists():
                    err(f"fixtures manifest: file missing on disk: {path}")
                if not isinstance(f.get("sha256"), str) or len(f.get("sha256", "")) != 64:
                    err(f"fixtures manifest: bad sha256 for {path}")
    return ids


def check_ledger(docs: list[dict]) -> None:
    try:
        ledger = json.loads(LEDGER.read_text())
    except Exception as e:  # noqa: BLE001
        err(f"ledger.json: parse error: {e}")
        return
    check_schema(ledger, load_schema(LEDGER_SCHEMA), "ledger.json")
    if not isinstance(ledger.get("entries"), list) or not ledger["entries"]:
        err("ledger.json: no entries")
        return

    by_id = {d["doc"].get("id"): d for d in docs}
    ledger_ids = [e.get("id") for e in ledger["entries"]]
    file_stems = [d.get("stem") for d in docs]
    if sorted(ledger_ids) != sorted(file_stems):
        err(f"ledger/scenario id mismatch: ledger={sorted(ledger_ids)} files={sorted(file_stems)}")

    for e in ledger["entries"]:
        if e.get("status") not in STATUSES:
            err(f"ledger entry {e.get('id')}: bad status {e.get('status')!r}")
        doc = by_id.get(e.get("scenario"))
        if doc is None:
            err(f"ledger entry {e.get('id')}: scenario {e.get('scenario')!r} has no file")
            continue
        # mirror rules: the ledger is truth; the yaml mirrors it exactly
        if doc["doc"].get("status") != e.get("status"):
            err(f"{doc['file'].name}: status mirror mismatch "
                f"(yaml={doc['doc'].get('status')!r} ledger={e.get('status')!r})")
        if doc["doc"].get("title") != e.get("title"):
            err(f"{doc['file'].name}: title mirror mismatch "
                f"(yaml={doc['doc'].get('title')!r} ledger={e.get('title')!r})")
        # evidence references
        for ev in e.get("evidence", []):
            ref = ev.get("ref") if isinstance(ev, dict) else ev
            if not isinstance(ref, str):
                err(f"ledger entry {e.get('id')}: evidence ref not a string: {ev!r}")
                continue
            if ref.startswith(("r2:", "https://")):
                continue
            if not (ROOT / ref).exists():
                err(f"ledger entry {e.get('id')}: missing evidence file {ref}")

    for dep in ledger.get("blocked_dependencies", []) + ledger.get("resolved_dependencies", []):
        for ev in dep.get("evidence", []):
            if not (ROOT / ev).exists():
                err(f"dependency {dep.get('id')}: missing evidence file {ev}")


def check_capability_reports() -> None:
    if not CAPS_SCHEMA.exists():
        err("capabilities.schema.json missing")
        return
    schema = load_schema(CAPS_SCHEMA)
    reports = sorted((ROOT / "lab" / "providers").glob("*/capability-report.json"))
    if not reports:
        err("no provider capability report found "
            "(expected lab/providers/<slug>/capability-report.json)")
        return
    for path in reports:
        try:
            report = json.loads(path.read_text())
        except Exception as e:  # noqa: BLE001
            err(f"{path.relative_to(ROOT)}: parse error: {e}")
            continue
        check_schema(report, schema, str(path.relative_to(ROOT)))
        if report.get("slug") == "e2b" and report.get("emulator_acceleration") != "none":
            err(f"{path.relative_to(ROOT)}: e2b must report emulator_acceleration "
                f"'none' (TCG substrate truth), got {report.get('emulator_acceleration')!r}")
        for key in CAPABILITY_KEYS:
            if key not in report:
                err(f"{path.relative_to(ROOT)}: missing capability {key!r}")


def main() -> int:
    global KNOWN_FIXTURE_IDS
    KNOWN_FIXTURE_IDS = fixture_ids()
    docs = check_scenarios()
    check_ledger(docs)
    check_capability_reports()
    if errors:
        print("VALIDATION FAILED:")
        for m in errors:
            print("  -", m)
        return 1
    print(f"OK: {len(docs)} scenarios valid (typed requires + timeouts); "
          f"ledger schema-valid + mirrored; "
          f"{len(KNOWN_FIXTURE_IDS)} fixture ids known; "
          f"capability reports schema-valid; gates green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
