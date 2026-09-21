"""E2B LabProvider package (CAMSCAN-008).

Public surface:
    from lab.providers.e2b import E2BProvider, E2BProviderConfig

The acceptance gate (CAMSCAN-008 §22 lifecycle) runs:
    python3 lab/providers/e2b/acceptance.py
"""
from .provider import E2BProvider, E2BProviderConfig, STATIC_CAPABILITIES

__all__ = ["E2BProvider", "E2BProviderConfig", "STATIC_CAPABILITIES"]
