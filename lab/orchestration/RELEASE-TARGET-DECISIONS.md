# Release-target decisions — handoff §11 disposition (lead, 2026-10-04)

The handoff's explicitly-unimplemented list, each determined for the release
target vs a clearly documented next release. Truthful tracking only — nothing
is claimed complete because an adjacent engine exists.

| item | disposition | basis |
|---|---|---|
| PPTX conversion | **RELEASE TARGET** (ENGINE-complete) | CAMSCAN-PPTX-017 recovered + integrated (158cb19/5bd5577, 1175/1175 §10g parity); the conversion UI entry wires with VERIFY-002 |
| ID-card capture UI | **RELEASE TARGET** | PROD-012 ScanMode catalog + ModeSelector/GuidanceGeometry delivered; VERIFY-002 wires the mode chip fully (guidance overlay + capture + ModeNote) |
| business-card capture UI | **RELEASE TARGET** | same engine basis; VERIFY-002 wires US/EU variants |
| book capture UI | **RELEASE TARGET, honest-pending seam** | SpreadSplitPlanner engine delivered; the midline-split pipeline into the review flow is the declared open seam — the chip ships visibly-pending (no fake functionality) unless VERIFY-002 genuinely completes the wiring within its territory |
| long-image raster application | **RELEASE TARGET** | LongImagePlanner + export tooling delivered (PROD-015); VERIFY-002 wires the export entry end-to-end |
| auto-capture | **NEXT RELEASE** | no engine: requires a detection-stability policy (StableDetection windowing) + camera-fixture proof first; faking a timer is not auto-capture |
| batch scan | **NEXT RELEASE** | multi-page SESSIONS exist (PROD-004) but batch (multi-document) mode is a separate flow; not implemented, not claimed |
| batch-selection UI | **NEXT RELEASE** | depends on batch scan existing |

Out-of-scope (§12, unchanged): optional cloud account, sync, cross-device
metadata, collaboration, fax, translation — the clean-room/offline-first
mandate holds.
