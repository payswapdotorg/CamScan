#!/usr/bin/env python3
"""Lead-owned bootstrap validator — the CI acceptance gate for the control plane.

Checks (bootstrap scope; workers extend via their work orders, they do not
loosen gates):
  1. every lab/scenarios/*.yaml parses and matches the DSL contract
  2. scenario ids unique; S### file stems consistent with ledger ids
  3. meta.requires within the known capability set
  4. ledger.json parses; entry statuses within the vocabulary;
     ledger scenario ids == scenario file ids
  5. BLOCKED dependencies reference existing evidence files

Exit code 0 = gates green. No network, no secrets.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SCEN = ROOT / "lab" / "scenarios"
LEDGER = ROOT / "lab" / "parity-ledger" / "ledger.json"
STATUSES = {"UNKNOWN", "DISCOVERED", "SPECIFIED", "IMPLEMENTED", "PARTIAL", "BLOCKED", "PASS"}
CAPS = {"gui", "persistent", "android_emulator", "emulator_acceleration", "adb",
        "camera_fixture", "screenshots", "recording", "snapshot", "android_studio"}
REQUIRED_KEYS = {"id", "title", "status", "preconditions", "fixture", "steps", "assertions", "meta"}
ASSERT_KEYS = {"behavior", "ui", "state", "output"}

errors: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def check_scenarios() -> list[str]:
    ids: list[str] = []
    files = sorted(SCEN.glob("*.yaml"))
    if not files:
        err("no scenario files found")
        return ids
    for f in files:
        try:
            doc = yaml.safe_load(f.read_text())
        except Exception as e:  # noqa: BLE001
            err(f"{f.name}: YAML parse error: {e}")
            continue
        if not isinstance(doc, dict):
            err(f"{f.name}: not a mapping")
            continue
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
        reqs = doc["meta"].get("requires", [])
        bad_caps = set(reqs) - CAPS
        if bad_caps:
            err(f"{f.name}: unknown capabilities {sorted(bad_caps)}")
        stem = f.name.split("-", 1)[0]
        if not (len(stem) == 4 and stem.startswith("S") and stem[1:].isdigit()):
            err(f"{f.name}: file stem must be S###")
        ids.append(stem)
        if doc["id"] != f.stem.split("-", 1)[1]:
            err(f"{f.name}: id/stem mismatch ({doc['id']})")
    dupes = {i for i in ids if ids.count(i) > 1}
    if dupes:
        err(f"duplicate scenario numbers: {sorted(dupes)}")
    return ids


def check_ledger(scen_ids: list[str]) -> None:
    try:
        ledger = json.loads(LEDGER.read_text())
    except Exception as e:  # noqa: BLE001
        err(f"ledger.json: parse error: {e}")
        return
    if not isinstance(ledger.get("entries"), list) or not ledger["entries"]:
        err("ledger.json: no entries")
        return
    ledger_ids = [e.get("id") for e in ledger["entries"]]
    for e in ledger["entries"]:
        if e.get("status") not in STATUSES:
            err(f"ledger entry {e.get('id')}: bad status {e.get('status')!r}")
    if sorted(ledger_ids) != sorted(scen_ids):
        err(f"ledger/scenario id mismatch: ledger={sorted(ledger_ids)} files={sorted(scen_ids)}")
    for dep in ledger.get("blocked_dependencies", []):
        for ev in dep.get("evidence", []):
            if not (ROOT / ev).exists():
                err(f"blocked dep {dep.get('id')}: missing evidence file {ev}")


def main() -> int:
    ids = check_scenarios()
    check_ledger(ids)
    if errors:
        print("VALIDATION FAILED:")
        for m in errors:
            print("  -", m)
        return 1
    print(f"OK: {len(ids)} scenarios valid; ledger consistent; gates green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
