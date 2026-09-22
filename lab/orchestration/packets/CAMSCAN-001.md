# CAMSCAN-001 — CamScan Android app skeleton (Worker 1)

You are Worker 1 (implementation worker) for payswapdotorg/CamScan — an Android
document-scanner app that will be reconciled against CamScanner in a parity lab.
This task: create the Gradle application skeleton ONLY. No feature code.

## Setup
- Clone https://github.com/payswapdotorg/CamScan.git at base sha de96877675ff8bf91ccef174abc2679311d273ff; branch `work/CAMSCAN-001`.
- Work in `app/` only (you own it; do not touch other paths).

## Task
1. `app/` becomes a standard Android Gradle project (Kotlin, single module `:app`,
   minSdk 26, targetSdk 35, view-based UI, Material 3 theme).
2. `assembleDebug` produces an installable APK; `assembleRelease` works with a debug
   signing config placeholder.
3. Unit tests: at least one trivial unit test that runs in CI (`testDebugUnitTest`).
4. Lint passes (`lintDebug`) — zero errors.
5. Root screen: a single placeholder activity with the app label "CamScan" (no more —
   features come from parity scenarios, never invent ahead of the oracle).
6. CI: extend `.github/workflows/ci.yml` with a job that runs
   `./gradlew assembleDebug testDebugUnitTest lintDebug` on ubuntu-latest with JDK 17 +
   Android SDK preinstalled. Keep the existing `control-plane-gates` job untouched.

## Rules
- Deterministic builds: commit `gradle/libs.versions.toml` / wrapper jar per repo norm.
- No credentials, no network at runtime, no emulator requirement in CI.
- Do not implement scanning/capture/etc. — skeleton only.

## Verification (run these, paste outputs verbatim in the report)
```
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

## Final report — use EXACTLY this headline:
```
=== CAMSCAN-001 COMPLETION REPORT ===
task: app skeleton
environment: (toolchain versions)
what was implemented: …
verification: (verbatim command outputs)
evidence: (file list + sha256)
assumptions: …
open questions / handoffs: …
base sha: de96877675ff8bf91ccef174abc2679311d273ff
```
Push branch `work/CAMSCAN-001` and report branch + HEAD sha. The tech lead re-runs
all gates at the integration station.
