# CamScanner Capability Map — the reference oracle (CAMSCAN-004)

> The mission §6 feature inventory, instantiated against the OFFICIAL CamScanner
> Android application with per-capability discovery status. **Statuses are
> OBSERVED / NOT-OBSERVED — never assumed.** An untested capability is never a
> pass (`lab/evidence/EVIDENCE.md`).
>
> Session: `camscan004-reference-20260921T224152Z` (2026-09-21, Worker 2).
> Live on-device observation was **BLOCKED** — the reference substrate
> credential (`E2B_API_KEY`, lead-held per `SECURITY.md`) is absent from this
> worker sandbox; verbatim attempts in
> [`reference-observations/REPORT.md §3`](reference-observations/REPORT.md).
> Static artifact analysis of the official APK **was performed and
> cryptographically verified** — those capabilities below are OBSERVED.

## 1. Statuses at a glance

| capability (mission §6 area) | scenario | discovery status | evidence |
|---|---|---|---|
| application identity & provenance | — | **OBSERVED (static)** | [static-apk-analysis.md](reference-observations/static-apk-analysis.md) |
| installer artifact integrity (signature) | — | **OBSERVED (static)** | same — 13,083/13,083 digests verified |
| permission surface (install-time/runtime) | S003 input | **OBSERVED (manifest)** / on-device grant flow NOT-OBSERVED | same + [S003](reference-observations/S003-camera-permission.md) |
| launch | S001 | **NOT-OBSERVED** | [S001](reference-observations/S001-launch.md) |
| onboarding | S002 | **NOT-OBSERVED** | [S002](reference-observations/S002-onboarding.md) |
| camera permission flow | S003 | **NOT-OBSERVED** (runtime dialog behavior) | [S003](reference-observations/S003-camera-permission.md) |
| single-document capture | S004 | **NOT-OBSERVED** | [S004](reference-observations/S004-single-document-capture.md) |
| auto document detection | S005 | **NOT-OBSERVED** | [S005](reference-observations/S005-automatic-document-detection.md) |
| crop confirmation | S006 | **NOT-OBSERVED** | [S006](reference-observations/S006-crop-confirmation.md) |
| manual crop | S007 | **NOT-OBSERVED** | [S007](reference-observations/S007-manual-crop.md) |
| perspective correction | S008 | **NOT-OBSERVED** | [S008](reference-observations/S008-perspective-correction.md) |
| enhancement modes | S009 | **NOT-OBSERVED** | [S009](reference-observations/S009-enhancement.md) |
| rotate | S010 | **NOT-OBSERVED** | [S010](reference-observations/S010-rotate.md) |
| save to library | S011 | **NOT-OBSERVED** | [S011](reference-observations/S011-save.md) |
| reopen saved document | S012 | **NOT-OBSERVED** | [S012](reference-observations/S012-reopen-saved-document.md) |
| multi-page scan | S013 | **NOT-OBSERVED** | [S013](reference-observations/S013-multi-page-scan.md) |
| document deletion | S014 | **NOT-OBSERVED** | [S014](reference-observations/S014-document-deletion.md) |
| OCR | S015 | **NOT-OBSERVED** | [S015](reference-observations/S015-ocr.md) |
| PDF export | S016 | **NOT-OBSERVED** | [S016](reference-observations/S016-pdf-export.md) |
| JPG export | S017 | **NOT-OBSERVED** | [S017](reference-observations/S017-jpg-export.md) |
| sharing | S018 | **NOT-OBSERVED** | [S018](reference-observations/S018-sharing.md) |
| account/session requirements | cross-cutting | **NOT-OBSERVED** (open question #1) | [REPORT §2](reference-observations/REPORT.md) |
| first-run network behavior | cross-cutting | **NOT-OBSERVED** | [REPORT §2](reference-observations/REPORT.md) |

OBSERVED-static facts summarized:

- **App:** `com.intsig.camscanner` v`7.25.5.2609020000` (code `72551`),
  minSdk 21 / targetSdk 36, launchable activity
  `com.intsig.camscanner.mainmenu.mainactivity.MainActivity`.
- **Artifact:** XAPK bundle sha256
  `7ef8e46525a1210bbaa332d5f5d560ce30d768b4375ea92265d2e292d25dba65`,
  from APKCombo (vendor site has no direct APK). v1-signed by IntSig's own
  certificate; full digest audit passed — genuine, untampered official build.
- **Permissions:** 38 uses-permissions (4 runtime-dangerous active on the
  Android 11 reference profile: `CAMERA`, `READ_EXTERNAL_STORAGE`,
  `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) + 3 declared custom
  signature permissions; `WRITE_EXTERNAL_STORAGE` declared but ineffective
  (targetSdk 36 ≥ 30 on Android 11).
- **ABI risk:** native code arm64-only (config split); reference image is
  x86_64 AOSP without ARM translation — first live run MUST probe this.

## 2. Corrected S001–S018 behavior notes (proposals for the lead)

The provisional specs stay untouched (lead-owned). Corrections discovered by
this session, to be applied by the lead after live verification:

| # | provisional assumption | correction proposal | basis |
|---|---|---|---|
| 1 | S001 `cold-start-under-threshold` | pin a concrete threshold (suggest ≤ 30 s to first frame under TCG) | provider timing model |
| 2 | S002 `onboarding-shown-on-first-run` | may be false for modern CamScanner (EULA-on-first-scan instead of a pager) — verify before locking | unverified; flagged |
| 3 | S002 lacks account assertions | add `login-{required,optional}-for-core-scan` decision record | work-order question |
| 4 | S003 single permission prompt | expect possible bundled LOCATION prompt too (both fine/coarse are runtime-dangerous in the manifest) | OBSERVED (manifest) |
| 5 | S004/S011 `pdf-created` as save output | default save format may be in-app jpg; PDF may be export-only (S016) — verify empirically | unverified; flagged |
| 6 | S011 output location | do not assume `/sdcard/CamScanner`: scoped storage applies (targetSdk 36, Android 11, `WRITE_EXTERNAL_STORAGE` ineffective) — outputs expected app-scoped or MediaStore | OBSERVED (manifest) |
| 7 | S003/S004/S005/S013 `requires: camera_fixture` | currently unsatisfiable on e2b (`camera_fixture: false`, CAMSCAN-008 probe) — fixture mechanism (CAMSCAN-003) or re-scope needed | committed capability report |
| 8 | S008 `rectified-page-geometry` | make measurable: corner angles 90°±tolerance, semantic not pixel-perfect | LAB.md parity model |
| 9 | S015 OCR | record cloud-vs-local + account-gating; offline OCR is a required edge case (cleartext traffic allowed in manifest) | OBSERVED (manifest hint) |
| 10 | S017 JPG export | add EXIF-geotag presence/absence observable (location permissions requested) | OBSERVED (manifest) |
| 11 | all capture scenarios | ABI resolution needed first: arm64-only splits on x86_64 AOSP image — probe install-multiple + launch; fallback google_apis image or x86 build variant | OBSERVED (artifact) |

## 3. How statuses flip to OBSERVED

Run the reference observation at the lab host station (credential env-injected,
never in git/evidence):

```
E2B_API_KEY=… python3 tools/reference-observe/observe.py \
    --apk <official-bundle> --apk-source-url "<url>"
```

The driver executes provision(`purpose="reference"`) → TCG boot → install
(XAPK-aware: `install-multiple` splits) → package facts → first-run captures →
state scan → offline probe → second run → hash-verified evidence bundle →
destroy, writing `runs/<run-id>/observation-result.json`. Interactive scenario
walks (S004–S018 flows) then follow the per-scenario protocols in
[reference-observations/](reference-observations/) using provider
`interact`/`capture` (target registry from CAMSCAN-002 when it lands).

## 4. Reference environment parameters (pinned for comparability)

Android 11 (API 30) x86_64, `pixel_4`, 1080x2280, locale `en-US`, timezone
`UTC`, camera-back `virtualscene`, TCG (`-accel off`), AVD `camscan-reference`
— identical profile for implementation-side runs (parity requires the same
fixture ids + hashes, device profile, locale/timezone and permission baseline;
`lab/evidence/EVIDENCE.md`).
