# tools/reference-observe — CAMSCAN-004 reference observation driver

Worker 2's driver for observing the OFFICIAL CamScanner application in the
isolated e2b reference environment (AVD `camscan-reference` — never shared with
Worker 1's implementation environment). Observable behavior only: no reverse
engineering, no decompilation, no copying proprietary source/assets.

## Files

| file | role |
|---|---|
| `observe.py` | the lifecycle driver: preflight → provision(`purpose="reference"`) → TCG boot → install → package facts → first-run captures → state scan → offline probe → second run → evidence bundle → destroy |
| `gen_scenario_records.py` | regenerates the per-scenario observation records in `docs/reference-observations/S0xx-*.md` (deterministic table-driven content) |

## Usage (lab host station — holds `E2B_API_KEY`)

```bash
pip install -r lab/providers/e2b/requirements.txt   # e2b SDK
export E2B_API_KEY=...                              # env only — never in git/evidence/logs

python3 tools/reference-observe/observe.py \
    --apk /path/to/CamScanner_<ver>_apkcombo.xapk \
    --apk-source-url "https://apkcombo.com/camscanner/com.intsig.camscanner/download/phone-<ver>-apk" \
    [--package com.intsig.camscanner] [--out runs/] [--keep-env]
```

- `--apk` accepts a plain `.apk` (`adb install -r`) **or** an `.xapk`/`.apks`
  split bundle (auto-extracted; installed via `adb install-multiple` — the
  official CamScanner is distributed as splits: base + `config.arm64_v8a` +
  `config.en`).
- Exit 0 only on full PASS; every phase (including a BLOCKED preflight) is
  recorded in `runs/<run_id>/observation-result.json`.
- Output layout: `runs/<run_id>/{observation-result.json, observation-notes.json,
  health_report.json, storage_before_first_run.txt, storage_after_first_run.txt,
  splits/, evidence/<run_id>/{manifest.json, environment.json, trace.jsonl, cap/}}`.

## Known blockers / notes (2026-09-21 session)

1. `E2B_API_KEY` is lead-held; running without it exits BLOCKED at preflight
   with the provider's verbatim error (recorded in
   `docs/reference-observations/REPORT.md §3`).
2. **ABI risk:** the official bundle's native code is arm64-only; the pinned
   image `system-images;android-30;default;x86_64` has no ARM translation.
   First live run must watch for `UnsatisfiedLinkError` (mitigations in
   REPORT §6).
3. Camera-fixture-dependent flows (S004/S005/S013) additionally require a
   working fixture mechanism — the e2b provider reports `camera_fixture: false`
   today.

## Regenerating the scenario records

```bash
python3 tools/reference-observe/gen_scenario_records.py
```

Content is table-driven; edit the `S = {...}` table and re-run.
