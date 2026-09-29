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

// Compressed-export flow instrumentation (CAMSCAN-PROD-008 §6.7) —
// lead-station-only: this sandbox has no emulator, so this skeleton is
// compile-verified here (assembled into the androidTest APK) and executed at
// the Tech Lead's integration station, exactly like the PROD-007
// ExportFlowTest written on the same base. Seeds the app's real private
// content directory with a two-page document whose processed images are real
// PNG bytes (Bitmap.compress), so the compress path exercises the real
// PageJpegEncoder decode, the DownsamplePlanner/BitmapResampler seam, the
// real PdfWriter behind PdfAssembler, the ContentStore put, and the viewer's
// honest "original X -> compressed Y" snackbar from CompressionReport.
@RunWith(AndroidJUnit4::class)
class CompressExportFlowTest {

    private lateinit var contentDir: File

    @Before
    fun seedDocument() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        contentDir = File(context.filesDir, "content")
        contentDir.deleteRecursively()
        contentDir.mkdirs()

        val store = FileContentStore(contentDir)
        runBlocking {
            val ref0 = store.put("seed-compress-p0", pngBytes(width = 8, height = 6))
            val ref1 = store.put("seed-compress-p1", pngBytes(width = 6, height = 8))

            val repository = PersistentDocumentRepository(
                contentDir = contentDir,
                timeSource = TimeSource.SYSTEM,
                idGenerator = IdGenerator { "seed-compress-id-" + System.nanoTime() },
            )
            repository.upsertDocument(
                Document(
                    id = "seed-compress-doc",
                    title = "Compress Doc",
                    createdAtMillis = 1_000L,
                    updatedAtMillis = 1_000L,
                ),
                listOf(
                    Page(
                        id = "seed-compress-p0",
                        documentId = "seed-compress-doc",
                        index = 0,
                        processedImageRef = ref0,
                        createdAtMillis = 1_000L,
                        updatedAtMillis = 1_000L,
                    ),
                    Page(
                        id = "seed-compress-p1",
                        documentId = "seed-compress-doc",
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
    fun viewerShowsCompressButton_withExactStableId() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withText("Compress Doc")).perform(click())

        onView(withId(R.id.export_compress_button)).check(matches(isDisplayed()))
    }

    @Test
    fun compressButton_producesHonestOriginalToCompressedSnackbar() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withText("Compress Doc")).perform(click())
        onView(withId(R.id.export_compress_button)).perform(click())

        // Honest success state from CompressionReport: "Original X ->
        // compressed Y" — the truth even when compression grows tiny inputs.
        onView(withText(startsWith("Original"))).check(matches(isDisplayed()))
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
