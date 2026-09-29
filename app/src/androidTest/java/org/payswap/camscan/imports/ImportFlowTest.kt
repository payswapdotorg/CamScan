package org.payswap.camscan.imports

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
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

// Import flow instrumentation (CAMSCAN-PROD-008 §6.7) — lead-station-only:
// this sandbox has no emulator, so this skeleton is compile-verified here
// (assembled into the androidTest APK) and executed at the Tech Lead's
// integration station, exactly like the PROD-006/007 flow tests.
// The system picker itself (ACTION_OPEN_DOCUMENT with the ACTION_GET_CONTENT
// fallback) is platform UI outside Espresso's reach: the station probe taps
// library_import_button, picks a seeded image from the picker, and checks the
// "Imported <title>" snackbar; this skeleton pins the exact stable ids and
// the merge-selection affordances the picker flow hangs off.
@RunWith(AndroidJUnit4::class)
class ImportFlowTest {

    private lateinit var contentDir: File

    @Before
    fun seedDocument() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        contentDir = File(context.filesDir, "content")
        contentDir.deleteRecursively()
        contentDir.mkdirs()

        val store = FileContentStore(contentDir)
        runBlocking {
            val ref0 = store.put("seed-import-p0", pngBytes(width = 8, height = 6))
            val repository = PersistentDocumentRepository(
                contentDir = contentDir,
                timeSource = TimeSource.SYSTEM,
                idGenerator = IdGenerator { "seed-import-id-" + System.nanoTime() },
            )
            repository.upsertDocument(
                Document(
                    id = "seed-import-doc",
                    title = "Import Doc",
                    createdAtMillis = 1_000L,
                    updatedAtMillis = 1_000L,
                ),
                listOf(
                    Page(
                        id = "seed-import-p0",
                        documentId = "seed-import-doc",
                        index = 0,
                        processedImageRef = ref0,
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
    fun libraryShowsImportButton_withExactStableId() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withId(R.id.home_open_library)).perform(click())

        // The exact stable id exists and the affordance is tappable.
        onView(withId(R.id.library_import_button)).check(matches(isDisplayed()))
        onView(withId(R.id.library_import_button)).check(matches(isEnabled()))
    }

    @Test
    fun libraryMergeButton_opensSelectionBarWithConfirmAndCancel() {
        ActivityScenario.launch(MainActivity::class.java)

        onView(withId(R.id.home_open_library)).perform(click())
        onView(withId(R.id.library_merge_button)).perform(click())

        // Selection mode opens: confirm + cancel affordances appear; the
        // confirm stays disabled below the two-source contract (one row
        // exists, none selected).
        onView(withId(R.id.library_merge_confirm_button)).check(matches(isDisplayed()))
        onView(withId(R.id.library_merge_cancel_button)).check(matches(isDisplayed()))
        onView(withId(R.id.library_merge_confirm_button)).check(matches(isNotEnabled()))

        // Cancel exits selection mode back to the import row.
        onView(withId(R.id.library_merge_cancel_button)).perform(click())
        onView(withId(R.id.library_import_button)).check(matches(isDisplayed()))
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF669933.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
