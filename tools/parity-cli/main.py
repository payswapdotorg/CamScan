#!/usr/bin/env python3
"""parity-cli — manifest-driven parity comparison engine (CAMSCAN-005).

Turns two evidence bundles (exactly what ``tools/evidence-cli``
produces, per ``lab/evidence/EVIDENCE.md``) into a structured diff, a
verdict, gap reports and ledger updates:

    compare <run-id>       runs/<run-id>/reference/manifest.json +
                           runs/<run-id>/implementation/manifest.json
                           → reconciliation/{diff.json, verdict.json}
    gap <run-id>           open divergences (severity ≥ medium) →
                           lab/reconciliation/<stem>.<feature>.gap.yaml
                           (GAP-FORMAT.md); --from-diff reuses the
                           existing diff.json instead of re-comparing
    ledger-update <run-id>  verdict → lab/parity-ledger/ledger.json
                           (+ scenario status mirror), schema-valid

Determinism: outputs are byte-identical for identical evidence —
sorted entries, derived timestamps (never the tool's wall clock).
Exit codes: 0 ok (a FAIL/BLOCKED verdict is a successful comparison,
not a tool error), 1 operational failure, 2 usage.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:  # script-mode bootstrap
    sys.path.insert(0, str(_REPO_ROOT))

from tools.parity_cli.compare import compare_run
from tools.parity_cli.gaps import write_gaps
from tools.parity_cli.ledger import update_ledger
from tools.parity_cli.load import resolve_run_dir
from tools.parity_cli.model import DIMENSIONS, SEVERITIES, ParityCliError


def _add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--repo-root", metavar="PATH", default=None,
                        help="repo root (default: the repo containing "
                             "this tool; --runs-dir/--gaps-dir/--ledger "
                             "resolve against it)")


def _repo_root(args: argparse.Namespace) -> Path:
    return Path(args.repo_root) if args.repo_root else _REPO_ROOT


def _print_counts(counts: dict[str, int]) -> None:
    print("  counts: " + "  ".join(
        f"{severity}={counts.get(severity, 0)}" for severity in SEVERITIES))


def cmd_compare(args: argparse.Namespace) -> int:
    result = compare_run(
        args.run_id, repo_root=_repo_root(args),
        runs_dir=Path(args.runs_dir) if args.runs_dir else None,
        masks_dir=Path(args.masks_dir) if args.masks_dir else None,
        use_masks=not args.no_masks, check=args.check)
    verdict = result.verdict
    print(f"run {result.run_id} [{result.diff.get('scenario')}]")
    print(f"verdict: {verdict['verdict']}")
    print(f"summary: {verdict['summary']}")
    _print_counts(verdict["counts"])
    masks = result.diff.get("masks") or {}
    if masks.get("enabled") and (
            masks.get("removed_reference_steps")
            or masks.get("removed_implementation_steps")):
        print(f"masks: {masks.get('rules_considered', 0)} rule(s) "
              f"considered, removed reference steps "
              f"{masks.get('removed_reference_steps')} / implementation "
              f"steps {masks.get('removed_implementation_steps')}")
    for dimension in DIMENSIONS:
        info = (result.diff.get("dimensions") or {}).get(dimension) or {}
        if info.get("entries"):
            print(f"  {dimension:<13} {info['entries']} entr"
                  f"{'y' if info['entries'] == 1 else 'ies'} "
                  f"(critical={info.get('critical', 0)} "
                  f"high={info.get('high', 0)} "
                  f"medium={info.get('medium', 0)} "
                  f"low={info.get('low', 0)})")
    if args.check:
        if result.check_problems:
            print("COMPARE CHECK FAILED (reconciliation outputs stale):",
                  file=sys.stderr)
            for problem in result.check_problems:
                print(f"  - {problem}", file=sys.stderr)
            return 1
        print(f"compare check ok: {result.run_id} — reconciliation/"
              "diff.json + verdict.json up to date")
        return 0
    if result.wrote_diff:
        print(f"wrote runs/{result.run_id}/reconciliation/diff.json")
        print(f"wrote runs/{result.run_id}/reconciliation/verdict.json")
    return 0


def cmd_gap(args: argparse.Namespace) -> int:
    repo_root = _repo_root(args)
    run_dir = resolve_run_dir(args.run_id, repo_root=repo_root,
                              runs_dir=Path(args.runs_dir)
                              if args.runs_dir else None)
    if args.from_diff:
        from tools.evidence_cli.jsonio import load as jsonio_load
        diff_path = run_dir / "reconciliation" / "diff.json"
        if not diff_path.is_file():
            raise ParityCliError(
                f"runs/{args.run_id}/reconciliation/diff.json missing — "
                "run compare first (--from-diff needs it)")
        try:
            diff = jsonio_load(diff_path)
        except (OSError, ValueError) as e:
            raise ParityCliError(f"diff.json unreadable: {e}") from e
    else:
        result = compare_run(
            args.run_id, repo_root=repo_root,
            runs_dir=Path(args.runs_dir) if args.runs_dir else None,
            masks_dir=Path(args.masks_dir) if args.masks_dir else None,
            use_masks=not args.no_masks)
        diff = result.diff
        print(f"compare ok: {result.verdict['verdict']} "
              f"({result.verdict['summary']})")
    gaps_dir = Path(args.gaps_dir) if args.gaps_dir else \
        repo_root / "lab" / "reconciliation"
    outcome = write_gaps(diff, run_dir, gaps_dir)
    print(f"gaps considered: {outcome.considered} "
          f"(severity critical/high/medium only)")
    for path in outcome.written:
        print(f"wrote {path}")
    for skipped in outcome.skipped:
        print(f"skipped {skipped['path']} "
              f"(status {skipped['status']!r} — human annotation kept)")
    if not outcome.written and not outcome.skipped:
        print("no open divergences at gap severity — nothing to file")
    return 0


def cmd_ledger_update(args: argparse.Namespace) -> int:
    repo_root = _repo_root(args)
    run_dir = resolve_run_dir(args.run_id, repo_root=repo_root,
                              runs_dir=Path(args.runs_dir)
                              if args.runs_dir else None)
    result = update_ledger(run_dir, repo_root=repo_root,
                           ledger_path=Path(args.ledger)
                           if args.ledger else None,
                           dry_run=args.dry_run)
    print(f"ledger update: {result.entry_id} [{result.scenario}] "
          f"status → {result.status} "
          f"(verdict {result.verdict}, run {result.run_id})")
    print(f"  last_run: provider={result.last_run['provider']} "
          f"accel={result.last_run['emulator_acceleration']} "
          f"utc={result.last_run['utc']}")
    for ev in result.evidence_added:
        print(f"  evidence+: {ev['kind']} {ev['ref']}")
    if not result.evidence_added:
        print("  evidence+: (none new — already recorded)")
    print(f"  notes: {result.notes_line}")
    if args.dry_run:
        print("dry-run: nothing written")
    else:
        print(f"wrote {result.ledger_path}")
        print(f"wrote status mirror {result.scenario_path}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="parity-cli",
        description="CamScan parity comparison engine (CAMSCAN-005; "
                    "contracts: lab/evidence/EVIDENCE.md, "
                    "lab/reconciliation/GAP-FORMAT.md, "
                    "lab/parity-ledger/ledger.schema.json)")
    sub = parser.add_subparsers(dest="command", required=True)

    p_compare = sub.add_parser(
        "compare", help="compare the run's two subject bundles; write "
                        "reconciliation/{diff.json,verdict.json}")
    p_compare.add_argument("run_id", help="runs/<run-id> directory name")
    p_compare.add_argument("--runs-dir", metavar="PATH", default=None,
                           help="runs/ directory (default: "
                                "<repo-root>/runs)")
    p_compare.add_argument("--masks-dir", metavar="PATH", default=None,
                           help="mask directory (default: this tool's "
                                "masks/)")
    p_compare.add_argument("--no-masks", action="store_true",
                           help="compare without the ignore-list masks")
    p_compare.add_argument("--check", action="store_true",
                           help="gate only: fail if the on-disk "
                                "reconciliation outputs are stale; "
                                "write nothing")
    _add_common(p_compare)
    p_compare.set_defaults(func=cmd_compare)

    p_gap = sub.add_parser(
        "gap", help="emit gap yamls for open divergences "
                    "(GAP-FORMAT.md)")
    p_gap.add_argument("run_id", help="runs/<run-id> directory name")
    p_gap.add_argument("--from-diff", action="store_true",
                       help="reuse the existing reconciliation/diff.json "
                            "instead of re-comparing")
    p_gap.add_argument("--runs-dir", metavar="PATH", default=None)
    p_gap.add_argument("--masks-dir", metavar="PATH", default=None)
    p_gap.add_argument("--no-masks", action="store_true")
    p_gap.add_argument("--gaps-dir", metavar="PATH", default=None,
                       help="gap output directory (default: "
                            "<repo-root>/lab/reconciliation)")
    _add_common(p_gap)
    p_gap.set_defaults(func=cmd_gap)

    p_ledger = sub.add_parser(
        "ledger-update", help="record the run's verdict in the parity "
                              "ledger (+ scenario status mirror)")
    p_ledger.add_argument("run_id", help="runs/<run-id> directory name")
    p_ledger.add_argument("--runs-dir", metavar="PATH", default=None)
    p_ledger.add_argument("--ledger", metavar="PATH", default=None,
                          help="ledger.json path (default: "
                               "<repo-root>/lab/parity-ledger/ledger.json)")
    p_ledger.add_argument("--dry-run", action="store_true",
                          help="compute the mutation, write nothing")
    _add_common(p_ledger)
    p_ledger.set_defaults(func=cmd_ledger_update)

    args = parser.parse_args(argv)
    try:
        return int(args.func(args))
    except ParityCliError as e:
        print(f"parity-cli: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
