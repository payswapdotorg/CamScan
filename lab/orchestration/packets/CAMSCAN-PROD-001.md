You are Worker 1 (Capture & Scan Engine track) for CamScan, dispatched by the Tech Lead through the agents console. Work autonomously until CAMSCAN-PROD-001 is implemented, tested, and fully reported. This packet is fully self-contained — you cannot see the Tech Lead's context, and no other worker can see yours.

=== CAMSCAN-PROD-001 — CAPTURE FOUNDATION (Worker 1) ===

# 1. ROLE

You are Worker 1 on a permanent three-worker product program building **CamScan** — an
independent, clean-room Android document-scanning application (applicationId
`org.payswap.camscan`). The observable behavior oracle is the public CamScanner app; the
reference app is NEVER an implementation dependency (no proprietary code/assets/internals).

You own the **capture & scan-engine surface**. You are not the architect: the shared
contracts in §4 are frozen and lead-owned; implement inside your ownership boundary and
report any needed contract change in your final report instead of making it yourself.

# 2. PROGRAM CONTEXT

- Repository: https://github.com/payswapdotorg/CamScan (public, anonymous clone works).
  After cloning, READ these files in your clone for the full program frame:
  - `docs/PRODUCT-ARCHITECTURE-LOCK.md` (architecture authority)
  - `docs/SCAN-ENGINE-CONTRACT.md` (the scanner capability contract)
  - `lab/orchestration/PRODUCT-WORK-BOARD.yaml` (your path ownership)
- CAMSCAN-PROD-001 is the **capture foundation** of the P0 scanner vertical slice:
  CameraX preview, camera permission flow, capture lifecycle, stable camera state.
  Document detection, quad stabilization, geometry/perspective, enhancement and the scan
  session arrive in LATER work orders (PROD-002/003/004) owned by other sessions — do NOT
  implement them now, but keep your seams clean so they can plug in.
- Priority rule: P0 "scan a document" outranks everything. Do not build P1/P2/P3 features.
- Offline-first: no network in the scan path. No account. No cloud.

# 3. ENVIRONMENT SETUP (run exactly, in order)

```bash
git clone https://github.com/payswapdotorg/CamScan.git CamScan
cd CamScan
git checkout 9a674bc761786c9b4b97058b454e879b091d9c93
git checkout -b work/CAMSCAN-PROD-001
git config user.name "CamScan Worker 1"
git config user.email "worker1@camscan.invalid"
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

You MAY create/modify:
- `app/src/main/java/org/payswap/camscan/capture/**` (your tree)
- `app/src/test/java/org/payswap/camscan/capture/**` (your JVM tests)
- `app/src/androidTest/java/org/payswap/camscan/capture/**` (your instrumentation skeletons)
- `app/src/main/res/layout/fragment_scan.xml`, `app/src/main/res/values/strings_capture.xml`
  (your resources ONLY — file names are worker-disjoint by design)
- the §4 contract files (byte-exact as given)

You MUST NOT touch:
- `MainActivity.kt`, `activity_main.xml`, `res/values/strings.xml`, any other res file,
  or any file under `document/`, `library/`, `export/`, `settings/`, `ocr/`, `tools/`,
  `search/` (other workers' surfaces / the shell)
- anything outside `app/` (lab/, tools/, docs/ are lead-owned)

# 6. TASK — CAPTURE FOUNDATION

Implement, in `app/src/main/java/org/payswap/camscan/capture/camera/` (plus your res
files and tests):

1. **CameraPermissionGate** — permission state machine, pure Kotlin:
   states `NOT_REQUESTED, REQUESTED, GRANTED, DENIED_SOFT, DENIED_PERMANENT`;
   events `onPermissionResult(granted: Boolean)`, plus a rationale probe injected as
   `fun interface RationaleChecker { fun shouldShowRationale(): Boolean }` so the machine
   is JVM-testable. `DENIED_PERMANENT` is reached only when denied AND rationale is false.
   Include `val canRequest: Boolean` semantics (re-request allowed from NOT_REQUESTED and
   DENIED_SOFT only).
2. **CameraStateMachine** — capture lifecycle state machine, pure Kotlin:
   states `IDLE, OPENING, READY, CAPTURING, ERROR, CLOSED`; events `bindStarted()`,
   `cameraReady()`, `captureStarted()`, `captureComplete()`, `captureFailed()`,
   `cameraError(recoverable: Boolean)`, `close()`. Legal transitions only; an illegal
   event is rejected (returns false and records a diagnostic string) — never throws.
   A recoverable camera error returns to OPENING (rebind); a permanent one goes to ERROR
   (terminal until close()).
3. **StableCameraState** — immutable camera settings + reducer, pure Kotlin:
   fields `lensFacing (BACK|FRONT)`, `flash (AUTO|ON|OFF)`, `available: Boolean`,
   plus an event reducer: `ToggleFlash`, `SwitchLens`, `SetAvailable(Boolean)`.
   Switching to an unavailable lens is rejected. Flash AUTO is the default (matches the
   reference scanner behavior of auto-flash in dim conditions).
4. **CameraController** — the CameraX glue (thin; all decisions delegated to 1–3):
   - binds `Preview` + `ImageCapture` to a `LifecycleOwner` via
     `ProcessCameraProvider.getInstance(ctx)` (ListenableFuture listener pattern, no
     blocking gets), selector from StableCameraState.lensFacing;
   - rebinds on lens switch and on recoverable error;
   - `takeStill(target: java.io.File, callback: (CaptureOutcome) -> Unit)` where
     `CaptureOutcome` is your own sealed type (Saved(file)/Failed(reason)) with a reason
     taxonomy (IO, CAPTURE_FAILED, CAMERA_UNAVAILABLE, NOT_READY, NO_PERMISSION);
   - surface flash mode onto ImageCapture flash-mode mapping;
   - exposes camera state via a small listener interface; no Activity/Fragment imports
     beyond LifecycleOwner/Context.
5. **ScanFragment** — the live scan surface (`fragment_scan.xml`):
   - `PreviewView` (id `scan_camera_preview`) with compat mode;
   - permission overlay when not GRANTED: rationale text (id
     `scan_permission_rationale`) + request button (id `scan_permission_request_button`)
     calling `registerForActivityResult(RequestPermission())`;
   - capture button (id `scan_capture_button`, enabled only in READY);
   - flash toggle (id `scan_flash_toggle`) and camera switch (id `scan_switch_camera`);
   - graceful no-camera / permanent-error state (id `scan_unavailable_state`);
   - hosts CameraController; drives CameraStateMachine; on capture-complete keeps the
     file reference in memory (a simple `CapturedShot` value) and re-arms for the next
     capture — document persistence is NOT your concern in this work order;
   - exposes `var onCaptureResult: ((List<CapturedShot>) -> Unit)? = null` invoked when
     the user finishes (a leave-scan affordance, id `scan_done_button`) — the shell and
     session flow consume this in later work orders.
6. **CameraScanLauncher** — implements `core.navigation.ScanLauncher`: starts
   `ScanFragment` into `host.containerViewId` via the host's FragmentManager (addToBackStack
   "scan"), returns true. `ScanHost.onScanFinished` is called with null when the user exits
   the scan surface without captures (for now; the real document id arrives with the
   session work order).
7. **JVM unit tests** (`app/src/test/java/org/payswap/camscan/capture/camera/`):
   exhaustive transition tables for 1–3: every legal transition asserted, every illegal
   event rejected without throw; permission gate paths incl. permanent-denial; reducer
   defaults + rejections. These tests are pure JUnit 4, no Android classes — they MUST
   compile and run under `./gradlew :app:testDebugUnitTest` at the integration station.
8. **Instrumentation skeleton** (`app/src/androidTest/java/org/payswap/camscan/capture/camera/ScanFragmentLaunchTest.kt`):
   FragmentScenario launch of ScanFragment asserting the preview + capture button exist
   with the exact resource ids; marked with a clear comment that it runs at the lead's
   integration station (lab AVD), not in your sandbox.

**Stable semantic ids (exact, ADB-parity tests depend on them):**
`scan_camera_preview`, `scan_capture_button`, `scan_flash_toggle`,
`scan_switch_camera`, `scan_permission_request_button`, `scan_permission_rationale`,
`scan_unavailable_state`, `scan_done_button`.
Every interactive view also gets a `contentDescription` from `strings_capture.xml`
(except purely decorative ones).

**Discipline:**
- Kotlin 2.0, JVM target 17, minSdk 26 — no APIs above SDK 26 without fallback.
- No new dependencies beyond §4 (report needs instead). No Compose. No multi-module.
- Deterministic where pure: same input → same output; time only via TimeSource.
- Keep pure logic (1–3) free of `android.*` imports so it stays JVM-testable; the glue
  (4–6) stays thin.
- String resources only via `strings_capture.xml` (prefixed `scan_`).

# 7. VERIFICATION (honest, bounded)

1. Attempt, budget-boxed to ~20 minutes total: check `java -version`; if a JDK exists you
   MAY try to fetch Android cmdline-tools + `sdkmanager "platforms;android-35"
   "build-tools;35.0.0"` (accept licenses), then `./gradlew :app:testDebugUnitTest
   --offline` will not work without SDK — use the online build. If anything fails or the
   budget expires: STOP, deliver statically, and say so plainly.
2. Static self-checks (always): `git status` clean-tree discipline; `git diff --stat`
   review; re-read every Kotlin file for syntax discipline (balanced braces, imports,
   no dangling TODOs without a report note).
3. NEVER fabricate test output. If you could not run Gradle, the report's verification
   section says exactly: "no Android toolchain in sandbox; compile/test deferred to lead
   integration" plus the static checks you did perform.

# 8. DELIVERY (git, in-sandbox — you have NO push credentials)

```bash
cd CamScan
git add -A
git commit -m "CAMSCAN-PROD-001: capture foundation — CameraX preview, permission gate, capture lifecycle, stable camera state + lead core contracts (verbatim)"
git bundle create camscan-prod-001.bundle 9a674bc761786c9b4b97058b454e879b091d9c93..work/CAMSCAN-PROD-001
cp camscan-prod-001.bundle ../camscan-prod-001.bundle
sha256sum camscan-prod-001.bundle
git diff --stat 9a674bc761786c9b4b97058b454e879b091d9c93..work/CAMSCAN-PROD-001
```

Also write `CamScan/DELIVERY-PROD-001.txt` listing every added/changed file path + the
bundle sha256. Your sandbox and its files ARE the delivery channel — the Tech Lead
harvests them directly. Do not delete your work. Do not push anywhere.

# 9. FINAL REPORT (your last chat message — report ONLY, no file dumps)

```
=== CAMSCAN-PROD-001 COMPLETION REPORT ===
task: capture foundation — CameraX preview, permission, capture lifecycle, stable camera state
environment: sandbox OS + toolchain actually available (state plainly if no Android SDK)
what was implemented/observed: …
verification: commands + outputs (verbatim — including failed attempts; never invented)
evidence: artifact list + sha256 (bundle path, DELIVERY-PROD-001.txt) — no raw bytes in chat
assumptions: …
open questions / handoffs: …
base sha: 9a674bc761786c9b4b97058b454e879b091d9c93
```

Status vocabulary is binding: `implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`.
Report exactly what exists. A green self-review is not a pass.
