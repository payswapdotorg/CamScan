package org.payswap.camscan.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the export presentation helpers (CAMSCAN-PROD-007 §6.6):
 * deterministic, Locale-free byte-size formatting used by the viewer's
 * success snackbars.
 */
class ExportArtifactTest {

    @Test
    fun bytesBelowOneKib_formatPlain() {
        assertEquals("0 B", formatSizeBytes(0))
        assertEquals("1 B", formatSizeBytes(1))
        assertEquals("512 B", formatSizeBytes(512))
        assertEquals("1023 B", formatSizeBytes(1023))
    }

    @Test
    fun kibRange_formatsOneDecimalKb() {
        assertEquals("1.0 KB", formatSizeBytes(1024))
        assertEquals("1.2 KB", formatSizeBytes(1258))
        assertEquals("1.3 KB", formatSizeBytes(1308))
        assertEquals("1024.0 KB", formatSizeBytes(1024L * 1024 - 1))
    }

    @Test
    fun mibRange_formatsOneDecimalMb() {
        assertEquals("1.0 MB", formatSizeBytes(1024L * 1024))
        assertEquals("2.4 MB", formatSizeBytes(2_500_000))
        assertEquals("76.3 MB", formatSizeBytes(80_000_000))
    }

    @Test
    fun negativeInput_formatsAsZero() {
        assertEquals("0 B", formatSizeBytes(-5))
    }

    @Test
    fun mimeConstants_matchPlatformConventions() {
        assertEquals("application/pdf", ExportArtifact.MIME_PDF)
        assertEquals("image/jpeg", ExportArtifact.MIME_JPEG)
    }
}
