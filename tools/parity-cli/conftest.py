"""Pytest bootstrap: make the repo root importable regardless of where
pytest was invoked from, so ``tools.parity_cli.*`` (the alias package
registered by ``tools/__init__.py``) resolves — same convention as
evidence-cli/adb-bridge."""
from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
