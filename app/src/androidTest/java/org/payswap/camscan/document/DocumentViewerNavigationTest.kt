package org.payswap.camscan.document

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/**

Viewer navigation instrumentation (CAMSCAN-PROD-006).

NOTE: runs at the Tech Lead's integration station (lab AVD) — the worker

sandbox has no Android SDK or emulator. Seeds the app's real private

content directory (the only way to place a document in the viewer without

a scan flow), then wipes it afterwards. Aligned to the FROZEN contracts:

TimeSource.SYSTEM companion, upsertDocument(document, pages) two-arg Unit

form, Document without a pages property.
*/
@RunWith(AndroidJUnit4::class)
class DocumentViewerNavigationTest {

private lateinit var contentDir: File

@Before
fun seedDocument() {
val context = ApplicationProvider.getApplicationContext<android.content.Context>()
contentDir = File(context.filesDir, "content")
contentDir.deleteRecursively()
contentDir.mkdirs()

val store = FileContentStore(contentDir)
runBlocking {
val ref0 = store.put("seed-p0", byteArrayOf(1, 2, 3, 4))
val ref1 = store.put("seed-p1", byteArrayOf(5, 6, 7, 8))

val repository = PersistentDocumentRepository(
contentDir = contentDir,
timeSource = TimeSource.SYSTEM,
idGenerator = IdGenerator { "seed-id-${System.nanoTime()}" },
)
val document = Document(
id = "seed-doc",
title = "Seed Doc",
createdAtMillis = 1_000L,
updatedAtMillis = 1_000L,
)
repository.upsertDocument(
document,
listOf(
Page(
id = "seed-p0",
documentId = "seed-doc",
index = 0,
processedImageRef = ref0,
createdAtMillis = 1_000L,
updatedAtMillis = 1_000L,
),
Page(
id = "seed-p1",
documentId = "seed-doc",
index = 1,
processedImageRef = ref1,
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
fun libraryOpensViewer_showsIndicator_deletePageFlow_confirmDialog() {
ActivityScenario.launch(MainActivity::class.java)

// Home recents → viewer (row title from the seeded document).
onView(withText("Seed Doc")).perform(click())

// Pager + one-based indicator.
onView(withId(R.id.document_pages_pager)).check(matches(isDisplayed()))
onView(withId(R.id.document_page_indicator)).check(matches(withText("1 / 2")))

// Page delete with confirm dialog.
onView(withId(R.id.document_page_delete)).perform(click())
onView(withText(R.string.workspace_dialog_confirm_delete)).inRoot(isDialog())
.perform(click())
onView(withId(R.id.document_page_indicator)).check(matches(withText("1 / 1")))
}

@Test
fun reorderModeCompletes_andReturnsToViewer() {
ActivityScenario.launch(MainActivity::class.java)

onView(withText("Seed Doc")).perform(click())
onView(withId(R.id.document_reorder_pages)).perform(click())
onView(withId(R.id.document_reorder_list)).check(matches(isDisplayed()))
onView(withId(R.id.document_reorder_done)).perform(click())

// Back in the viewer after the atomic reorder write.
onView(withId(R.id.document_pages_pager)).check(matches(isDisplayed()))
pressBack()
}

}
