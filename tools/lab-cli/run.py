"""``run`` — the orchestration stage (CAMSCAN-007).

One scenario end-to-end through the loop's VERIFY stage (LOOP.md):

    scenario yaml (typed requires)
        → capability matching (lab.providers.scheduler — never identity)
        → eligible provider record
        → driver provision/execute/teardown (pluggable; teardown pinned)
        → single-subject staging → evidence-cli bundle → paired layout
        → BOTH envs present? parity-cli compare + gap (API import)

Composition choice (documented per the work order): **API import, not
subprocess** — ``tools.parity_cli.compare.compare_run`` /
``tools.parity_cli.gaps.write_gaps`` are called in-process, mirroring
how parity-cli itself composes evidence-cli (imported vocabulary, never
forked). Same-process calls give structured results and deterministic
bytes without process-spawn overhead; the CLI still prints the exact
operator commands for manual reproduction.

Honesty rules:

- no capable provider on record for an env → explicit ``planned:`` line
  NO-OP (the scheduler's unsatisfied reasons are printed verbatim);
- no live driver on record for an env → explicit ``planned:`` line
  NO-OP (documented substrate path named);
- ``--plan`` does ONLY the resolution + planned lines — it never
  provisions, never executes, never touches runs/;
- a FAIL/BLOCKED/PARTIAL verdict from compare is a SUCCESSFUL
  orchestration (exit 0) — parity-cli's own exit-code doctrine.
"""
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from tools.evidence_cli.jsonio import load as jsonio_load
from tools.lab_cli import evidence as ev
from tools.lab_cli.drivers import (
    APP_PACKAGES,
    ENVS,
    DriverHandle,
    ExecutionRequest,
    ProvisionRequest,
    SubjectRunResult,
    resolve_driver,
)
from tools.lab_cli.scenarios import LabCliError, Scenario, list_scenarios
from tools.lab_cli.steps import plan_steps, render_plan_line

from lab.providers.scheduler import select_provider


@dataclass
class RunOptions:
    """Everything ``run`` needs besides the scenario itself."""

    repo_root: Path
    runs_dir: Path
    gaps_dir: Path
    envs: tuple[str, ...] = ENVS            # reference, implementation
    plan: bool = False                      # resolution only
    driver_kind: str = "live"               # live | recording
    stamp: str = ""                         # YYYYMMDDTHHMMSSZ (default: now)
    suffix: str = ""                        # default: the driver slug
    apk: Path | None = None
    registry: Any = None                    # adb-bridge TargetRegistry
    emit: Callable[[str], None] = print
    clock: Callable[[], str] = field(
        default_factory=lambda: lambda: datetime.now(UTC)
        .strftime(ev.UTC_FORMAT))


# ------------------------------------------------------------ provider pool

def load_provider_reports(repo_root: Path) -> list[dict[str, Any]]:
    """Every capability report on record (sorted, fail-closed on parse).

    Structural schema validation of reports is the CI validator's gate
    (``tools/lab-cli/validate.py`` — re-exposed by this CLI); here an
    unreadable report aborts the run rather than scheduling on garbage.
    """
    providers_dir = Path(repo_root) / "lab" / "providers"
    paths = sorted(providers_dir.glob("*/capability-report.json"))
    if not paths:
        raise LabCliError(
            f"no provider capability reports on record (expected "
            f"{providers_dir}/<slug>/capability-report.json)")
    reports: list[dict[str, Any]] = []
    for path in paths:
        try:
            doc = jsonio_load(path)
        except (OSError, ValueError) as e:
            raise LabCliError(
                f"provider report unreadable: {path} ({e})") from e
        if not isinstance(doc, dict) or not doc.get("slug"):
            raise LabCliError(f"provider report malformed: {path}")
        reports.append(doc)
    # Pool-order policy (CAMSCAN-009): slug-ascending. The scheduler
    # picks the first ELIGIBLE record in pool order, so the order IS
    # the selection policy: a base provider record ("e2b") must precede
    # its environment-class refinements ("e2b-reference") or adding an
    # env-class record could silently flip the paired run's substrate
    # record. Glob order is NOT trusted: it is implementation-sensitive
    # (string sort puts "e2b-reference/" before "e2b/" because '-' <
    # '/', while Path-object sort happens to keep "e2b" first on 3.12)
    # — the explicit slug sort makes the policy deterministic either
    # way.
    reports.sort(key=lambda report: str(report.get("slug", "")))
    return reports


def _no_provider_reason(reasons: dict[str, list[str]]) -> str:
    parts = [f"{slug}: {'; '.join(unsatisfied)}"
             for slug, unsatisfied in sorted(reasons.items())]
    return "no capable provider on record: " + "; ".join(parts)


def _requires_line(scenario: Scenario) -> str:
    requires = scenario.requires
    pieces = []
    for cap in sorted(requires):
        value = requires[cap]
        if isinstance(value, dict):
            inner = ",".join(str(v) for v in value.get("allowed", []))
            pieces.append(f"{cap}.allowed=[{inner}]")
        else:
            pieces.append(f"{cap}={value}")
    return " ".join(pieces)


# ---------------------------------------------------------- subject run

def orchestrate_subject(driver: Any, scenario: Scenario, subject: str,
                        run_id: str, chosen: dict[str, Any],
                        step_plans: list[Any], fixtures: list[dict[str, str]],
                        stage: Path, opts: RunOptions) -> SubjectRunResult:
    """provision → execute → teardown, with the teardown invariant.

    ``teardown`` runs on EVERY path out of execute (success, step
    failure, exception) — a driver never gets to leak a paid
    environment by raising. Pinned by tests with a failing stub driver.
    """
    emit = opts.emit
    prov_request = ProvisionRequest(
        run_id=run_id, subject=subject, scenario=scenario,
        provider_report=chosen,
        step_timeout_s=scenario.step_timeout_seconds,
        timeout_s=scenario.timeout_seconds,
        apk=opts.apk, emit=emit)
    handle: DriverHandle = driver.provision(prov_request)
    started_at, finished_at = ev.execution_window(
        run_id.split("-", 1)[0], len(step_plans))
    exec_request = ExecutionRequest(
        handle=handle, run_id=run_id, subject=subject, scenario=scenario,
        step_plans=step_plans, stage_dir=stage, fixtures=fixtures,
        started_at=started_at, finished_at=finished_at,
        clock=opts.clock, emit=emit)
    error = ""
    try:
        result = driver.execute(handle, exec_request)
    except Exception as exc:  # noqa: BLE001 — the runner owns the invariant
        error = f"{type(exc).__name__}: {exc}"
        result = SubjectRunResult.failed(subject, error)
    finally:
        driver.teardown(handle, error=error, emit=emit)
    return result


# ------------------------------------------------------------- one scenario

def run_scenario(scenario: Scenario, opts: RunOptions) -> int:
    """Orchestrate one scenario; returns the CLI exit code (0/1)."""
    emit = opts.emit
    problems = scenario.problems()
    if problems:
        emit(f"run: {scenario.stem} {scenario.id} — invalid meta.requires:")
        for problem in problems:
            emit(f"  - {problem}")
        return 1
    reports = load_provider_reports(opts.repo_root)
    chosen, reasons = select_provider(scenario.requires, reports)

    stamp = opts.stamp or datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    suffix = opts.suffix or opts.driver_kind
    run_id = ev.new_run_id(scenario.stem, suffix, stamp)
    run_dir = Path(opts.runs_dir) / run_id

    step_plans = plan_steps(scenario.steps, opts.registry)
    fixtures: list[dict[str, str]] = []

    header = (f"plan: {scenario.stem} {scenario.id} status={scenario.status}"
              if opts.plan else
              f"run: {scenario.stem} {scenario.id} env={'+'.join(opts.envs)} "
              f"runs-dir={opts.runs_dir}")
    emit(header)
    emit(f"  requires: {_requires_line(scenario)}")
    emit("  providers on record: "
         + ",".join(sorted(r.get("slug", "?") for r in reports)))

    if not opts.plan and run_dir.exists():
        emit(f"run: run dir exists: {run_dir} (pass --stamp/--suffix for a "
             "fresh id — never silently overwritten)")
        return 1

    ran: list[str] = []
    failures = 0
    for subject in opts.envs:
        if chosen is None:
            emit(f"planned: {scenario.stem} {scenario.id} env={subject} "
                 f"provider=none NO-OP ({_no_provider_reason(reasons)})")
            continue
        provider_slug = str(chosen.get("slug", "?"))
        driver, driver_reason = resolve_driver(
            subject, provider_slug, opts.driver_kind)
        if driver is None:
            emit(f"planned: {scenario.stem} {scenario.id} env={subject} "
                 f"provider={provider_slug} NO-OP ({driver_reason})")
            continue
        if opts.plan:
            emit(f"planned: {scenario.stem} {scenario.id} env={subject} "
                 f"provider={provider_slug} driver={driver.slug} "
                 f"app={APP_PACKAGES[subject]} steps={len(step_plans)} "
                 f"timeout_seconds={scenario.timeout_seconds} "
                 f"step_timeout_seconds={scenario.step_timeout_seconds} "
                 f"run=runs/<stamp>-{scenario.stem}-{suffix}")
            continue

        emit(f"  {subject}: provider={provider_slug} driver={driver.slug} "
             f"app={APP_PACKAGES[subject]}")
        if not fixtures:
            fixtures = ev.fixture_entries(scenario, opts.repo_root)
        stage = ev.stage_dir(opts.runs_dir, run_id, subject)
        if stage.parent.exists():
            ev.cleanup_staging(opts.runs_dir, run_id)
        stage.mkdir(parents=True)
        result = orchestrate_subject(
            driver, scenario, subject, run_id, chosen, step_plans,
            fixtures, stage, opts)
        if not result.ok:
            failures += 1
            ev.cleanup_staging(opts.runs_dir, run_id)
            emit(f"  {subject}: FAILED — {result.reason}")
            for problem in result.problems:
                emit(f"    - {problem}")
            continue
        try:
            ev.scenario_copy_for_stage(stage, scenario.file)
            n_artifacts = ev.bundle_stage(stage, opts.repo_root)
            ev.assemble_subject(stage, opts.runs_dir, run_id, subject,
                                scenario.file, emit)
        finally:
            ev.cleanup_staging(opts.runs_dir, run_id)
        ran.append(subject)
        emit(f"  {subject}: bundled {n_artifacts} artifact(s) → "
             f"runs/{run_id}/{subject}")

    if opts.plan:
        for plan in step_plans:
            emit("  " + render_plan_line(plan, opts.registry))
        emit(f"  budget: timeout_seconds={scenario.timeout_seconds} "
             f"step_timeout_seconds={scenario.step_timeout_seconds}")
        return 0

    if failures:
        return 1

    present = [s for s in ENVS if (run_dir / s).is_dir()]
    if len(present) == len(ENVS):
        reconcile_run(run_id, Path(opts.runs_dir), Path(opts.gaps_dir),
                      Path(opts.repo_root), emit)
        emit(f"run: {run_id} complete (both envs, reconciled)")
    else:
        missing = [s for s in ENVS if s not in present]
        emit(f"run: {run_id} partial (present: {'+'.join(present) or 'none'}; "
             f"compare skipped — needs both envs; planned NO-OP: "
             f"{'+'.join(missing)})")
    return 0


# -------------------------------------------------------------- reconcile

def reconcile_run(run_id: str, runs_dir: Path, gaps_dir: Path,
                  repo_root: Path, emit: Callable[[str], None]) -> None:
    """parity-cli compare + gap via API import (in-process composition).

    A FAIL/BLOCKED/PARTIAL verdict is a successful orchestration; only
    a :class:`tools.parity_cli.model.ParityCliError` propagates (the
    command layer turns it into exit 1).
    """
    from tools.parity_cli.compare import compare_run
    from tools.parity_cli.gaps import write_gaps

    result = compare_run(run_id, repo_root=repo_root, runs_dir=runs_dir)
    verdict = result.verdict
    emit(f"compare: {run_id} verdict={verdict['verdict']} "
         f"({verdict['summary']})")
    counts = verdict.get("counts") or {}
    emit("  counts: " + "  ".join(
        f"{sev}={counts.get(sev, 0)}"
        for sev in ("critical", "high", "medium", "low")))
    run_dir = Path(runs_dir) / run_id
    outcome = write_gaps(result.diff, run_dir, gaps_dir)
    emit(f"gap: considered={outcome.considered} "
         f"written={len(outcome.written)} skipped={len(outcome.skipped)} "
         f"→ {gaps_dir}")
    for path in outcome.written:
        emit(f"  wrote {path}")
    for skipped in outcome.skipped:
        emit(f"  kept {skipped['path']} (status "
             f"{skipped['status']!r} — human annotation preserved)")
    emit("reproduce: python3 tools/parity-cli/main.py compare "
         f"{run_id} --runs-dir {runs_dir}")


# ------------------------------------------------------------ command layer

def cmd_run(args: Any) -> int:
    """CLI entry for ``run`` (see main.py for the argparse surface)."""
    repo_root = Path(args.repo_root) if args.repo_root \
        else Path(__file__).resolve().parents[2]
    runs_dir = Path(args.runs_dir) if args.runs_dir else repo_root / "runs"
    gaps_dir = Path(args.gaps_dir) if args.gaps_dir \
        else repo_root / "lab" / "reconciliation"
    envs = tuple(ENVS) if args.env == "both" else (args.env,)

    registry = None
    try:
        from tools.adb_bridge.bridge import TargetRegistry
        registry = TargetRegistry(
            repo_root / "tools" / "adb-bridge" / "targets.yaml")
    except (OSError, ValueError) as e:
        raise LabCliError(f"target registry failed to load: {e}") from e

    opts = RunOptions(
        repo_root=repo_root, runs_dir=runs_dir, gaps_dir=gaps_dir,
        envs=envs, plan=args.plan, driver_kind=args.driver,
        stamp=args.stamp, suffix=args.suffix,
        apk=Path(args.apk) if args.apk else None, registry=registry)

    if args.all:
        scenarios = list_scenarios(repo_root / "lab" / "scenarios")
    elif args.scenario:
        from tools.lab_cli.scenarios import resolve_scenario
        scenarios = [resolve_scenario(args.scenario,
                                      repo_root / "lab" / "scenarios")]
    else:  # argparse enforces (scenario | --all); unreachable
        raise LabCliError("run needs a scenario id or --all")

    failures = 0
    for scenario in scenarios:
        failures += 1 if run_scenario(scenario, opts) else 0
    if len(scenarios) > 1:
        print(f"run: {len(scenarios)} scenario(s) orchestrated, "
              f"{failures} operational failure(s)")
    return 1 if failures else 0
