You are Worker 3 (OCR, Tools & Verification track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-009 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

> **PACKET REVISION 2** (2026-09-27, lead): rebased onto the lead-owned
> PRODUCT-FOUNDATION commit `69ee49256ca647c3a1f0ec5d8689204f07624f9a`. Two changes from revision 1 (as
> originally dispatched): (a) the §3 branch base is `69ee49256ca6…`, not
> `9a674bc…`; (b) the §4 contract layer is ALREADY on your base — consume it
> read-only, never recreate or modify it. Revision-1 text is preserved in git
> history (34f7d2c) and in the live session transcript. Everything else is
> unchanged.

=== CAMSCAN-PROD-009 — OCR ENGINE ADAPTER + RESULT MODEL (Worker 3) ===

# 1. ROLE

You are Worker 3 on a permanent three-worker product program building **CamScan** — an
independent, clean-room Android document-scanning application (applicationId
`org.payswap.camscan`). The observable behavior oracle is the public CamScanner app; the
reference app is NEVER an implementation dependency (no proprietary code/assets/internals).

You own the **OCR / tools / verification surface**. You are not the architect: the shared
contracts in §4 are frozen and lead-owned; implement inside your ownership boundary and
report any needed contract change in your final report instead of making it yourself.

# 2. PROGRAM CONTEXT

- Repository: https://github.com/payswapdotorg/CamScan (public, anonymous clone works).
  After cloning, READ these files in your clone for the full program frame:
  - `docs/PRODUCT-ARCHITECTURE-LOCK.md` (architecture authority — esp. §11 OCR)
  - `docs/SCAN-ENGINE-CONTRACT.md` (the scanner capability contract)
  - `lab/orchestration/PRODUCT-WORK-BOARD.yaml` (your path ownership)
- CAMSCAN-PROD-009 is the **OCR foundation** of the program: a stable OCR engine adapter
  interface + result model + a deterministic OCR test harness. The architecture lock
  mandates: OCR is an adapter behind a stable interface; an on-device engine is preferred
  for the first real implementation; **OCR never blocks the primary scan path** (scan/save
  works without OCR; OCR/indexing runs after save).
- The REAL on-device engine binding (ML Kit / Tesseract-family / other on-device options)
  is a LATER decision informed by your analysis — do NOT integrate any heavy engine now.
  This work order ships: the seam, the model, the harness, one deterministic stub engine,
  and an engine-selection analysis in the report.
- Worker ownership note: `lab/reconciliation/**` and `tools/parity-cli/**` are also yours
  in the wider program, but this work order touches ONLY the app OCR surface. Reference
  OCR behavior evidence will be produced by the Tech Lead's reference runs later; your
  harness must be ready to consume it.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git checkout 69ee49256ca647c3a1f0ec5d8689204f07624f9a
git checkout -b work/CAMSCAN-PROD-009
git config user.name "CamScan Worker 3"
git config user.email "worker3@camscan.invalid"
git rev-parse HEAD   # MUST print 69ee49256ca647c3a1f0ec5d8689204f07624f9a
```

Your sandbox very likely has NO Android SDK and no Gradle cache. That is expected and
acceptable — the Tech Lead re-runs the full Gradle gate (build + tests + lint) at the
integration station on the lab substrate and NEVER trusts reported numbers. Your duty is
correct, complete, disciplined code plus honest reporting. See §7 for the optional
best-effort toolchain attempt and the honesty rules. (Your pure-Kotlin logic and JVM
tests are the most compile-verifiable of the three tracks — if any Kotlin compiler is
obtainable in-sandbox, use it on the pure files, and report exactly what you ran.)

# 4. SHARED CONTRACT LAYER (lead-owned, frozen — already on your base commit)

The wave-0 contracts are NOT something you create. They are landed on `main` as
the single lead-owned PRODUCT-FOUNDATION commit
`69ee49256ca647c3a1f0ec5d8689204f07624f9a`, which is your branch base. All
three concurrent workers build on the exact same bytes — that is why nobody
may modify them. Read them in your clone before writing code; your task
section references their types:

- `gradle/libs.versions.toml` — version catalog (CameraX 1.4.1 set, lifecycle,
  fragment, recyclerview, coroutines, JVM + instrumentation test deps)
- `app/build.gradle.kts` — module build (SDK levels, dependency wiring,
  AndroidJUnitRunner)
- `app/src/main/AndroidManifest.xml` — CAMERA permission + optional camera
  feature (required=false)
- `app/src/main/java/org/payswap/camscan/core/model/Documents.kt` — Document /
  Page domain model (Corner, cropQuad, enhancement modes, content refs)
- `app/src/main/java/org/payswap/camscan/core/repository/DocumentRepository.kt`
  — domain persistence contract (Flow-based observe, upsert, delete)
- `app/src/main/java/org/payswap/camscan/core/storage/ContentStore.kt` —
  opaque-ref binary asset contract (put/open/delete/exists)
- `app/src/main/java/org/payswap/camscan/core/time/TimeSource.kt` —
  deterministic time seam (SYSTEM default; inject fakes in tests)
- `app/src/main/java/org/payswap/camscan/core/navigation/ScanEntry.kt` —
  shell ⇄ scan-engine seam (ScanHost / ScanLauncher)

Do not modify any of them. Do not add sibling files under `core/` — if you need
a core change, report it as an open question in your final report and implement
nothing core-side.

# 5. YOUR OWNERSHIP BOUNDARY (binding)

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/ocr/**` (your tree)
- `app/src/test/java/org/payswap/camscan/ocr/**` (your JVM tests — this is your main
  verification surface)
- nothing in the §4 contract layer — it is already on your base commit and is read-only

You MUST NOT touch:
- any file under `capture/`, `processing/`, `core/image/`, `document/`, `library/`,
  `export/`, `settings/`, `search/`, `tools/` (other workers' surfaces — search/indexing
  is your PROD-010, not this order)
- `MainActivity.kt`, any res file, `app/src/androidTest/**` (Worker 2 owns the
  instrumentation tree), or anything outside `app/`

# 6. TASK — OCR ENGINE ADAPTER + RESULT MODEL

Implement, in `app/src/main/java/org/payswap/camscan/ocr/` (pure Kotlin, no UI, no
Android imports — this entire work order must stay JVM-clean):

1. **`engine/OcrEngine.kt`** — the stable seam (the interface real engines adapt to):
   ```kotlin
   interface OcrEngine {
       val engineId: String
       suspend fun recognize(image: OcrImage, settings: OcrSettings): OcrOutcome
       fun close()
   }
   ```
   - `OcrImage`: page image input — `bytes: ByteArray`, `width: Int`, `height: Int`,
     `format: OcrImageFormat` (PNG/JPEG/UNKNOWN), plus an optional `rotationDegrees: Int`.
     Provide `OcrImage.normalized()` conveniences as you see fit.
   - `OcrSettings`: `languageHints: List<String>` (BCP-47), `mode: OcrMode`
     (FAST/BALANCED/ACCURATE) — defaults sensible (empty hints = auto, BALANCED).
   - `OcrOutcome`: sealed — `Success(result: OcrResult)` | `Failure(reason: OcrFailure)`
     with `OcrFailure` a sealed taxonomy (UNSUPPORTED_FORMAT, EMPTY_IMAGE, CORRUPT_IMAGE,
     ENGINE_ERROR(message), CLOSED). Failures are values, not exceptions, at the seam.
2. **`engine/OcrResult.kt`** — the result model (what persists and indexes):
   - `resultId: String`, `pageId: String?` (ties to `core.model.Page.ocrResultId`),
   - `blocks: List<OcrTextBlock>` — each `text: String`, `confidence: Float (0..1)`,
     `box: OcrBox` (normalized l/t/r/b in 0..1 page space), `blockIndex: Int` in reading
     order;
   - `fullText: String` (blocks joined deterministically), `engineId: String`,
     `settingsEcho: OcrSettings`, `recognisedAtMillis: Long` (via injected TimeSource —
     the RESULT CARRIES the timestamp, the engine reads time through a TimeSource
     constructor parameter, never directly), `processingDurationMillis: Long`;
   - `meanConfidence: Float` computed;
   - a `toStableString()` serialization (stable, deterministic, human-diffable — plain
     key=value/text lines; NO reflection, NO kotlin-serialization dependency).
3. **`engine/DeterministicStubEngine.kt`** — the harness engine:
   - `engineId = "stub-deterministic"`;
   - output is a PURE function of (image bytes, width, height, format, rotation,
     settings): derive a stable digest (e.g. SHA-256 over the inputs) and generate a
     fixed number of blocks whose text/boxes/confidences are derived from that digest;
   - **documented loudly as NOT an OCR implementation** — a plumbing/determinism fixture
     only (KDoc: "not real text recognition; never ship as a user-facing engine");
   - degenerate inputs (empty bytes, non-positive dimensions) → the correct `Failure`
     values, never exceptions.
4. **`engine/UnavailableEngine.kt`** — the "OCR never blocks the scan path" embodiment:
   `engineId = "unavailable"`; every recognize → `Failure(ENGINE_ERROR("ocr unavailable"))`.
   The app can run with this wired in and scanning stays fully functional.
5. **`harness/OcrHarness.kt`** — the deterministic test harness (your acceptance
   artifact): a reusable Kotlin component (not a @Test itself) that, given an
   `OcrEngine`, a list of synthetic `OcrImage`s, fixed settings, and a TimeSource:
   - runs recognition over all inputs;
   - asserts determinism: two full runs produce byte-identical `toStableString()`
     sequences;
   - captures per-input outcome type, processing duration, and a run manifest
     (`OcrHarnessReport` — plain data + `toStableString()`);
   - is engine-agnostic (works unchanged against the stub today, the real engine later,
     and reference-evidence comparison data in the future);
   - generates its synthetic images deterministically in-code (repeating byte patterns
     of controlled sizes — no binary fixtures needed in git).
6. **JVM unit tests** (`app/src/test/java/org/payswap/camscan/ocr/`):
   - `DeterministicStubEngineTest`: determinism (same input twice → identical stable
     strings), digest-sensitivity (one-byte change → different output), degenerate-input
     failures mapped to the right reasons, block ordering/normalized-box invariants;
   - `OcrResultTest`: fullText join order, meanConfidence math, toStableString
     round-trip stability (stable string of the same instance twice is identical; parse
     back if you ship a parser);
   - `UnavailableEngineTest`: always the documented failure, never throws;
   - `OcrHarnessTest`: two harness runs byte-identical end-to-end; report manifest
     completeness; TimeSource-injected timestamps.
   Pure JUnit 4 (+ kotlinx-coroutines-test where suspend is exercised); NO Android
   imports — must compile and run under `./gradlew :app:testDebugUnitTest` at the
   integration station.
7. **Engine-selection analysis** (report section, not code): a SHORT comparison of
   on-device OCR options for the first real engine — ML Kit on-device text recognition,
   Tesseract-family engines (e.g. tesseract4android), and other independently
   implementable on-device options — covering: on-device/offline operation, APK-size
   impact, model licensing/redistribution constraints, API-level coverage (minSdk 26+),
   determinism friendliness for the parity harness, and integration fit with your
   `OcrEngine` seam. Recommendation + runner-up. This analysis informs a later work
   order; it does not gate this one.

**Discipline:**
- Kotlin 2.0, JVM target 17. NO Android imports anywhere in your tree (JVM-clean).
- No new dependencies beyond §4 (report needs instead — no kotlin-serialization, no
  bouncycastle; JDK MessageDigest is your SHA-256).
- Determinism is the core value of this order: same inputs → byte-identical outputs.
- Time only via the injected TimeSource.

# 7. VERIFICATION (honest, bounded)

1. Your tree is pure Kotlin: if ANY Kotlin compiler is obtainable in-sandbox (check for
   `kotlinc`; a standalone kotlin-compiler zip from GitHub releases may be fetched if
   network/disk allow — budget-box this to ~15 minutes), compile your main+test files
   against the coroutines jars (fetch from Maven Central if feasible) and run the JUnit
   tests; report the exact commands and outputs. If toolchain assembly fails: deliver
   statically and say so plainly.
2. Static self-checks (always): `git status` clean-tree discipline; `git diff --stat`
   review; re-read every file for syntax discipline and determinism discipline.
3. NEVER fabricate test output. If you could not compile, the report's verification
   section says exactly: "no Kotlin toolchain assembled in sandbox; compile/test deferred
   to lead integration" plus the static checks you did perform.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-009: OCR engine adapter, result model, deterministic stub + harness on the lead PRODUCT-FOUNDATION base"
git bundle create camscan-prod-009.bundle 69ee49256ca647c3a1f0ec5d8689204f07624f9a..work/CAMSCAN-PROD-009
cp camscan-prod-009.bundle ../camscan-prod-009.bundle
sha256sum camscan-prod-009.bundle
git diff --stat 69ee49256ca647c3a1f0ec5d8689204f07624f9a..work/CAMSCAN-PROD-009
```

Also write `CamScan/DELIVERY-PROD-009.txt` listing every added/changed file path + the
bundle sha256. Your sandbox and its files ARE the delivery channel — the Tech Lead
harvests them directly. Do not delete your work. Do not push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-009 COMPLETION REPORT ===
task: OCR engine adapter + OCR result model (+ deterministic harness)
environment: sandbox OS + toolchain actually available (state plainly if no Kotlin/Android toolchain)
what was implemented/observed: …
verification: commands + outputs (verbatim — including failed attempts; never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-009.txt) — no raw bytes in chat
assumptions: …
open questions / handoffs: … (include the engine-selection analysis + recommendation here)
base sha: 69ee49256ca647c3a1f0ec5d8689204f07624f9a
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
