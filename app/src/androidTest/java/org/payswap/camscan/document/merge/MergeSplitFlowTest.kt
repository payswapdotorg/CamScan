package org.payswap.camscan.document.merge

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.payswap.camscan.MainActivity
import org.payswap.camscan.R
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.FileContentStore
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.document.persistence.PersistentDocumentRepository

// Merge/split flow instrumentation (CAMSCAN-PROD-008 §6.7) — lead-station-only:
// this sandbox has no emulator, so this skeleton is compile-verified here and
// executed at the Tech Lead's integration station, exactly like the
// PROD-006/007 flow tests. Seeds the app's real private content directory
// with two documents whose processed images are real PNG bytes
// (Bitmap.compress), so the merge consumes real persisted pages and the
// viewer's split mode taps a real decoded pager page.
@RunWith(AndroidJUnit4::class)
class MergeSplitFlowTest {

    private lateinit var contentDir: File

    @Before
    fun seedDocuments() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        contentDir = File(context.filesDir, "content")
        contentDir.deleteRecursively()
        contentDir.mkdirs()

        val store = FileContentStore(contentDir)
        runBlocking {
            val a0 = store.put("seed-merge-a-p0", pngBytes(width = 8, height = 6))
            val a1 = store.put("seed-merge-a-p1", pngBytes(width = 6, height = 8))
            val b0 = store.put("seed-merge-b-p0", pngBytes(width = 8, height = 6))

            val repository = PersistentDocumentRepository(
                contentDir = contentDir,
                timeSource = TimeSource.SYSTEM,
                idGenerator = IdGenerator { "seed-merge-id-" + System.nanoTime() },
            )
            repository.upsertDocument(
                Document(
                    id = "seed-merge-doc-a",
                    title = "Merge A",
                    createdAtMillis = 1_000L,
                    // Newer than Merge B so the visible list order is A, B —
                    // the order law DocumentMergeEngine documents.
                    updatedAtMillis = 3_000L,
                ),
                listOf(
                    Page(
                        id = "seed-merge-a-p0",
                        documentId = "seed-merge-doc-a",
                        index = 0,
                        processedImageRef = a0,
                        createdAtMillis = 1_000L,
                        updatedAtMillis = 1_000L,
                    ),
                    Page(
                        id = "seed-merge-a-p1",
                        documentId = "seed-merge-doc-a",
                        index = 1,
                        processedImageRef = a1,
                        createdAtMillis = 1_000L,
                        updatedAtMillis = 1_000L,
                    ),
                ),
            )
            repository.upsertDocument(
                Document(
                    id = "seed-merge-doc-b",
                    title = "Merge B",
                    createdAtMillis = 1_000L,
                    updatedAtMillis = 2_000L,
                ),
                listOf(
                    Page(
                        id = "seed-merge-b-p0",
                        documentId = "seed-merge-doc-b",
                        index = 0,
                        processedImageRef = b0,
                        createdAtMillis = 1_000L,
                        updatedAtMillis = 1_000L,
                    ),
                ),
            )
        }
    }

    @After
    fun wipeContent() {
        contentDir.deleteRecursively()
    }

    @Test
    fun viewerShowsImportMergeSplitButtons_withExactStableIds() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withText("Merge A")).perform(click())

        onView(withId(R.id.document_merge_button)).check(matches(isDisplayed()))
        onView(withId(R.id.document_append_import_button)).check(matches(isDisplayed()))
        onView(withId(R.id.document_split_button)).check(matches(isDisplayed()))
    }

    @Test
    fun libraryMerge_consumesSourcesIntoMergedDocument() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withId(R.id.home_open_library)).perform(click())

        // Enter selection mode and check both rows.
        onView(withId(R.id.library_merge_button)).perform(click())
        onView(withText("Merge A")).perform(click())
        onView(withText("Merge B")).perform(click())
        onView(withId(R.id.library_merge_confirm_button)).perform(click())

        // Honest success state: "Merged into Merge A + 1 more" (the title
        // rule: first source title + " + " + (n-1) + " more").
        onView(withText("Merged into Merge A + 1 more")).check(matches(isDisplayed()))
    }

    @Test
    fun viewerSplit_extractsSelectedPagesIntoNewDocument() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withText("Merge A")).perform(click())

        // Split mode: tap the visible page, then extract it.
        onView(withId(R.id.document_split_button)).perform(click())
        onView(withId(R.id.document_page_image)).perform(click())
        onView(withId(R.id.document_split_confirm_button)).perform(click())

        // Honest success state: "Extracted 1 pages".
        onView(withText(startsWith("Extracted"))).check(matches(isDisplayed()))
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
