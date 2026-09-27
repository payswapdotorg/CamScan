You are Worker 3 (OCR, Tools & Verification track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-009 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

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
git checkout 9a674bc761786c9b4b97058b454e879b091d9c93
git checkout -b work/CAMSCAN-PROD-009
git config user.name "CamScan Worker 3"
git config user.email "worker3@camscan.invalid"
git rev-parse HEAD   # MUST print 9a674bc761786c9b4b97058b454e879b091d9c93
```

Your sandbox very likely has NO Android SDK and no Gradle cache. That is expected and
acceptable — the Tech Lead re-runs the full Gradle gate (build + tests + lint) at the
integration station on the lab substrate and NEVER trusts reported numbers. Your duty is
correct, complete, disciplined code plus honest reporting. See §7 for the optional
best-effort toolchain attempt and the honesty rules. (Your pure-Kotlin logic and JVM
tests are the most compile-verifiable of the three tracks — if any Kotlin compiler is
obtainable in-sandbox, use it on the pure files, and report exactly what you ran.)

# 4. SHARED CONTRACT LAYER (lead-owned, frozen — create these files BYTE-EXACT)

These files are the lead-published wave-0 contracts. Create them with EXACTLY the content
below (they are identical across all three concurrent worker branches; any drift causes
integration conflicts). Do not modify them. Do not add sibling files under `core/` — if
you need a core change, report it as an open question.

## 4.1 `gradle/libs.versions.toml` (FULL REPLACEMENT of the existing file)

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
coreKtx = "1.15.0"
appcompat = "1.7.0"
material = "1.12.0"
fragmentKtx = "1.8.5"
recyclerview = "1.3.2"
lifecycle = "2.8.7"
camerax = "1.4.1"
coroutines = "1.9.0"
junit = "4.13.2"
androidxTestExt = "1.2.1"
androidxTestRunner = "1.6.2"
espresso = "3.6.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
androidx-fragment-ktx = { group = "androidx.fragment", name = "fragment-ktx", version.ref = "fragmentKtx" }
androidx-recyclerview = { group = "androidx.recyclerview", name = "recyclerview", version.ref = "recyclerview" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
androidx-camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
androidx-camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExt" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
androidx-fragment-testing = { group = "androidx.fragment", name = "fragment-testing", version.ref = "fragmentKtx" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

## 4.2 `app/build.gradle.kts` (FULL REPLACEMENT)

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}


android {
    namespace = "org.payswap.camscan"
    compileSdk = 35


    defaultConfig {
        applicationId = "org.payswap.camscan"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CAMSCAN-001 placeholder: release is signed with the debug key until a
            // real signing setup lands. AGP generates the debug keystore on demand,
            // so this works on headless CI with no committed credentials.
            signingConfig = signingConfigs.getByName("debug")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    kotlinOptions {
        jvmTarget = "17"
    }


    lint {
        abortOnError = true
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.android)


    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)


    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.fragment.testing)
}
```

## 4.3 `app/src/main/AndroidManifest.xml` (FULL REPLACEMENT)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">


    <!-- CAMSCAN-PROD-001: the scan path needs the camera. The feature is optional at
         install time (required=false) so non-camera devices still install CamScan and
         receive a graceful no-camera state. -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />


    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.CamScan">


        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>


</manifest>
```

## 4.4 `app/src/main/java/org/payswap/camscan/core/model/Documents.kt` (NEW)

```kotlin
package org.payswap.camscan.core.model


/**
 * CamScan canonical document model — lead-owned contract
 * (docs/PRODUCT-ARCHITECTURE-LOCK.md §4). Workers consume; changes require a
 * lead contract revision. All timestamps are epoch milliseconds read through
 * [org.payswap.camscan.core.time.TimeSource]; all large binaries live behind
 * ContentStore refs (opaque strings), never inline.
 */


/** Ordered corner in normalized source-image coordinates (0.0..1.0). */
data class Corner(val x: Float, val y: Float)


/** Enhancement transform applied to a page. Deterministic for fixed input+mode. */
enum class PageEnhancementMode { ORIGINAL, GRAYSCALE, BLACK_AND_WHITE, CONTRAST, SHARPEN, LOW_LIGHT }


/** How the document entered the app. */
enum class DocumentSource { SCAN, IMPORTED }


/**
 * A single scanned page. Editing is non-destructive: [sourceCaptureRef] keeps
 * the original capture available for reprocessing until explicitly deleted.
 */
data class Page(
    val id: String,
    val documentId: String,
    /** 0-based position inside the document's page order. */
    val index: Int,
    /** ContentStore ref to the original capture (non-destructive editing). */
    val sourceCaptureRef: String? = null,
    /** ContentStore ref to the current processed page image. */
    val processedImageRef: String? = null,
    /** Source-space crop quad when perspective metadata exists, else null. */
    val cropQuad: List<Corner>? = null,
    val enhancement: PageEnhancementMode = PageEnhancementMode.ORIGINAL,
    /** Right-angle rotation applied on top of the processed image. */
    val rotationDegrees: Int = 0,
    /** Optional OCR result attached to this page (indexed later by Worker 3). */
    val ocrResultId: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)


/** A durable document containing ordered pages. */
data class Document(
    val id: String,
    val title: String,
    /** Ordered page ids; pages resolve through the repository. */
    val pageIds: List<String> = emptyList(),
    val sourceType: DocumentSource = DocumentSource.SCAN,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
```

## 4.5 `app/src/main/java/org/payswap/camscan/core/repository/DocumentRepository.kt` (NEW)

```kotlin
package org.payswap.camscan.core.repository


import kotlinx.coroutines.flow.Flow
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page


/**
 * Domain persistence contract for documents and pages — lead-owned
 * (PRODUCT-ARCHITECTURE-LOCK §9). Implementations may be in-memory (tests,
 * early UI) or Room-backed (a later work order); callers depend only on this
 * interface.
 */
interface DocumentRepository {
    /** Emits the current document list (most recently updated first) on every change. */
    fun observeDocuments(): Flow<List<Document>>


    suspend fun getDocument(id: String): Document?


    /** Pages of a document in index order; empty when the document is unknown. */
    suspend fun getPages(documentId: String): List<Page>


    /** Creates or updates a document together with its full ordered page list. */
    suspend fun upsertDocument(document: Document, pages: List<Page>)


    suspend fun deleteDocument(id: String)
}
```

## 4.6 `app/src/main/java/org/payswap/camscan/core/storage/ContentStore.kt` (NEW)

```kotlin
package org.payswap.camscan.core.storage


/**
 * Contract for durable binary assets (source captures, processed page images,
 * exported artifacts) — lead-owned (PRODUCT-ARCHITECTURE-LOCK §9). Refs are
 * opaque stable strings owned by the implementation; nothing else may parse
 * their structure.
 */
interface ContentStore {
    /** Stores [bytes] under the caller-chosen logical [key]; returns the durable ref. */
    suspend fun put(key: String, bytes: ByteArray): String


    suspend fun open(ref: String): ByteArray?


    suspend fun delete(ref: String): Boolean


    suspend fun exists(ref: String): Boolean
}
```

## 4.7 `app/src/main/java/org/payswap/camscan/core/time/TimeSource.kt` (NEW)

```kotlin
package org.payswap.camscan.core.time


/**
 * Deterministic time seam (PRODUCT-ARCHITECTURE-LOCK §3, core/time). Product
 * code never calls System.currentTimeMillis() directly; it reads time through
 * an injected TimeSource so tests and evidence pipelines stay deterministic.
 * Lead-owned.
 */
fun interface TimeSource {
    fun nowMillis(): Long


    companion object {
        val SYSTEM: TimeSource = TimeSource { System.currentTimeMillis() }
    }
}
```

## 4.8 `app/src/main/java/org/payswap/camscan/core/navigation/ScanEntry.kt` (NEW)

```kotlin
package org.payswap.camscan.core.navigation


import androidx.fragment.app.FragmentManager


/**
 * Shell ⇄ scan-engine seam — lead-owned (PRODUCT-ARCHITECTURE-LOCK §12).
 *
 * Worker 2's shell implements [ScanHost] and triggers scans through
 * [ScanLauncher]; Worker 1's capture engine provides the launcher
 * implementation that opens the real scan surface. Until the capture engine
 * is integrated the shell uses its own placeholder launcher.
 */
interface ScanHost {
    val fragmentManager: FragmentManager
    val containerViewId: Int


    /** Called when a finished scan produced a document (id), or null when aborted. */
    fun onScanFinished(documentId: String?)
}


interface ScanLauncher {
    /** Opens the scan surface attached to [host]. Returns true when a scan surface opened. */
    fun startScan(host: ScanHost): Boolean
}
```

# 5. YOUR OWNERSHIP BOUNDARY (binding)

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/ocr/**` (your tree)
- `app/src/test/java/org/payswap/camscan/ocr/**` (your JVM tests — this is your main
  verification surface)
- the §4 contract files (byte-exact as given)

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
git commit -m "CAMSCAN-PROD-009: OCR engine adapter, result model, deterministic stub + harness + lead core contracts (verbatim)"
git bundle create camscan-prod-009.bundle 9a674bc761786c9b4b97058b454e879b091d9c93..work/CAMSCAN-PROD-009
cp camscan-prod-009.bundle ../camscan-prod-009.bundle
sha256sum camscan-prod-009.bundle
git diff --stat 9a674bc761786c9b4b97058b454e879b091d9c93..work/CAMSCAN-PROD-009
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
base sha: 9a674bc761786c9b4b97058b454e879b091d9c93
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
