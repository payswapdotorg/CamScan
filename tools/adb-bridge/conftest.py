"""Pytest bootstrap: make the repo root importable regardless of where
pytest was invoked from, so ``tools.adb_bridge.bridge`` and
``lab.providers.types`` both resolve (the bridge's contract types come from
the lab package)."""
from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
