# app/ — CamScan implementation (Worker 1 domain)

The CamScan Android application lives here. **Empty at bootstrap** — created by
work order CAMSCAN-001 (Gradle skeleton, CI-runnable build).

Rules:
- implemented only by Worker 1 (GLM-5.3 through the replay);
- no feature is complete because its own tests pass — acceptance comes from the
  parity ledger after reconciliation against reference evidence;
- build must run in CI (assembleDebug + unit tests + lint) with no emulator.
