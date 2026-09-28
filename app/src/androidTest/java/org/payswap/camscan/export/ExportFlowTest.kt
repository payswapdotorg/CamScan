package org.payswap.camscan.export

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

/**
 *
 * Export flow instrumentation (CAMSCAN-PROD-007 §6.7) — lead-station-only:
 * this sandbox has no emulator, so this skeleton is compile-verified here
 * (assembled into the androidTest APK) and executed at the Tech Lead's
 * integration station, exactly like the PROD-006 viewer navigation tests.
 *
 * Seeds the app's real private content directory with a two-page document
 * whose processed images are real PNG bytes (Bitmap.compress), so the
 * export path exercises the real PageJpegEncoder decode + JPEG re-encode,
 * PdfWriter, ContentStore put, and the viewer's export row.
 */
@RunWith(AndroidJUnit4::class)
class ExportFlowTest {

    private lateinit var contentDir: File

    @Before
    fun seedDocument() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        contentDir = File(context.filesDir, "content")
        contentDir.deleteRecursively()
        contentDir.mkdirs()

        val store = FileContentStore(contentDir)
        runBlocking {
            val ref0 = store.put("seed-export-p0", pngBytes(width = 8, height = 6))
            val ref1 = store.put("seed-export-p1", pngBytes(width = 6, height = 8))

            val repository = PersistentDocumentRepository(
                contentDir = contentDir,
                timeSource = TimeSource.SYSTEM,
                idGenerator = IdGenerator { "seed-export-id-${System.nanoTime()}" },
            )
            repository.upsertDocument(
                Document(
                    id = "seed-export-doc",
                    title = "Export Doc",
                    createdAtMillis = 1_000L,
                    updatedAtMillis = 1_000L,
                ),
                listOf(
                    Page(
                        id = "seed-export-p0",
                        documentId = "seed-export-doc",
                        index = 0,
                        processedImageRef = ref0,
                        createdAtMillis = 1_000L,
                        updatedAtMillis = 1_000L,
                    ),
                    Page(
                        id = "seed-export-p1",
                        documentId = "seed-export-doc",
                        index = 1,
                        processedImageRef = ref1,
                        rotationDegrees = 90,
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
    fun viewerShowsExportButtons_withExactStableIds() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withText("Export Doc")).perform(click())

        onView(withId(R.id.document_export_pdf_button)).check(matches(isDisplayed()))
        onView(withId(R.id.document_export_jpg_button)).check(matches(isDisplayed()))
        onView(withId(R.id.document_share_button)).check(matches(isDisplayed()))
    }

    @Test
    fun exportPdfButton_producesSuccessSnackbar() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withText("Export Doc")).perform(click())
        onView(withId(R.id.document_export_pdf_button)).perform(click())

        // Honest success state: "Exported Export Doc.pdf (<size>)".
        onView(withText(startsWith("Exported"))).check(matches(isDisplayed()))
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
