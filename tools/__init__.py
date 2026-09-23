"""``tools/`` — lab tooling packages.

Work-order deliverable directories use dashes (``tools/adb-bridge``,
``tools/evidence-cli``, …), which Python identifiers cannot contain. This
package init registers each dashed directory as a *synthetic* package under
its importable underscore alias, so both binding conventions hold verbatim:

    from tools.adb_bridge.bridge import AdbBridge     # underscore import
    from tools.evidence_cli.api import bundle_run     # same mechanism, W3 tool
    python3 -m pytest tools/adb-bridge -q             # dashed path

The aliased packages are synthetic on purpose: a physical ``__init__.py``
inside a dashed directory would make pytest import it as a dash-named
``Package`` node (not a valid module name), so none lives there.

Registration is generalized (CAMSCAN-006): every dashed sub-directory that
contains at least one ``*.py`` file gets the same alias treatment, and each
alias resolves only its declared exports (lazily importing the primary
module); anything else raises ``AttributeError``. ``adb-bridge``'s spec is
byte-for-byte its original registration — the mechanism is parameterized,
not changed. Dashed directories without Python files are not registered
(``parity-cli`` was the README-only placeholder example at CAMSCAN-006
branch time; CAMSCAN-005 landed its implementation, so it now registers
through the same generalized path).
"""
from __future__ import annotations

import importlib
import sys
import types
from pathlib import Path

_TOOLS = Path(__file__).resolve().parent

#: dashed directory -> (underscore alias, primary module, exported names).
#: The primary module is imported lazily on first export resolution.
_ALIAS_SPECS: dict[str, tuple[str, str, tuple[str, ...]]] = {
    "adb-bridge": (
        "tools.adb_bridge",
        "bridge",
        ("AdbBridge", "VerbResult", "ResolvedTarget", "Predicate",
         "TargetRegistry", "TargetEntry", "UnknownTargetError",
         "LabProviderLike", "EnvironmentId", "DEFAULT_VERB_TIMEOUT_S",
         "DEFAULT_POLL_S"),
    ),
    "evidence-cli": (
        "tools.evidence_cli",
        "api",
        ("bundle_run", "upload_run", "verify_manifest",
         "R2Store", "EvidenceCliError"),
    ),
    "lab-cli": ("tools.lab_cli", "validate", ("main",)),
    "reference-observe": ("tools.reference_observe", "observe", ()),
}


def _iter_dashed_dirs() -> list[Path]:
    """Dashed tool directories that carry at least one Python file."""
    out: list[Path] = []
    for child in sorted(_TOOLS.iterdir()):
        if not child.is_dir() or "-" not in child.name:
            continue
        if any(child.glob("*.py")):
            out.append(child)
    return out


def _register_alias(dashed: str, alias: str, primary: str,
                    exports: tuple[str, ...]) -> None:
    """Make ``tools/<dashed>`` importable as ``tools.<underscore>``."""
    pkg = _TOOLS / dashed
    if not (pkg / f"{primary}.py").is_file() or alias in sys.modules:
        return
    module = types.ModuleType(alias)
    module.__path__ = [str(pkg)]          # a package rooted at the dashed dir
    module.__package__ = alias

    def __getattr__(name: str, _alias: str = alias, _primary: str = primary,
                    _exports: tuple[str, ...] = exports):
        if name in _exports:
            primary_mod = importlib.import_module(f"{_alias}.{_primary}")
            return getattr(primary_mod, name)
        raise AttributeError(f"module {_alias!r} has no attribute {name!r}")

    module.__getattr__ = __getattr__      # type: ignore[method-assign]
    sys.modules[alias] = module


def _register_aliases() -> None:
    for dashed_dir in _iter_dashed_dirs():
        alias, primary, exports = _ALIAS_SPECS.get(
            dashed_dir.name,
            (f"tools.{dashed_dir.name.replace('-', '_')}", _first_py(dashed_dir), ()),
        )
        _register_alias(dashed_dir.name, alias, primary, exports)


def _first_py(dashed_dir: Path) -> str:
    """A primary module for aliases without an explicit spec."""
    return min(dashed_dir.glob("*.py")).stem


_register_aliases()
