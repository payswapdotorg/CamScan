"""Driver contract + driver registry (CAMSCAN-007).

A **driver** owns stages (b)+(c) of the work order for ONE subject of
ONE run: provisioning/starting an environment via the LabProvider
contract (``lab/providers/LABPROVIDER.md``) and executing the scenario
steps through adb-bridge verbs while capturing evidence per
``lab/evidence/EVIDENCE.md`` into the runner-provided staging dir.

The runner (``tools/lab_cli/run.py``) owns everything else: scenario
resolution through the capability scheduler, run-dir naming, the
single-subject bundle/assemble shuffle (``tools/lab_cli/evidence.py``),
and the reconciliation stage (parity-cli compare + gap). The runner
ALSO owns the teardown invariant: ``teardown`` is invoked on every path
out of ``execute`` (success, step failure, exception, timeout) — a
driver never gets to leak a paid environment by raising.

Three drivers ship:

- :class:`tools.lab_cli.recording.RecordingDriver` — logs the would-be
  calls and writes deterministic synthetic evidence; the pytest-path
  driver (live-provider network calls are out of pytest scope).
- :class:`tools.lab_cli.e2b_live.E2bLiveDriver` — the live e2b path
  for the implementation env (lazy ``lab.providers.e2b`` import,
  ``E2B_API_KEY`` preflight); exercised by the lab later, never by
  pytest.
- :class:`tools.lab_cli.reference_live.ReferenceDriver` — the live
  reference env (official CamScanner) per the probe-17 recipe
  (CAMSCAN-009): presigned-URL/local XAPK acquisition, the
  dexopt+install-multiple retry ladder, ANR dismissal; hermetic tests
  drive it through an injected scripted transport, never the SDK.

Driver availability is **env-aware** (``_LIVE_DRIVER_FACTORIES``): the
live e2b driver is on record for the *implementation* env
(CAMSCAN-007), and the live reference driver — the probe-17 recipe of
``lab/substrate/REFERENCE-INSTALL-2026-09-22.md`` — landed with
CAMSCAN-009 (:class:`tools.lab_cli.reference_live.ReferenceDriver`,
on record for the e2b base record and the reference env-class record
alike). A live run whose env has no driver on record — or whose
scenario has no capable provider on record (S004's camera_fixture
today) — is still an honest NO-OP ``planned:`` line, never a
fabricated observation.
"""
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from tools.lab_cli.scenarios import Scenario

#: The two subjects of a paired run (EVIDENCE.md).
ENVS: tuple[str, ...] = ("reference", "implementation")

#: App package per env (provenance: targets.yaml app scopes /
#: tools/reference-observe/observe.py APK_PACKAGE_DEFAULT).
APP_PACKAGES: dict[str, str] = {
    "reference": "com.intsig.camscanner",
    "implementation": "org.payswap.camscan",
}

#: Documented-but-not-implemented live driver paths (NO-OP reasons).
#: CAMSCAN-009: the reference-env path LANDED (ReferenceDriver, on
#: record for e2b + e2b-reference) — the table is empty until a new
#: documented-but-unimplemented substrate path exists. The honest
#: NO-OP doctrine is unchanged: a live run with no driver on record
#: for its env, or no capable provider on record for its scenario
#: (S004's camera_fixture today), still NO-OPs with a ``planned:``
#: line — never a fabricated observation.
DOCUMENTED_DRIVERS: dict[str, str] = {}


# ------------------------------------------------------------- requests

@dataclass
class ProvisionRequest:
    """Everything a driver needs to provision ONE subject environment."""

    run_id: str
    subject: str                      # reference | implementation
    scenario: Scenario
    provider_report: dict[str, Any]   # the scheduler's chosen record
    step_timeout_s: int               # meta.step_timeout_seconds
    timeout_s: int                    # meta.timeout_seconds
    apk: Path | None = None        # local APK (implementation live)
    emit: Callable[[str], None] = print


@dataclass
class ExecutionRequest:
    """Everything a driver needs to execute + capture ONE subject."""

    handle: DriverHandle
    run_id: str
    subject: str
    scenario: Scenario
    step_plans: list[Any]             # steps.StepPlan (typed loosely: no cycle)
    stage_dir: Path                   # single-subject staging run dir
    fixtures: list[dict[str, str]]    # [{id, sha256}] pinned corpus entries
    started_at: str                   # deterministic window (run-id-derived)
    finished_at: str                  # deterministic window (run-id-derived)
    clock: Callable[[], str] = field(
        default_factory=lambda: lambda: "")  # real-time ISO (live drivers)
    emit: Callable[[str], None] = print


@dataclass
class DriverHandle:
    """Provisioned-environment facts + driver-private state.

    The runner reads only the public facts (they land in the manifest's
    ``provider``/``application``/``device`` blocks); ``native`` carries
    whatever the driver needs between provision/execute/teardown (e.g.
    the LabProvider instance + EnvironmentId for the live driver).
    """

    subject: str
    provider_slug: str
    environment_id: str
    capabilities: dict[str, Any] = field(default_factory=dict)
    application: dict[str, Any] = field(default_factory=dict)
    device: dict[str, Any] = field(default_factory=dict)
    native: Any = None


@dataclass
class SubjectRunResult:
    """Outcome of one subject's execution (never raises past the runner)."""

    subject: str
    ok: bool
    planned_noop: bool = False
    reason: str = ""
    steps_executed: int = 0
    artifacts: int = 0
    problems: list[str] = field(default_factory=list)

    @classmethod
    def noop(cls, subject: str, reason: str) -> SubjectRunResult:
        return cls(subject=subject, ok=True, planned_noop=True, reason=reason)

    @classmethod
    def failed(cls, subject: str, reason: str) -> SubjectRunResult:
        return cls(subject=subject, ok=False, reason=reason)


# ---------------------------------------------------------------- contract

class RunDriver(Protocol):
    """The driver contract — what a live provider driver must implement.

    Lifecycle (enforced by the runner, not by trust):

    1. ``provision(request) -> DriverHandle`` — create + boot + reset the
       environment via the LabProvider contract; record provider/app/
       device facts on the handle. Must NOT be reached unless a capable
       provider record was resolved by the scheduler.
    2. ``execute(handle, request) -> SubjectRunResult`` — drive the
       planned steps through adb-bridge verbs (per-step budget =
       ``meta.step_timeout_seconds``; scenario wall-clock =
       ``meta.timeout_seconds`` from env-ready to evidence-complete),
       writing artifacts under ``<stage>/<subject>/`` and the
       single-subject metadata (``run-metadata.json``: the EVIDENCE.md
       manifest minus ``artifacts``) at the stage root. Device-side
       failures are recorded (ok=False + reason), not raised.
    3. ``teardown(handle, error="")`` — stop + destroy on EVERY path;
       never raises; paid environments are never leaked.

    Determinism: everything the driver writes must be reproducible from
    (scenario, run_id, subject) — no wall-clock stamps (the runner hands
    the deterministic started_at/finished_at derived from the run-id
    timestamp), no host paths inside content-addressed sections.
    """

    slug: str

    def provision(self, request: ProvisionRequest) -> DriverHandle: ...

    def execute(self, handle: DriverHandle,
                request: ExecutionRequest) -> SubjectRunResult: ...

    def teardown(self, handle: DriverHandle, error: str = "",
                 emit: Callable[[str], None] = print) -> None: ...


# ---------------------------------------------------------------- registry

def live_driver_reason(env: str, provider_slug: str) -> str | None:
    """Why no live driver is on record for (env, provider) — or None."""
    if (env, provider_slug) in _LIVE_DRIVER_FACTORIES:
        return None
    documented = DOCUMENTED_DRIVERS.get((env, provider_slug))
    if documented:
        return (f"no live driver on record for env={env} on provider "
                f"{provider_slug} ({documented})")
    return (f"no live driver on record for env={env} on provider "
            f"{provider_slug}")


#: Live driver factories, registered lazily (the e2b import stays inside
#: the factory: importing it must never require the e2b SDK).
_LIVE_DRIVER_FACTORIES: dict[tuple[str, str], Callable[[], Any]] = {}


def register_live_driver(env: str, provider_slug: str,
                         factory: Callable[[], Any]) -> None:
    """Register a live driver factory for (env, provider_slug)."""
    _LIVE_DRIVER_FACTORIES[(env, provider_slug)] = factory


def _register_builtin_live() -> None:
    def _e2b_implementation() -> Any:
        from tools.lab_cli.e2b_live import E2bLiveDriver
        return E2bLiveDriver()

    def _e2b_reference() -> Any:
        from tools.lab_cli.reference_live import ReferenceDriver
        return ReferenceDriver()

    # On record (CAMSCAN-007): the implementation env on e2b.
    register_live_driver("implementation", "e2b", _e2b_implementation)
    # On record (CAMSCAN-009): the reference env on the e2b substrate —
    # registered for BOTH the base provider record (e2b — the record
    # the scheduler's slug-ascending pool policy selects for paired
    # runs) and the reference env-class record (e2b-reference) so
    # reference runs resolve under either record without identity
    # branching. The implementation env stays keyed to the base record
    # only — an honest NO-OP names the gap if that is ever reached.
    register_live_driver("reference", "e2b", _e2b_reference)
    register_live_driver("reference", "e2b-reference", _e2b_reference)


_register_builtin_live()


def resolve_driver(env: str, provider_slug: str, kind: str) \
        -> tuple[RunDriver | None, str]:
    """Resolve a driver for (env, provider) by kind.

    Returns (driver, reason): driver is None when none is on record
    (reason explains — the honest NO-OP). ``kind``: ``live`` (default
    operator path) or ``recording`` (deterministic would-be-call logger;
    on record for every env — it fabricates no substrate claims, it
    executes none either).
    """
    if kind == "recording":
        from tools.lab_cli.recording import RecordingDriver
        return RecordingDriver(), ""
    if kind == "live":
        factory = _LIVE_DRIVER_FACTORIES.get((env, provider_slug))
        if factory is not None:
            return factory(), ""
        return None, live_driver_reason(env, provider_slug) or (
            f"no live driver on record for env={env} on provider "
            f"{provider_slug}")
    raise ValueError(f"unknown driver kind {kind!r}")
