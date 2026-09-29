package org.payswap.camscan.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [ImportIntents] pick-spec seam (CAMSCAN-PROD-008
 * §6.1 — the PROD-007 ShareIntents pattern: android-free construction,
 * platform launchers materialize from it).
 */
class ImportIntentsTest {

    @Test
    fun openDocumentPick_carriesActionMimesAndSinglePick() {
        val spec = ImportIntents.openDocumentPick()

        assertEquals(ImportIntents.ACTION_OPEN_DOCUMENT, spec.action)
        assertEquals(
            listOf(ImportIntents.MIME_IMAGE_WILDCARD, ImportIntents.MIME_APPLICATION_PDF),
            spec.mimeTypes,
        )
        assertFalse("imports are single-pick", spec.allowMultiple)
    }

    @Test
    fun openDocumentAction_matchesThePlatformConstant() {
        // Spelled-out constant pinned to the platform value.
        assertEquals("android.intent.action.OPEN_DOCUMENT", ImportIntents.ACTION_OPEN_DOCUMENT)
        assertEquals("android.intent.action.GET_CONTENT", ImportIntents.ACTION_GET_CONTENT)
    }

    @Test
    fun getContentFallback_usesWildcardSingleMime() {
        val spec = ImportIntents.getContentFallbackPick()

        assertEquals(ImportIntents.ACTION_GET_CONTENT, spec.action)
        assertEquals(listOf(ImportIntents.GET_CONTENT_FALLBACK_MIME), spec.mimeTypes)
        assertEquals("*/*", ImportIntents.GET_CONTENT_FALLBACK_MIME)
        assertFalse(spec.allowMultiple)
    }

    @Test
    fun importMimeTypes_areExactlyImagesAndPdfs() {
        assertEquals(listOf("image/*", "application/pdf"), ImportIntents.IMPORT_MIME_TYPES)
        assertTrue(ImportIntents.IMPORT_MIME_TYPES.containsAll(listOf("image/*", "application/pdf")))
    }
}
