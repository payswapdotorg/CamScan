#!/usr/bin/env python3
"""lab-cli — the lab's operator CLI (CAMSCAN-007).

Turns the validated control plane into orchestrated runs and aggregate
reports:

    validate   the lead's validator, re-exposed verbatim (imported from
               ``tools/lab-cli/validate.py`` — never forked; its gates
               stay byte-equivalent, only ever extended)
    run        one scenario (or --all) end-to-end on a capable provider:
               scheduler resolution → driver provision/execute/teardown
               → evidence bundle → paired run dir → parity-cli
               compare + gap; NO-OP ``planned:`` lines when no capable
               provider / no live driver is on record for an env;
               ``--plan`` resolves and prints ONLY the plan
    report     aggregate: runs found (envs + verdict), gap counts by
               severity, ledger status snapshot; ``--json`` machine
               output (parity-cli's deterministic serialization)

Entry: ``python3 tools/lab-cli/main.py <command> …`` (the CI keeps
invoking ``python3 tools/lab-cli/validate.py`` directly — that path is
untouched and byte-identical).

Exit codes: 0 ok (a FAIL/BLOCKED verdict is a successful comparison,
and an honest planned NO-OP is a successful orchestration); 1
operational failure; 2 usage. ``report`` exits 0 unless the runs dir
is unreadable.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:  # script-mode bootstrap
    sys.path.insert(0, str(_REPO_ROOT))

from tools.lab_cli.scenarios import LabCliError  # noqa: E402


# ---------------------------------------------------------------- validate

#: The lab-cli composition surface — the sibling tool entry points the
#: ``run``/``report`` stages compose. Their absence is an orchestration
#: gate failure (the 006 lesson: gates are only ever EXTENDED — this
#: list adds checks on top of the lead's; it never touches theirs).
COMPOSITION_SURFACE: tuple[tuple[str, str], ...] = (
    ("tools/parity-cli/main.py",
     "parity-cli entry (run's reconciliation stage: compare + gap)"),
    ("tools/parity-cli/compare.py",
     "parity-cli compare engine (API import)"),
    ("tools/parity-cli/gaps.py",
     "parity-cli gap writer (API import)"),
    ("tools/evidence-cli/api.py",
     "evidence-cli facade (run's evidence stage: bundle)"),
    ("tools/evidence-cli/bundle.py",
     "evidence-cli bundle writer (API import)"),
    ("tools/adb-bridge/bridge.py",
     "adb-bridge verb layer (run's execution stage)"),
    ("tools/adb-bridge/targets.yaml",
     "semantic target registry (seed data)"),
    ("lab/providers/scheduler.py",
     "capability scheduler (run's provider resolution stage)"),
)


def _composition_problems(repo_root: Path) -> list[str]:
    problems = []
    for rel, why in COMPOSITION_SURFACE:
        if not (repo_root / rel).is_file():
            problems.append(f"composition surface: {rel} missing ({why})")
    return problems


def cmd_validate(args: argparse.Namespace) -> int:
    """Re-expose the lead's validator verbatim + the extension gates.

    The lead's ``main()`` is IMPORTED (``tools.lab_cli.validate``) and
    runs first, printing its own output — on the green plane and on any
    control-plane corruption the stdout and exit code are byte-identical
    to ``python3 tools/lab-cli/validate.py`` (pinned by tests). The
    extension (composition surface) runs only after the lead's gates
    pass and stays silent when green — never a loosening, only ever an
    extension.
    """
    from tools.lab_cli.validate import main as lead_validate_main

    rc = lead_validate_main()
    if rc != 0:
        return rc  # the lead's failure output is already complete
    problems = _composition_problems(Path(args.repo_root)
                                     if args.repo_root else _REPO_ROOT)
    if problems:
        print("VALIDATION FAILED:")
        for problem in problems:
            print("  -", problem)
        return 1
    return 0


# ------------------------------------------------------------------- run

def cmd_run(args: argparse.Namespace) -> int:
    from tools.lab_cli.run import cmd_run as _cmd_run

    return _cmd_run(args)


# ---------------------------------------------------------------- report

def cmd_report(args: argparse.Namespace) -> int:
    from tools.lab_cli.report import cmd_report as _cmd_report

    return _cmd_report(args)


# ------------------------------------------------------------------- CLI

def _add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--repo-root", metavar="PATH", default=None,
                        help="repo root (default: the repo containing "
                             "this tool; --runs-dir/--gaps-dir/--ledger "
                             "resolve against it)")


def _stamp_type(value: str) -> str:
    if not re.fullmatch(r"\d{8}T\d{6}Z", value or ""):
        raise argparse.ArgumentTypeError(
            f"{value!r} must match YYYYMMDDTHHMMSSZ (UTC)")
    return value


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="lab-cli",
        description="CamScan parity lab operator CLI (CAMSCAN-007; "
                    "contracts: lab/orchestration/LOOP.md, "
                    "lab/scenarios/SCENARIO-DSL.md, "
                    "lab/evidence/EVIDENCE.md, "
                    "lab/providers/LABPROVIDER.md)")
    sub = parser.add_subparsers(dest="command", required=True)

    p_validate = sub.add_parser(
        "validate", help="the lead's control-plane gates, re-exposed "
                         "verbatim (import, never forked) + the lab-cli "
                         "composition-surface extension")
    _add_common(p_validate)
    p_validate.set_defaults(func=cmd_validate)

    p_run = sub.add_parser(
        "run", help="orchestrate a scenario end-to-end (VERIFY stage)")
    p_run.add_argument("scenario", nargs="?", default=None,
                       metavar="SCENARIO",
                       help="scenario kebab id, S### stem, or file name "
                            "(or --all for the whole corpus)")
    p_run.add_argument("--all", action="store_true",
                       help="run every scenario in lab/scenarios/")
    p_run.add_argument("--env", choices=("reference", "implementation",
                                         "both"), default="both",
                       help="which subject env(s) to run (default: both)")
    p_run.add_argument("--plan", action="store_true",
                       help="resolution ONLY: scheduler matching + "
                            "planned: lines (no provisioning, no "
                            "execution, no runs/ writes)")
    p_run.add_argument("--driver", choices=("live", "recording"),
                       default="live",
                       help="driver kind (default: live; 'recording' "
                            "logs would-be calls and writes "
                            "deterministic synthetic evidence — no "
                            "substrate, no credentials)")
    p_run.add_argument("--runs-dir", metavar="PATH", default=None,
                       help="runs/ directory (default: "
                            "<repo-root>/runs)")
    p_run.add_argument("--gaps-dir", metavar="PATH", default=None,
                       help="gap output directory (default: "
                            "<repo-root>/lab/reconciliation)")
    p_run.add_argument("--stamp", type=_stamp_type, metavar="STAMP",
                       default=None,
                       help="run-id timestamp YYYYMMDDTHHMMSSZ "
                            "(default: now; fixed stamps make recording "
                            "runs byte-reproducible)")
    p_run.add_argument("--suffix", metavar="SLUG", default=None,
                       help="run-id suffix (default: the driver slug)")
    p_run.add_argument("--apk", metavar="PATH", default=None,
                       help="local APK for live implementation runs "
                            "(pushed + installed through the provider "
                            "contract)")
    _add_common(p_run)
    p_run.set_defaults(func=cmd_run)

    p_report = sub.add_parser(
        "report", help="aggregate: runs, gap counts, ledger snapshot")
    p_report.add_argument("--runs-dir", metavar="PATH", default=None,
                          help="runs/ directory (default: "
                               "<repo-root>/runs)")
    p_report.add_argument("--scenario", metavar="ID", default=None,
                          help="filter by kebab scenario id or S### stem")
    p_report.add_argument("--json", action="store_true",
                          help="machine output (sorted keys, 2-space "
                               "indent, no wall-clock timestamps)")
    p_report.add_argument("--gaps-dir", metavar="PATH", default=None,
                          help="reconciliation gap directory (default: "
                               "<repo-root>/lab/reconciliation)")
    p_report.add_argument("--ledger", metavar="PATH", default=None,
                          help="ledger.json path (default: "
                               "<repo-root>/lab/parity-ledger/"
                               "ledger.json)")
    _add_common(p_report)
    p_report.set_defaults(func=cmd_report)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "run" and not args.scenario and not args.all:
        parser.error("run needs a scenario id (kebab/S###/file name) or --all")
    try:
        return int(args.func(args))
    except LabCliError as e:
        print(f"lab-cli: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
