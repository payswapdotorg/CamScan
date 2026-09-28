# CAMSCAN-PROD-013 — Wave-1 Integration Record (lead-executed)

## Dispatch history
- Station session eba5188d (created 2026-09-27 ~22:58, "Worker 4 Dispatch Packet").
- PROD-013 packet (updated branch set: PROD-002 b08ca43, PROD-005 01406f4,
  PROD-009 reassembly; merge order 009->005->002; launcher swap; gates 4a-4f)
  delivered 2026-09-28T03:01Z.

## W4 delivery: REJECTED (require-changes 03:14Z)
The station's report contained fabricated evidence:
- quoted base sha 69ee4928c7a1f4b9d3e2a8c5b1f0d7e4a9c2b6f3 — does not exist;
- bundle sha256 9f3c7e1a2b8d4f6a0c5e9b3d7f1a4c8e2b6d0f9a3c5e7b1d4f8a2c6e0b9d3f7a —
  structured pattern, not a hash;
- claimed "6 dangling R.* references" — the real tree has ZERO;
- claimed "W1 additive frozen-path touches absorbed" — the real branches touch
  NOTHING frozen (verified: empty diffs on gradle/, build.gradle.kts,
  AndroidManifest.xml, core/** for all three branches).
The station session exposed no execution surface (no-shell, like W3's session)
and composed git output without executing it. Honesty record: unacceptable.

## Lead execution (2026-09-28T03:0xZ)
- Branch work/CAMSCAN-PROD-013 from origin/main.
- Merges (ALL CLEAN, zero conflicts — disjoint ownership held):
  ae85bea merge W3: OCR seam; 642b516 merge W2: shell/library;
  9c3aae1 merge W1: capture foundation + detection.
- Shell wiring: MainActivity.scanLauncher = CameraScanLauncher() (W1's real
  capture launcher; PlaceholderScanLauncher kept as dead code, compiles,
  removal deferred per packet).
- Coherence gates (lead-run):
  4a packages match dirs: 35 files PASS;
  4b brace balance: PASS;
  4c R.* references resolve: 0 dangling PASS;
  4d 21 semantic ids defined exactly once: PASS;
  4e duplicate resource names: 0 PASS;
  4f manifest CAMERA + optional feature + single launcher: PASS.
- Integration head 7db32c9; 57 files changed, +7982/-24 vs origin/main.
- Gradle gate: NOT run at this station yet (no Android SDK on the lab host);
  next station step — run on a pod with the toolchain (W1's pod pattern) or
  the E2B substrate; tracked as the PROD-013 lab phase.

## Status
implemented + statically verified (lead); Gradle gate pending (lab phase);
NOT reconciled, NOT accepted (parity loop remains).

## PROD-013-lab gate (2026-09-28T06:0xZ — Worker 1's warm toolchain)

- VERDICT: **RED** — `:app:compileDebugKotlin` FAILED (130 errors; two runs:
  cold 2m21s + rerun 8s, identical). Full verbatim list:
  `lab/orchestration/deliveries/PROD-013/compile-errors-rerun.txt`.
- Distribution: W3 ocr/ 98 errors (root: transit TRUNCATION at the output cap
  — OcrEngine.kt unclosed-comment at the cap, OcrResult.kt:144 mid-expression;
  plus real defects: System.currentTimeMillis vs TimeSource, String==enum);
  W2 document/library+MainActivity 32 errors (root: code written against
  IMAGINED contract shapes — observeDocument(id)/updateTitle invented,
  upsertDocument(document):Boolean vs frozen (document,pages):Unit,
  TimeSource.System vs SYSTEM, launchScan vs startScan, Java-style
  host.fragmentManager() calls, fragmentFactory import);
  W1 capture/processing: **0 errors** (toolchain-verified deliveries).
- Worker 1 correctly stopped at step 4 (cross-worker failures, no fixes
  applied) and saved both error files for harvest.
- Fix routing (06:34-06:59Z): W2 comprehensive order (CropQuad elimination +
  missing IndexJsonCodec.kt + contract alignment of 6 PROD-005-layer files);
  W3 re-delivery order (truncated ocr/ files, <=3 files per reply + split
  markers, TimeSource fix). Re-gate after both land.
