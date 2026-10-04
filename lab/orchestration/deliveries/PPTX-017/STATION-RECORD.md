# PPTX-017 station reassembly record (lead)

- Provenance: worker chat 39849825 (lost era, last activity 2026-10-03T12:46Z); pod released
  for slot capacity before harvest (§11d) — narrative survived server-side (§9c/§10b).
- Harvest: extract_writes.py → 10 file ops (6 writes, 2 edits, 2 multiedits) + 60 bash calls.
- Replay: §10b protocol (Write→Edit→MultiEdit atomicity) on fresh branch from b9b2534 —
  ZERO divergence; diff stat EXACTLY matches the worker report (4 files, +952).
- Station gates (memory-capped staged, --no-daemon -Xmx1536m in-process --max-workers=2):
  compileDebugKotlin GREEN; testDebugUnitTest 1175/1175 failures=0 errors=0
  (§10g parity: EXACT match with the certified report — 1139 existing + 36 new);
  lintDebug GREEN; assembleDebug APK 50,998,813 B (byte size EXACT match with the report;
  sha 2d16462a... differs from the worker's 65df9867... — APK signing timestamp, expected);
  compileDebugAndroidTestKotlin GREEN.
- Branch work/CAMSCAN-PPTX-017 @ 158cb19 PUSHED; merged onto work/CAMSCAN-PROD-014 @ 5bd5577
  (merge gate 1175/1175 green).
- Scenario S025-export-office-pptx.yaml staged at repo root by the worker (mirror-drop
  convention); the lab/scenarios/ mirror + reconciliation regen lands with the VERIFY-002
  integration close (the PROD-015 close pattern).
