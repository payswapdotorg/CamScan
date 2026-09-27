You are Worker 2 (Document Workspace & Export track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-005 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

=== CAMSCAN-PROD-005 — PRODUCT SHELL / NAVIGATION / HOME / LIBRARY (Worker 2) ===

# 1. ROLE

You are Worker 2 on a permanent three-worker product program building **CamScan** — an
independent, clean-room Android document-scanning application (applicationId
`org.payswap.camscan`). The observable behavior oracle is the public CamScanner app; the
reference app is NEVER an implementation dependency (no proprietary code/assets/internals).

You own the **document workspace surface**: product shell, navigation, home and library.
You are not the architect: the shared contracts in §4 are frozen and lead-owned; implement
inside your ownership boundary and report any needed contract change in your final report
instead of making it yourself.

# 2. PROGRAM CONTEXT

- Repository: https://github.com/payswapdotorg/CamScan (public, anonymous clone works).
  After cloning, READ these files in your clone for the full program frame:
  - `docs/PRODUCT-ARCHITECTURE-LOCK.md` (architecture authority — esp. §12 UI surfaces)
  - `lab/orchestration/PRODUCT-WORK-BOARD.yaml` (your path ownership)
- CAMSCAN-PROD-005 is the **product shell** of the P0 scanner vertical slice: app
  navigation, home/library screens, document list surfaces. Document/page persistence and
  the viewer arrive in PROD-006 (a later session) — for now the library runs on an
  in-memory repository implementing the lead's `DocumentRepository` contract. PDF/JPG
  export is PROD-007. The scan surface itself is Worker 1's PROD-001 — you integrate a
  placeholder launcher seam now; the lead wires the real scan screen at integration.
- Priority rule: P0 "scan a document" outranks everything. The shell's job is to carry the
  user into the scan flow and back out to the library.
- Offline-first: no network, no account, no cloud anywhere in your surface.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git checkout 9a674bc761786c9b4b97058b454e879b091d9c93
git checkout -b work/CAMSCAN-PROD-005
git config user.name "CamScan Worker 2"
git config user.email "worker2@camscan.invalid"
git rev-parse HEAD   # MUST print 9a674bc761786c9b4b97058b454e879b091d9c93
```

Your sandbox very likely has NO Android SDK and no Gradle cache. That is expected and
acceptable — the Tech Lead re-runs the full Gradle gate (build + tests + lint) at the
integration station on the lab substrate and NEVER trusts reported numbers. Your duty is
correct, complete, disciplined code plus honest reporting. See §7 for the optional
best-effort toolchain attempt and the honesty rules.

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

For this work order you additionally own the **app shell root** (this is your slice —
the other two workers are forbidden from touching it):

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/MainActivity.kt` (rewrite as the shell host)
- `app/src/main/java/org/payswap/camscan/document/**` and `.../library/**` (your trees)
- `app/src/test/java/org/payswap/camscan/document/**`, `.../library/**` (JVM tests)
- `app/src/androidTest/java/org/payswap/camscan/**` (you own the instrumentation tree;
  Worker 1 will add capture-package files there — do not remove or block them)
- `app/src/main/res/layout/activity_main.xml`, `fragment_home.xml`, `fragment_library.xml`,
  `fragment_document_stub.xml`, `app/src/main/res/values/strings_workspace.xml`
  (your resources ONLY — file names are worker-disjoint by design)
- the §4 contract files (byte-exact as given)

You MUST NOT touch:
- any file under `capture/`, `processing/`, `core/image/` (Worker 1's surface)
- `res/layout/fragment_scan.xml`, `res/values/strings_capture.xml` (Worker 1's)
- `res/values/strings.xml` (frozen: app_name only), any file under `ocr/`, `tools/`,
  `search/`, `export/`, `settings/`, or anything outside `app/`

# 6. TASK — PRODUCT SHELL / NAVIGATION / HOME / LIBRARY

Implement the app shell exactly as the architecture lock §12 describes the primary
surfaces (Home/Library with New Scan + recent documents; the Scan surface is Worker 1's;
the Page Editor and Document Viewer arrive with later work orders — stub their entry
points):

1. **MainActivity** — single-activity shell (AppCompat, Theme.CamScan):
   - hosts a single fragment container (`R.id.app_fragment_container`);
   - implements the lead's `core.navigation.ScanHost` (fragmentManager + containerViewId +
     `onScanFinished(documentId)` — when a real scan later produces a document id, the
     shell opens the document detail; for now it returns to Home and refreshes the list);
   - starts `HomeFragment` as the root (no back-stack entry above it);
   - constructs the in-memory `DocumentRepository` and hands it to fragments via
     a plain constructor/factory (no DI framework — keep it minimal and testable).
2. **HomeFragment** (`library/` tree, `fragment_home.xml`):
   - top app bar with the app name (CamScan);
   - primary **New Scan** button (id `home_new_scan_button`) invoking the
     `ScanLauncher` seam;
   - **recent documents** list (RecyclerView, id `home_recent_list`) — top 10 by
     `updatedAtMillis` desc, each row showing title + page count + relative date
     (row title id `document_row_title`);
   - **Library** navigation affordance (id `home_open_library`);
   - empty state (id `home_empty_state`) with a clear "no documents yet — scan your
     first document" message.
3. **LibraryFragment** (`document/` tree, `fragment_library.xml`):
   - full document list (RecyclerView id `library_documents_list`, same row layout);
   - empty state (id `library_empty_state`);
   - back/up behavior returns to Home.
4. **DocumentDetailStubFragment** (`document/` tree, `fragment_document_stub.xml`):
   - shows the document title (id `document_detail_title`), page count, and an honest
     "document viewer arrives in the next work order" notice (id
     `document_detail_stub_notice`); delete affordance (id `document_detail_delete`)
     wired to the repository (with an undo Snackbar — offline-first, non-destructive
     spirit; hard delete on confirmation is acceptable for the stub).
5. **PlaceholderScanLauncher** (`document/` tree) — implements `ScanLauncher` by opening
   a `PlaceholderScanFragment` (id-stamped notice "scan engine integrating — Worker 1's
   capture surface attaches here", id `placeholder_scan_notice`) into the host container.
   The lead swaps in Worker 1's real launcher at integration with a one-line wiring change.
6. **InMemoryDocumentRepository** (`document/` tree) — implements the lead's
   `DocumentRepository` contract over a `MutableMap`, `MutableStateFlow`-backed
   `observeDocuments()` (most recently updated first), full CRUD semantics, and
   deterministic behavior given a fixed `TimeSource`. NO demo/seed data — the app starts
   honestly empty.
7. **JVM unit tests** (`app/src/test/java/org/payswap/camscan/document|library/`):
   - `InMemoryDocumentRepositoryTest`: observe emissions (empty → doc added → updated →
     deleted), ordering by updatedAtMillis desc, upsert replaces pages atomically,
     getPages index ordering, unknown-id behaviors;
   - a `ScanHost`/`ScanLauncher` contract fake test proving the shell seam types behave
     (placeholder launcher returns true; host callbacks fire).
   Pure JUnit 4 + kotlinx-coroutines-test; no Android imports — they MUST compile and run
   under `./gradlew :app:testDebugUnitTest` at the integration station.
8. **Instrumentation test** (`app/src/androidTest/.../workspace/MainActivityNavigationTest.kt`):
   launch MainActivity → assert Home renders (new-scan button + empty state) → open
   Library → assert list screen → back → Home. Marked with a clear comment that it runs
   at the lead's integration station (lab AVD), not in your sandbox.

**Stable semantic ids (exact, ADB-parity tests depend on them):**
`app_fragment_container`, `home_new_scan_button`, `home_recent_list`, `home_open_library`,
`home_empty_state`, `library_documents_list`, `library_empty_state`, `document_row_title`,
`document_detail_title`, `document_detail_stub_notice`, `document_detail_delete`,
`placeholder_scan_notice`.
Every interactive view also gets a `contentDescription` from `strings_workspace.xml`
(prefixed `workspace_`).

**Discipline:**
- Kotlin 2.0, JVM target 17, minSdk 26 — no APIs above SDK 26 without fallback.
- No new dependencies beyond §4 (report needs instead). No Compose. No DI framework. No
  multi-module. No Jetpack Navigation graph — plain FragmentManager transactions with a
  small back-stack policy you own.
- Repository/list logic stays free of `android.*` imports so it stays JVM-testable; the
  fragments stay thin.
- Deterministic where pure; time only via TimeSource (never System.currentTimeMillis()).
- String resources only via `strings_workspace.xml`.

# 7. VERIFICATION (honest, bounded)

1. Attempt, budget-boxed to ~20 minutes total: check `java -version`; if a JDK exists you
   MAY try to fetch Android cmdline-tools + `sdkmanager "platforms;android-35"
   "build-tools;35.0.0"` (accept licenses), then run `./gradlew :app:testDebugUnitTest`.
   If anything fails or the budget expires: STOP, deliver statically, and say so plainly.
2. Static self-checks (always): `git status` clean-tree discipline; `git diff --stat`
   review; re-read every Kotlin/XML file for syntax discipline (balanced braces, closed
   tags, valid resource names, imports).
3. NEVER fabricate test output. If you could not run Gradle, the report's verification
   section says exactly: "no Android toolchain in sandbox; compile/test deferred to lead
   integration" plus the static checks you did perform.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-005: product shell — navigation, home, library, in-memory repository + lead core contracts (verbatim)"
git bundle create camscan-prod-005.bundle 9a674bc761786c9b4b97058b454e879b091d9c93..work/CAMSCAN-PROD-005
cp camscan-prod-005.bundle ../camscan-prod-005.bundle
sha256sum camscan-prod-005.bundle
git diff --stat 9a674bc761786c9b4b97058b454e879b091d9c93..work/CAMSCAN-PROD-005
```

Also write `CamScan/DELIVERY-PROD-005.txt` listing every added/changed file path + the
bundle sha256. Your sandbox and its files ARE the delivery channel — the Tech Lead
harvests them directly. Do not delete your work. Do not push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-005 COMPLETION REPORT ===
task: product shell — navigation, home, library
environment: sandbox OS + toolchain actually available (state plainly if no Android SDK)
what was implemented/observed: …
verification: commands + outputs (verbatim — including failed attempts; never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-005.txt) — no raw bytes in chat
assumptions: …
open questions / handoffs: …
base sha: 9a674bc761786c9b4b97058b454e879b091d9c93
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
