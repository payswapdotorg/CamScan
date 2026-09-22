"""``tools/`` — lab tooling packages.

Work-order deliverable directories use dashes (``tools/adb-bridge``), which
Python identifiers cannot contain. This package init registers the dashed
directory as a *synthetic* package under its importable underscore alias, so
both binding conventions hold verbatim:

    from tools.adb_bridge.bridge import AdbBridge     # underscore import
    python3 -m pytest tools/adb-bridge -q             # dashed path

The aliased package is synthetic on purpose: a physical ``__init__.py``
inside the dashed directory would make pytest import it as a dash-named
``Package`` node (not a valid module name), so none lives there.
"""
from __future__ import annotations

import importlib
import sys
import types
from pathlib import Path

_ALIAS = "tools.adb_bridge"
_PKG = Path(__file__).resolve().parent / "adb-bridge"

#: public names re-exported lazily from the aliased package
_EXPORTS = ("AdbBridge", "VerbResult", "ResolvedTarget", "Predicate",
            "TargetRegistry", "TargetEntry", "UnknownTargetError",
            "LabProviderLike", "EnvironmentId", "DEFAULT_VERB_TIMEOUT_S",
            "DEFAULT_POLL_S")


def _register_alias() -> None:
    """Make ``tools/adb-bridge`` importable as ``tools.adb_bridge``."""
    if not (_PKG / "bridge.py").is_file() or _ALIAS in sys.modules:
        return
    module = types.ModuleType(_ALIAS)
    module.__path__ = [str(_PKG)]          # a package rooted at the dashed dir
    module.__package__ = _ALIAS

    def __getattr__(name: str):            # lazy re-exports, no eager import
        if name in _EXPORTS:
            bridge = importlib.import_module(f"{_ALIAS}.bridge")
            return getattr(bridge, name)
        raise AttributeError(f"module {_ALIAS!r} has no attribute {name!r}")

    module.__getattr__ = __getattr__       # type: ignore[method-assign]
    sys.modules[_ALIAS] = module


_register_alias()
