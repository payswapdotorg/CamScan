You are Worker 3 (OCR, Tools & Verification track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-010 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

> **EXECUTION DIRECTIVE (binding, platform lesson):**
> do NOT stop to acknowledge this packet. Your FIRST action is §3 setup; your
> LAST action is the §9 final report. Execute end-to-end in THIS turn:
> §3 setup -> §6 implement -> §7 checks -> §8 commit+bundle -> §9 report as your
> reply. No acks, no status updates, no clarifying questions. If genuinely
> blocked, the report states the blocker honestly with everything you did
> complete. If your turn is interrupted mid-work, the next directive resumes
> from your persisted sandbox state.

=== CAMSCAN-PROD-010 — LIVE OCR ENGINE BINDING: ML KIT (Worker 3) ===

# 1. ROLE

You are Worker 3 on a permanent three-worker product program building **CamScan** — an independent, clean-room
Android document-scanning application (applicationId `org.payswap.camscan`); the public CamScanner app is the
observable-behavior oracle, NEVER an implementation dependency. You own the **OCR / tools / verification
surface**. Wave 1 shipped your OCR foundation (PROD-009: the OcrEngine seam, OcrResult model,
DeterministicStubEngine, harness); this order binds the REAL on-device engine behind that frozen seam. The
shared contracts in §4 are frozen and lead-owned; report needed changes as open questions instead of making
them.

# 2. PROGRAM CONTEXT

- Repository: https://github.com/payswapdotorg/CamScan.git (public, anonymous clone works). After cloning, READ
    `docs/PRODUCT-ARCHITECTURE-LOCK.md` (§11 OCR — adapter behind a stable interface; on-device preferred; OCR
    never blocks the scan path), `docs/SCAN-ENGINE-CONTRACT.md`, `lab/orchestration/PRODUCT-WORK-BOARD.yaml`.
- **CAMSCAN-PROD-009 is DELIVERED, integrated, gate-green** (inside PROD-013's verdict, after your re-delivery and
    the lead's station repair); its fix-pass head on work/CAMSCAN-PROD-009 is your base. Wave 2 = the daily
    workflow; its integration gate is CAMSCAN-PROD-014; this order is the wave-2 W3 slice: the live engine.
- **Engine selection (LEAD-DECIDED):** PROD-009's engine-selection analysis was lost in transit, so the lead
    re-ran it and decided: **Google ML Kit on-device text recognition, bundled variant**
    (`com.google.mlkit:text-recognition:16.0.1`). On-device/offline ✓ (bundled model: no network, no play-services
    wait at recognition time); Apache-2.0 ✓; minSdk 21 ✓ (covers our 26); ~4-5MB APK impact — one-time and
    bounded; deterministic per pinned model version ✓ (the program pins 16.0.1; cross-version output is NOT
    guaranteed and never claimed); clean Task-based async API that bridges to the seam's suspend recognize.
    Runner-up: Tesseract4Android — non-Latin script coverage, larger footprint, weaker determinism ergonomics; a
    later wave if non-Latin becomes a requirement. This is DECIDED — implement ML Kit; do not re-litigate.
- **The catalog amendment is LEAD-OWNED and lands BEFORE dispatch:** `gradle/libs.versions.toml` gains
    `mlkitTextRecognition = "16.0.1"` under [versions] and the `text-recognition` entry (group `com.google.mlkit`)
    under [libraries]; `app/build.gradle.kts` gains the corresponding implementation line. You consume read-only
    and report needs. If the amendment is NOT at your base, STOP and report the blocker honestly — the engine
    cannot compile without it; do not edit the catalog yourself.
- Offline-first: no network at recognition time. No account. No cloud.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git fetch origin work/CAMSCAN-PROD-009
git checkout -b work/CAMSCAN-PROD-010 d936650
git config user.name "CamScan Worker 3"
git config user.email "worker3@camscan.invalid"
git rev-parse HEAD   # MUST print the full 40-hex sha starting d936650
ls app/src/main/java/org/payswap/camscan/ocr/engine/   # your PROD-009 files
grep -n "mlkitTextRecognition" gradle/libs.versions.toml   # MUST hit (lead pre-landed)
grep -n "text-recognition" app/build.gradle.kts           # MUST hit (lead pre-landed)
```

(d936650 is the PROD-009 fix-pass head on work/CAMSCAN-PROD-009 — pinned in the dispatch message header; the
lead confirms it here: BASE_SHA: d936650)

# 4. SHARED CONTRACT LAYER + YOUR FROZEN PRIOR DELIVERY (consume read-only)

Code against the REAL files at your base — never remembered or imagined shapes (the gate-1 lesson). Where this
summary and the base files differ, THE BASE FILES WIN and you report the discrepancy:

- The wave-0 core contracts, read-only as always: `core/model/Documents.kt` (Page.ocrResultId ties an OCR result
    to its page), `core/time/TimeSource.kt`, `core/repository/DocumentRepository.kt`,
    `core/storage/ContentStore.kt`.
- **Your PROD-009 delivery — FROZEN for this order** (the harness, the stub and the PROD-014 gate depend on stable
    shapes; needed changes are open questions, not edits): `ocr/engine/OcrEngine.kt` —
    `interface OcrEngine { val engineId: String; suspend fun recognize(image: OcrImage, settings: OcrSettings): OcrOutcome; fun close() }`;
    OcrImage(bytes, width, height, format PNG|JPEG|UNKNOWN, rotationDegrees); OcrSettings(languageHints, mode
    FAST|BALANCED|ACCURATE); OcrOutcome = Success(result) | Failure(reason); OcrFailure sealed =
    UNSUPPORTED_FORMAT | EMPTY_IMAGE | CORRUPT_IMAGE | ENGINE_ERROR(message) | CLOSED — failures are VALUES, not
    exceptions, at the seam. `ocr/engine/OcrResult.kt` — OcrResult(resultId, pageId?, blocks, fullText, engineId,
    settingsEcho, recognisedAtMillis, processingDurationMillis, meanConfidence, toStableString());
    OcrTextBlock(text, confidence 0..1, box normalized l/t/r/b in 0..1 page space, blockIndex in reading order).
    The box space and the fullText join rule are contracts your mapper must MATCH, not reinvent.
- `ocr/engine/DeterministicStubEngine.kt` ("stub-deterministic"), `ocr/engine/UnavailableEngine.kt` ("unavailable"
    — every recognize -> Failure(ENGINE_ERROR("ocr unavailable"))), `ocr/util/Digests.kt`, `ocr/harness/**`. Plus
    `gradle/libs.versions.toml` + `app/build.gradle.kts`: lead-amended per §2 — read-only.

# 5. YOUR OWNERSHIP BOUNDARY (binding)

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/ocr/**` (new `ocr/engine/mlkit/` and `ocr/engine/catalog/` trees;
    existing PROD-009 files ONLY where a compile-blocker demands it — minimal diff, loudly flagged in §9 as an
    open question)
- `app/src/test/java/org/payswap/camscan/ocr/**` (your JVM tests — the main verification surface)

You MUST NOT touch: the PROD-009 seam/result-model semantics (§4 — frozen this order); anything under
`capture/`, `processing/`, `core/image/`, `document/`, `library/`, `export/`, `settings/`, `search/`, `tools/`,
`MainActivity.kt`, any res file; `app/src/androidTest/**` (Worker 2's board path — the real-engine smoke
skeleton ships as a lead-transplant handoff per §6.5, not as a committed file); `gradle/`,
`app/build.gradle.kts`, `AndroidManifest.xml`; anything outside `app/`.

# 6. TASK — LIVE OCR ENGINE BINDING (ML KIT)

1. **MlKitOcrEngine** (`ocr/engine/mlkit/`): implements the EXISTING OcrEngine seam;
      `engineId = "mlkit-text-v2-latin"`. Task-to-coroutine bridge: recognize awaits ML Kit's Task via
      `suspendCancellableCoroutine`; on coroutine cancellation, cancel the underlying task where the ML Kit API
      allows it and let the coroutine resume as cancelled — never leak a running task. `close()` releases the
      TextRecognition client exactly once (idempotent; double-close safe). CLOSED semantics match the seam's
      failure-as-value discipline that UnavailableEngine embodies: after close(), every recognize returns
      Failure(CLOSED), never throws; verify UnavailableEngine's exact post-close behavior at base and keep the two
      consistent (mismatch -> open question). Pre-flight validation BEFORE any ML Kit call, mirroring the stub's
      degenerate-input handling: empty bytes or non-positive dims -> EMPTY_IMAGE; format UNKNOWN ->
      UNSUPPORTED_FORMAT; then construct the InputImage from OcrImage (bytes + dims + rotationDegrees) via the ML
      Kit-supported path you verify against the landed 16.0.1 API — construction/decode failure -> CORRUPT_IMAGE.
2. **Result mapping — pure mapper + thin adapter** (`ocr/engine/mlkit/`): `MlKitShapes.kt` — minimal ML Kit-shaped
      DTOs, PURE Kotlin with zero ML Kit and zero android imports: MlKitTextDto(blocks), MlKitBlockDto(text, box,
      lines), MlKitLineDto(text, box, confidence: Float?), MlKitElementDto(text, box) — mirroring ML Kit's
      Text/TextBlock/Line/Element reading order. The android adapter (the ONLY file importing the live ML Kit text
      classes) converts a recognized Text into the DTOs (Rect -> box fields); `MlKitMapper.kt` (pure) maps DTOs +
      image dims into OcrResult. Box normalization documented: ML Kit boxes are pixel rects in the INPUT image
      coordinate space; normalized OcrBox = LEFT/width, TOP/height, RIGHT/width, BOTTOM/height against the dims of
      the InputImage AS CONSTRUCTED (i.e. OcrImage.width/height, pre-rotation), clamped to 0..1 (boxes may exceed
      image bounds by a few pixels — document the clamp); if the station smoke test proves ML Kit's returned space
      is the rotated frame, the correction lands in ONE documented place in the mapper. Confidence policy
      documented: ML Kit v2 Latin exposes per-LINE confidence where available; block confidence = mean of its
      lines' confidences; a line with no confidence value takes the named constant `DEFAULT_CONFIDENCE = 1.0f`
      (missing is not low — a 0.0 default would poison meanConfidence; the constant makes the policy a one-line
      change). Text joining: fullText built by the SAME deterministic join rule OcrResult's fullText already uses —
      read it at base and reuse it; do not invent a second rule; blockIndex follows ML Kit's block reading order.
      resultId via the same id convention the stub uses; recognisedAtMillis via the injected TimeSource
      (constructor parameter, never direct clock reads); processingDurationMillis measured honestly around the
      await.
3. **Degenerate-input taxonomy** (documented mapping table in KDoc + tests): EMPTY_IMAGE <- pre-flight empty bytes
      / non-positive dims; UNSUPPORTED_FORMAT <- OcrImageFormat.UNKNOWN; CORRUPT_IMAGE <- InputImage construction
      failure / Task fails with image-decode-class errors / recognizer returns null Text; ENGINE_ERROR(message) <-
      every other Task failure, the ML Kit message preserved honestly; CLOSED <- recognize after close(). Task
      success with ZERO blocks is NOT a failure: Success with empty blocks and empty fullText (an honest blank
      page).
4. **Engine availability + fallback composition** (`ocr/engine/catalog/`): `OcrEngineCatalog` (factory) with an
      explicit `OcrEnginePolicy` value — PREFER_LIVE / STUB_ONLY / UNAVAILABLE; no environment sniffing magic — the
      caller states the policy. Documented rule: PREFER_LIVE -> MlKitOcrEngine when the dependency resolves and
      client construction succeeds, else UnavailableEngine (OCR never blocks the scan path); STUB_ONLY ->
      DeterministicStubEngine (the harness, JVM tests and station parity runs stay stub-driven unless a live run is
      explicitly requested); UNAVAILABLE -> UnavailableEngine. Runtime wiring into MainActivity is NOT yours this
      order (ownership): the catalog ships as a library seam and the wiring point is a §9 handoff for the lead at
      PROD-014.
5. **JVM tests** (`app/src/test/java/org/payswap/camscan/ocr/`) — NO real ML Kit anywhere in the test tree: Mapper
      (SYNTHETIC DTOs — build the fake Text/Block/Line/Element structures in the test tree) — reading order ->
      blockIndex sequence; box normalization math incl. the clamp; confidence aggregation + the DEFAULT_CONFIDENCE
      policy; fullText join matching OcrResult's rule; determinism (same DTOs + fixed ids/time -> byte-identical
      toStableString twice). MlKitOcrEngine against a FAKE recognizer handle (inject the client behind a minimal
      `TextRecognizerHandle` interface you define; the fake completes/fails/cancels on demand) — success ->
      Success; each failure mode -> the documented taxonomy mapping; cancellation -> coroutine cancels + task
      cancel invoked; close-then-recognize -> CLOSED; double-close safe; pre-flight failures never reach the
      handle. Catalog — the policy matrix with fakes (PREFER_LIVE + available -> mlkit; construction failure ->
      unavailable; STUB_ONLY -> stub; UNAVAILABLE -> unavailable). Real-engine smoke: author
      `MlKitOcrEngineSmokeTest.kt` as a DELIVERY-HANDOFF — the full source goes in DELIVERY-PROD-010.txt with the
      target path `app/src/androidTest/java/org/payswap/camscan/ocr/` (Worker 2's board path; the LEAD plants and
      runs it at the integration station; annotated LEAD-STATION-ONLY): one bundled-model recognize on a synthetic
      Canvas-drawn fixed string, asserting Success, non-empty fullText, engineId echo, and the box-coordinate-space
      assumption from §6.2. NOT committed to your branch.

**Stable semantic ids:** none introduced (no UI this order); all existing app ids unchanged.

**Discipline:** Kotlin 2.0, JVM 17, minSdk 26; the ONLY new dependency is the lead-landed ML Kit line — nothing
else; android.* imports confined to the ONE android-side adapter file; ML Kit / gms-tasks imports confined to
`ocr/engine/mlkit/**`; every other file in ocr/** stays JVM-clean; the JVM test tree stays ML Kit-free and
android-free; time via TimeSource; determinism wherever the mapper is pure.

**TRANSIT-CORRUPTION PROTOCOL (binding, new 2026-09-28):** the chat renderer that
carries your delivered code corrupts it in transit — it eats dollar-sign characters
in string templates (math-delimiter rendering), halves double backslashes, mangles
multi-line KDoc closers, and can eat closing braces. The gate-2 forensics proved this
on the raw transcripts. Therefore, in EVERY file you deliver:
- Use plain string CONCATENATION instead of interpolated templates wherever a
  template would appear — build strings with the `+` operator and explicit
  `.toString()` calls. NO dollar signs inside any delivered string literal.
- Prefer single-line KDoc (`/** Text. */`) or `//` comments; avoid multi-line
  `/** ... */` blocks whose closers can be mangled.
- Avoid double-backslash escape sequences where concatenation of explicit
  characters works.
The lead station-repairs any residual damage and the gate re-verifies — but code
delivered per this protocol survives transit verbatim.

# 7. VERIFICATION (honest, bounded)

Your JVM suite is the main surface: run `./gradlew :app:testDebugUnitTest` (plus
`:app:lintDebug :app:assembleDebug`) if the toolchain is warm — the ML Kit line resolves from the lead's catalog
amendment on first build (needs network or a warm Gradle cache; if neither, deliver statically and say so
plainly). Static self-checks always: clean tree; `git diff --stat` review; ownership audit against §5 (ocr/**
only); grep that ML Kit / gms-tasks imports appear ONLY under ocr/engine/mlkit/**; grep that the JVM test tree
contains zero real ML Kit references; NEVER fabricate output. Toolchain repair budget ~20 min.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-010: live OCR engine binding — MlKitOcrEngine on the frozen PROD-009 seam, pure DTO mapper with documented box/confidence policies, failure taxonomy, engine catalog + fallback on the PROD-009 fix-pass base"
git bundle create camscan-prod-010.bundle d936650..work/CAMSCAN-PROD-010
cp camscan-prod-010.bundle ../camscan-prod-010.bundle
sha256sum camscan-prod-010.bundle
git diff --stat d936650..work/CAMSCAN-PROD-010
```

Also write `CamScan/DELIVERY-PROD-010.txt`: every added/changed file path, the bundle sha256, AND the full
MlKitOcrEngineSmokeTest.kt handoff source with its target path. Copy it + the bundle to the workspace root. Your
sandbox files ARE the delivery channel — the Tech Lead harvests them (pod files-API or an inline-transit order).
Do not delete your work. Do not push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-010 COMPLETION REPORT ===
task: live OCR engine binding (Google ML Kit on-device text recognition, bundled)
environment: sandbox OS + toolchain actually available (state plainly if no network / no toolchain)
what was implemented/observed: …
verification: commands + outputs (verbatim — never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-010.txt) — no raw bytes
assumptions: … (MUST include the box-coordinate-space assumption awaiting the station
smoke test)
open questions / handoffs: … (MUST include: the MainActivity wiring point for the
catalog; the androidTest smoke transplant; any §4 seam discrepancy found; any
PROD-009 file you had to touch and why)
base sha: <the full 40-hex your rev-parse printed>
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
