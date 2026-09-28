
## PROD-013-lab gate (2026-09-28T06:0xZ — Worker 1's warm toolchain)

- VERDICT: **RED** — `:app:compileDebugKotlin` FAILED (130 errors; two runs:
  cold 2m21s + rerun 8s, identical). Full verbatim lists:
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
  W3 re-delivery order (truncated ocr/ files, ≤3 files per reply + split
  markers, TimeSource fix). Re-gate after both land.
