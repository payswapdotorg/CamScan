# CAMSCAN-004 — Reference Observations Report (Worker 2)

**Session:** `camscan004-reference-20260921T224152Z` · 2026-09-21 UTC
**Deliverable owner:** Worker 2 (reference/oracle) · **Verdict: BLOCKED (live observation) — static artifact analysis OBSERVED**

This report records the reference environment + session requirements for the
official CamScanner application, per work order CAMSCAN-004. S001–S018 are
provisional specs; the observations in this report are the oracle that corrects
them. Where this session could not observe, the status is **NOT-OBSERVED** —
per `lab/evidence/EVIDENCE.md` this is a legitimate verdict and is never
silently converted into a pass.

---

## 1. Reference environment specification (pinned, isolated)

Provisioned per the work order via the e2b LabProvider with
`EnvironmentSpec(purpose="reference")` — AVD **`camscan-reference`**, never
shared with Worker 1's implementation environment (`camscan-implementation`):

| property | value (provider defaults = EnvironmentSpec) |
|---|---|
| provider | `e2b` (E2B public `desktop` template, 8 vCPU / ~8 GB RAM / 25 GB disk) |
| acceleration | **TCG (`-accel off`)** — `emulator_acceleration: none` (substrate truth, `lab/substrate/VALIDATION-2026-09-21-TCG.md`) |
| Android | **11 (API 30)**, `system-images;android-30;default;x86_64` |
| device profile | `pixel_4`, 1080x2280, swiftshader_indirect |
| locale / timezone | `en-US` / `UTC` (deterministic via `-prop persist.sys.*`) |
| camera back | `virtualscene` (emulated) |
| capabilities used | `gui`, `adb`, `screenshots`, `recording`, `snapshot` (committed capability report) — **`camera_fixture: false`** (probed NOT injectable at the CAMSCAN-008 acceptance gate) |

**Status of this environment in this session: SPECIFIED, NOT EXERCISED** — see §3.

## 2. Reference application record (official CamScanner)

| field | value | status |
|---|---|---|
| application | CamScanner (INTSIG Information Co., Ltd) | OBSERVED (artifact) |
| package name | `com.intsig.camscanner` | OBSERVED (manifest, cryptographically verified) |
| version | `7.25.5.2609020000` (versionCode `72551`) — latest stable at 2026-09-21 | OBSERVED |
| source | APKCombo mirror (vendor site serves store links only): https://apkcombo.com/camscanner/com.intsig.camscanner/download/phone-7.25.5.2609020000-apk | OBSERVED (recorded) |
| installer sha256 | `7ef8e46525a1210bbaa332d5f5d560ce30d768b4375ea92265d2e292d25dba65` (XAPK bundle, 221,499,725 B) | OBSERVED |
| signature | v1 JAR (BNDLTOOL) by IntSig's own cert; PKCS#7 over `.SF` verified, `.SF` digest over `MANIFEST.MF` matches, **13,083/13,083 entry digests verified, 0 mismatches** — mirror tampering ruled out | OBSERVED |
| minSdk / targetSdk | 21 / 36 | OBSERVED |
| launchable activity | `com.intsig.camscanner.mainmenu.mainactivity.MainActivity` | OBSERVED |
| permissions requested | 38 uses-permissions + 3 declared custom (signature); classification install-time vs runtime for the Android 11 profile: see [static-apk-analysis.md](static-apk-analysis.md) | OBSERVED (manifest) |
| runtime prompts expected on Android 11 | `CAMERA`, `READ_EXTERNAL_STORAGE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | PREDICTED (unverified on device) |
| install-time grants expected on Android 11 | INTERNET & co. (normal); `WRITE_EXTERNAL_STORAGE` auto-granted-but-ineffective (targetSdk 36 ≥ 30) | PREDICTED (unverified) |
| Android version / device / locale / timezone | Android 11 x86_64, pixel_4, 1080x2280, en-US, UTC (reference env spec §1) | SPECIFIED (not yet exercised) |
| **account/session requirements** (does first-run demand login? which features gate on it?) | — | **NOT-OBSERVED** (needs live first-run) |
| **network behavior** (domains contacted at first run, logcat) | — | **NOT-OBSERVED** (needs live first-run; static hints: cleartext allowed, networkSecurityConfig, sentry/firebase/okhttp/netty stacks present) |
| outputs (files produced: names/formats/locations) | — | **NOT-OBSERVED** (needs live scan flow) |

## 3. Blocker — reference substrate credential absent from this worker sandbox

The work order states the `E2B_API_KEY` is "supplied in your sandbox environment
notes". **No such notes exist in this worker sandbox; an exhaustive search found
no `E2B_API_KEY`** (checked: full environment, `/etc/environment`, `.env` files,
`/run/secrets`, dotfiles, notes files). `SECURITY.md` confirms the credential
distribution model: *"`E2B_API_KEY` — Tech lead only"*; the lab host station
holds it. This matches `lab/orchestration/WORK-ORDERS.md` (CAMSCAN-009 — the
reference runs — is a separate work order).

Verbatim attempts (2026-09-21, this sandbox):

**Provider lifecycle via the CAMSCAN-004 observation driver** (`tools/reference-observe/observe.py`):

```
$ python3 tools/reference-observe/observe.py \
    --apk /home/z/camscan-apk/CamScanner_7.25.5.2609020000_apkcombo.com.xapk \
    --apk-source-url "https://apkcombo.com/camscanner/com.intsig.camscanner/download/phone-7.25.5.2609020000-apk"
[22:41:53] phase preflight: FAIL/BLOCKED
[22:41:53] verdict: BLOCKED  -> runs/camscan004-reference-20260921T224152Z/observation-result.json
EXIT=1
```
`observation-result.json` (verbatim):
```json
{
  "gate": "CAMSCAN-004",
  "run_id": "camscan004-reference-20260921T224152Z",
  "phases": [
    {
      "name": "preflight",
      "ok": false,
      "detail": {
        "reason": "E2B_API_KEY not set — the e2b provider requires the E2B API key in the environment (never in git/evidence)"
      },
      "ts_utc": "2026-09-21T22:41:53Z"
    }
  ],
  "apk": {
    "path": "/home/z/camscan-apk/CamScanner_7.25.5.2609020000_apkcombo.com.xapk",
    "source_url": "https://apkcombo.com/camscanner/com.intsig.camscanner/download/phone-7.25.5.2609020000-apk",
    "exists": true,
    "bytes": 221499725,
    "sha256": "7ef8e46525a1210bbaa332d5f5d560ce30d768b4375ea92265d2e292d25dba65"
  },
  "package_expected": "com.intsig.camscanner",
  "verdict": "BLOCKED",
  "blocker": "reference substrate unavailable in this worker sandbox: E2B_API_KEY not set — the e2b provider requires the E2B API key in the environment (never in git/evidence)",
  "finished_utc": "2026-09-21T22:41:53Z"
}
```

**CAMSCAN-008 acceptance gate (same substrate, no key):**
```
$ python3 lab/providers/e2b/acceptance.py
[22:41:59] capabilities: accel=none emulator=True adb=True
[22:41:59] provision: fresh E2B sandbox + baked bootstrap ...
[22:42:00] [!] GATE FAILURE: bootstrap failed on both attempts: AuthenticationException: API key is required, please visit the API Keys tab at https://e2b.dev/dashboard?tab=keys to get your API key. You can either set the environment variable `E2B_API_KEY` or you can pass it directly to the method like api_key="e2b_..."
[22:42:00] verdict: FAIL  -> lab/providers/e2b/.acceptance/acceptance_result.json
```

The E2B API itself is reachable from this sandbox
(`https://api.e2b.dev/health` → `Health check successful`) — the blocker is the
absent credential, not network isolation. Per SECURITY.md the credential must be
env-injected at the lab host station; it must never be requested through chat,
committed, or echoed into reports — hence this BLOCKED record instead.

## 4. What WAS observed (static, verifiable)

Everything derivable from the genuine artifact without a device — full detail in
[static-apk-analysis.md](static-apk-analysis.md) +
[static-apk-analysis.json](static-apk-analysis.json):

- official artifact obtained, source + sha256 recorded (§2);
- signature chain cryptographically verified (IntSig's own key covers every
  v1-signed byte; 13,083/13,083 entry digests match);
- application identity, launchable activity, min/target SDK;
- complete permission surface with install-time vs runtime classification for
  the Android 11 reference profile (input to S003/S011 spec corrections);
- uses-feature set (all optional — no install filtering on the emulator);
- **ABI risk discovery**: native code is arm64-only while the pinned substrate
  image is x86_64 AOSP without ARM translation (§6 open questions);
- packaging metadata hints (Flutter AI-chat module, sentry, firebase) — recorded
  as hints only, not observations of behavior.

## 5. Scenario observation records (S001–S018)

Per-scenario observation protocol + status lives in
[S001-launch.md](S001-launch.md) … [S018-sharing.md](S018-sharing.md).
Every dynamic observation is **NOT-OBSERVED (blocked by §3)**. Summary:

| scenario | area | status | static-informed notes |
|---|---|---|---|
| S001 | launch | NOT-OBSERVED | launchable activity verified statically; first-run screen state unobserved |
| S002 | onboarding | NOT-OBSERVED | login-gate question open (needs live first run) |
| S003 | camera permission | NOT-OBSERVED | CAMERA is a runtime dangerous perm on Android 11 (manifest-verified); FINE/COARSE_LOCATION also runtime — expect possible bundled prompt |
| S004 | single capture | NOT-OBSERVED | requires camera fixture — provider `camera_fixture: false` (unresolved lab-level blocker) |
| S005 | auto-detection | NOT-OBSERVED | same camera-fixture dependency |
| S006 | crop confirmation | NOT-OBSERVED | — |
| S007 | manual crop | NOT-OBSERVED | — |
| S008 | perspective correction | NOT-OBSERVED | — |
| S009 | enhancement | NOT-OBSERVED | — |
| S010 | rotate | NOT-OBSERVED | — |
| S011 | save | NOT-OBSERVED | targetSdk 36 + scoped storage on Android 11: outputs likely app-scoped (`/sdcard/Android/data/com.intsig.camscanner/files`) or MediaStore — spec should not assume `/sdcard/CamScanner` |
| S012 | reopen | NOT-OBSERVED | — |
| S013 | multi-page | NOT-OBSERVED | camera-fixture dependency |
| S014 | delete | NOT-OBSERVED | — |
| S015 | OCR | NOT-OBSERVED | cloud vs on-device split unknown; network dependency likely (usesCleartextTraffic=true) |
| S016 | PDF export | NOT-OBSERVED | — |
| S017 | JPG export | NOT-OBSERVED | — |
| S018 | share | NOT-OBSERVED | — |

Proposed spec corrections (lead-owned YAMLs/ledger untouched — proposals only)
are collected in [../capability-map.md](../capability-map.md).

## 6. Open questions / handoffs

1. **UNBLOCK (lead):** run `tools/reference-observe/observe.py` at the lab host
   station with `E2B_API_KEY` env-injected (never in git/evidence). The driver
   performs the full provision→destroy lifecycle and writes
   `runs/<run-id>/observation-result.json` + the provider's hash-verified
   evidence bundle. Artifact stays outside git; hashes recorded.
2. **ABI risk (lead decision):** arm64-only split set vs `…default;x86_64`
   AOSP image (no ARM translation). Probe order: (a) `adb install-multiple
   base + config.arm64_v8a + config.en` on the pinned image and observe
   launch/logcat for `UnsatisfiedLinkError`; (b) if it fails, either switch the
   reference `EnvironmentSpec.system_image` to
   `system-images;android-30;google_apis;x86_64` (adds ndk_translation;
   deviates from the lead-pinned `default` image — substrate decision), or
   (c) source an x86/x86_64-native or fat/monolithic official build variant.
3. **camera_fixture: false (lab-level):** S004/S005/S013 (+parts of S003)
   `meta.requires camera_fixture: true` but the committed e2b capability report
   says virtualscene poster injection is NOT available on the public template.
   Either a fixture mechanism is developed (CAMSCAN-003 scope), or those
   scenarios need re-scoping to `virtualscene` scene content as-is.
4. **CAMSCAN-003 fixtures** not yet merged at this base sha — no
   `lab/fixtures/manifest.json`; capture scenarios await the corpus.
5. **Account/session requirements** remain the biggest spec unknown: does
   first-run demand login? which S0xx features gate on it? First live session
   must record the onboarding decision tree verbatim (screenshots + ui dumps).

## 7. Session evidence manifest (this blocked session)

Durable session artifacts (git); the 211 MB XAPK stays outside git (R2 candidate
`camscan-parity-evidence/camscan004/reference-apk/…` when credentials allow):

| file | role |
|---|---|
| `runs/camscan004-reference-20260921T224152Z/observation-result.json` | blocked driver attempt (verbatim in §3) |
| `docs/reference-observations/static-apk-analysis.md` / `.json` | verified static observations |
| `docs/reference-observations/S001-launch.md` … `S018-sharing.md` | per-scenario observation protocols + statuses |
| `docs/capability-map.md` | mission §6 feature inventory, statuses + correction proposals |
| `docs/reference-observations/session-manifest.json` | sha256 of every session artifact (below) |

Session hashes are recorded in `session-manifest.json` (generated at commit
time — see the file for exact values; generation command recorded inside it).
