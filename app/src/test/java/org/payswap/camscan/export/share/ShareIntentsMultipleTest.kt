package org.payswap.camscan.export.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.export.ExportArtifact

// CAMSCAN-VERIFY-002 — JVM tests of the multi-share pure seam (the
// CAMSCAN-PROD-007 share object deliberately left to a later work
// order, now wired for batch sharing): the ACTION_SEND_MULTIPLE
// constant, the pure spec construction, and the flag mapping.

class ShareIntentsMultipleTest {

    @Test
    fun actionConstant_spellsTheFrameworkAction() {
        assertEquals(Intent.ACTION_SEND_MULTIPLE, ShareIntents.ACTION_SEND_MULTIPLE)
        assertEquals("android.intent.action.SEND_MULTIPLE", ShareIntents.ACTION_SEND_MULTIPLE)
    }

    @Test
    fun buildMultipleShareSpec_carriesEveryUriAndGrantsRead() {
        val spec = ShareIntents.buildMultipleShareSpec(
            "application/pdf",
            listOf("content://x/1", "content://x/2", "content://x/3"),
        )
        assertEquals(ShareIntents.ACTION_SEND_MULTIPLE, spec.action)
        assertEquals("application/pdf", spec.mime)
        assertEquals(listOf("content://x/1", "content://x/2", "content://x/3"), spec.streamUris)
        assertTrue(spec.grantReadUriPermission)
    }

    @Test
    fun buildMultipleShareSpec_defensivelyCopiesTheUriList() {
        val mutable = mutableListOf("content://x/1")
        val spec = ShareIntents.buildMultipleShareSpec("image/png", mutable)
        mutable.add("content://x/2")
        assertEquals(listOf("content://x/1"), spec.streamUris)
    }

    @Test
    fun shareFlagsForMultiple_grantsReadWhenRequested() {
        val granted = ShareIntents.buildMultipleShareSpec("application/pdf", listOf("content://x/1"))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, ShareIntents.shareFlagsForMultiple(granted))
        val revoked = granted.copy(grantReadUriPermission = false)
        assertEquals(0, ShareIntents.shareFlagsForMultiple(revoked))
    }

    @Test
    fun wildcardMime_isTheFrameworkWildcard() {
        assertEquals("*/*", ShareIntents.MIME_WILDCARD)
        assertFalse(ShareIntents.MIME_WILDCARD == ExportArtifact.MIME_PDF)
    }
}
