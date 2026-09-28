package org.payswap.camscan.export.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.export.ExportArtifact

/**
 * JVM tests for the pure share-construction seam (CAMSCAN-PROD-007 §6.6):
 * [ShareIntents.buildShareSpec] / [ShareIntents.shareFlags] run without
 * Robolectric; only compile-time platform constants are referenced. The
 * android side (Uri materialization, chooser) is exercised by the
 * lead-station instrumentation skeleton.
 */
class ShareIntentsTest {

    @Test
    fun pdfShareSpec_isActionSendWithStreamAndReadGrant() {
        val uri = "content://${ShareIntents.EXPORT_PROVIDER_AUTHORITY}/exports/Contract.pdf"
        val spec = ShareIntents.buildShareSpec(ExportArtifact.MIME_PDF, uri)

        assertEquals(Intent.ACTION_SEND, spec.action)
        assertEquals("application/pdf", spec.mime)
        assertEquals(uri, spec.streamUri)
        assertTrue("receivers must get read permission", spec.grantReadUriPermission)
    }

    @Test
    fun jpgShareSpec_keepsJpegMimeAndPassthroughUri() {
        val uri = "content://${ShareIntents.EXPORT_PROVIDER_AUTHORITY}/exports/Receipt_p2.jpg"
        val spec = ShareIntents.buildShareSpec(ExportArtifact.MIME_JPEG, uri)

        assertEquals(Intent.ACTION_SEND, spec.action)
        assertEquals("image/jpeg", spec.mime)
        assertEquals(uri, spec.streamUri)
        assertTrue(spec.grantReadUriPermission)
    }

    @Test
    fun shareFlags_mapGrantToPlatformFlag() {
        val granted = ShareIntents.buildShareSpec(ExportArtifact.MIME_PDF, "content://x/y")
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, ShareIntents.shareFlags(granted))

        val withheld = granted.copy(grantReadUriPermission = false)
        assertEquals(0, ShareIntents.shareFlags(withheld))
        assertFalse(ShareIntents.shareFlags(withheld) != 0)
    }

    @Test
    fun providerAuthority_followsApplicationIdConvention() {
        assertEquals(
            "org.payswap.camscan" + ".export.provider",
            ShareIntents.EXPORT_PROVIDER_AUTHORITY,
        )
    }

    @Test
    fun actionSendConstant_matchesPlatformValue() {
        assertEquals("android.intent.action.SEND", ShareIntents.ACTION_SEND)
    }
}
