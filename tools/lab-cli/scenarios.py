"""Scenario corpus access for ``tools/lab-cli`` (CAMSCAN-007).

Loads and resolves the lead-owned scenario DSL files
(``lab/scenarios/S###-<id>.yaml``, contract: ``lab/scenarios/
SCENARIO-DSL.md``). Parsing uses PyYAML — the same dependency the lead's
validator (``tools/lab-cli/validate.py``) already pins for the control
plane; everything downstream of the parsed mapping is stdlib-only.

Resolution accepts any of the three operator spellings:

- the kebab scenario id          (``application-launch``)
- the ``S###`` stem              (``S001``)
- the file name                  (``S001-application-launch.yaml``)

The corpus loader is deliberately dumb about semantics: DSL conformance
and ``meta.requires`` typing are the CI validator's gates (re-exposed by
this CLI's ``validate`` subcommand), not this module's job. Only the
shape the orchestrator needs (id/stem/steps/meta/fixture) is extracted,
and unknown shapes fail loud (operational error), never silently.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from lab.providers.scheduler import validate_requirements

#: Vocabulary of statuses the ledger/scenario mirror allows (SCENARIO-DSL).
STATUSES = ("UNKNOWN", "DISCOVERED", "SPECIFIED", "IMPLEMENTED",
            "PARTIAL", "BLOCKED", "PASS")


class LabCliError(Exception):
    """Operational failure (unknown scenario, unreadable corpus, broken
    provider record, driver failure). Data-level problems are recorded
    in outputs; this exception aborts the current command."""


@dataclass
class Scenario:
    """One parsed scenario DSL file (the fields the orchestrator needs)."""

    file: Path
    stem: str                    # "S001"
    id: str                      # "application-launch" (kebab)
    title: str = ""
    status: str = ""
    preconditions: list[str] = field(default_factory=list)
    fixture: dict[str, Any] = field(default_factory=dict)
    steps: list[Any] = field(default_factory=list)
    assertions: dict[str, list[str]] = field(default_factory=dict)
    meta: dict[str, Any] = field(default_factory=dict)

    @property
    def timeout_seconds(self) -> int:
        return int(self.meta.get("timeout_seconds") or 0)

    @property
    def step_timeout_seconds(self) -> int:
        return int(self.meta.get("step_timeout_seconds") or 0)

    @property
    def requires(self) -> dict[str, Any]:
        requires = self.meta.get("requires")
        return requires if isinstance(requires, dict) else {}

    def doc(self) -> dict[str, Any]:
        """The full parsed YAML mapping (verbatim semantics)."""
        return _load_yaml(self.file)

    def problems(self) -> list[str]:
        """Orchestration-blocking DSL problems (typed requires only —
        full DSL conformance stays the CI validator's gate set)."""
        return validate_requirements(self.meta.get("requires"))


def _load_yaml(path: Path) -> dict[str, Any]:
    try:
        doc = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as e:
        raise LabCliError(f"scenario file unreadable: {path} ({e})") from e
    if not isinstance(doc, dict):
        raise LabCliError(f"scenario file is not a mapping: {path}")
    return doc


def _stem_of(path: Path) -> str:
    stem = path.name.split("-", 1)[0]
    if not (len(stem) == 4 and stem.startswith("S") and stem[1:].isdigit()):
        raise LabCliError(
            f"scenario file stem must be S###: {path.name}")
    return stem


def load_scenario(path: Path) -> Scenario:
    """Parse one scenario file into the orchestrator's shape."""
    path = Path(path)
    doc = _load_yaml(path)
    stem = _stem_of(path)
    scenario_id = doc.get("id")
    if not isinstance(scenario_id, str) or not scenario_id:
        raise LabCliError(f"scenario file has no id: {path}")
    fixture = doc.get("fixture")
    if not isinstance(fixture, dict):
        fixture = {}
    assertions = doc.get("assertions")
    if not isinstance(assertions, dict):
        assertions = {}
    meta = doc.get("meta")
    if not isinstance(meta, dict):
        meta = {}
    return Scenario(
        file=path, stem=stem, id=scenario_id,
        title=str(doc.get("title") or ""),
        status=str(doc.get("status") or ""),
        preconditions=[str(p) for p in (doc.get("preconditions") or [])],
        fixture=fixture,
        steps=list(doc.get("steps") or []),
        assertions=assertions,
        meta=meta,
    )


def list_scenarios(scen_dir: Path) -> list[Scenario]:
    """Every scenario in the corpus, sorted by stem (deterministic)."""
    scen_dir = Path(scen_dir)
    files = sorted(scen_dir.glob("*.yaml"))
    if not files:
        raise LabCliError(f"no scenario files found under {scen_dir}")
    return [load_scenario(f) for f in files]


def resolve_scenario(query: str, scen_dir: Path) -> Scenario:
    """Resolve one scenario by kebab id, ``S###`` stem, or file name."""
    scen_dir = Path(scen_dir)
    query = query.strip()
    if query.endswith(".yaml"):
        query = query[: -len(".yaml")]
    for scenario in list_scenarios(scen_dir):
        if query in (scenario.id, scenario.stem, scenario.file.stem):
            return scenario
    raise LabCliError(
        f"unknown scenario {query!r} (looked under {scen_dir}; expected a "
        "kebab id like 'application-launch', an S### stem, or a file name)")
