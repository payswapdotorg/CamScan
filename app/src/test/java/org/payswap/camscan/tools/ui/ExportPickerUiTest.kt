package org.payswap.camscan.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.conversion.ConversionNames

// CAMSCAN-VERIFY-002 — JVM tests of the export picker's pure state:
// the format catalog (ids, extensions, honesty flags), the one-run-
// at-a-time busy guard, and the recorded outcome.

class ExportPickerUiTest {

    @Test
    fun catalog_isInDocumentedRenderOrder() {
        val ids = ExportFormatCatalog.ALL.map { it.formatId }
        assertEquals(listOf("pptx", "docx", "xlsx", "long-image-png"), ids)
    }

    @Test
    fun catalog_officeExtensionsMirrorTheConversionCatalog() {
        // Render order is picker-owned (flagship first); the extension SET
        // must mirror the conversion catalog's append-only registry.
        val officeExtensions = ExportFormatCatalog.ALL
            .filter { it !== ExportFormatCatalog.LONG_IMAGE }
            .map { it.extension }
            .sorted()
        assertEquals(ConversionNames.EXPORT_EXTENSIONS.sorted(), officeExtensions)
    }

    @Test
    fun catalog_byId_resolvesEveryOfferedFormatAndRejectsUnknown() {
        for (format in ExportFormatCatalog.ALL) {
            assertEquals(format, ExportFormatCatalog.byId(format.formatId))
        }
        assertNull(ExportFormatCatalog.byId("pdf"))
        assertNull(ExportFormatCatalog.byId(""))
    }

    @Test
    fun catalog_textLevelDeclaration_onlyOnOfficeFormats() {
        assertTrue(ExportFormatCatalog.PPTX.textLevelOnly)
        assertTrue(ExportFormatCatalog.DOCX.textLevelOnly)
        assertTrue(ExportFormatCatalog.XLSX.textLevelOnly)
        assertFalse(ExportFormatCatalog.LONG_IMAGE.textLevelOnly)
        assertFalse(ExportFormatCatalog.LONG_IMAGE.requiresRecognizedText)
        for (format in ExportFormatCatalog.ALL) {
            if (format !== ExportFormatCatalog.LONG_IMAGE) {
                assertTrue(format.requiresRecognizedText)
            }
        }
    }

    @Test
    fun busyGuard_beginRejectsSecondRunAndUnknownFormats() {
        val ui = ExportPickerUi()
        assertTrue(ui.begin("pptx"))
        assertTrue(ui.isBusy())
        assertEquals("pptx", ui.busyFormat())
        assertFalse("a second run is rejected", ui.begin("docx"))
        assertEquals("pptx", ui.busyFormat())
        ui.finish("pptx", succeeded = true)
        assertFalse(ui.isBusy())
        assertFalse("unknown ids never begin", ui.begin("txt"))
    }

    @Test
    fun busyGuard_finishOnlyClearsItsOwnFormat() {
        val ui = ExportPickerUi()
        ui.begin("docx")
        assertFalse("a different format cannot finish the run", ui.finish("pptx", true))
        assertTrue(ui.isBusy())
        assertTrue(ui.finish("docx", false))
        assertFalse(ui.isBusy())
    }

    @Test
    fun formatEnabled_tracksTheBusyState() {
        val ui = ExportPickerUi()
        assertTrue(ui.isFormatEnabled(ExportFormatCatalog.PPTX))
        ui.begin(ExportFormatCatalog.XLSX.formatId)
        assertFalse(ui.isFormatEnabled(ExportFormatCatalog.PPTX))
        assertFalse(ui.isFormatEnabled(ExportFormatCatalog.XLSX))
        ui.finish(ExportFormatCatalog.XLSX.formatId, true)
        assertTrue(ui.isFormatEnabled(ExportFormatCatalog.PPTX))
    }

    @Test
    fun outcome_survivesLaterRuns() {
        val ui = ExportPickerUi()
        ui.begin("pptx")
        ui.finish("pptx", succeeded = true)
        assertEquals("pptx", ui.lastOutcome()?.formatId)
        assertEquals(true, ui.lastOutcome()?.succeeded)
        ui.begin("docx")
        ui.finish("docx", succeeded = false)
        assertEquals("docx", ui.lastOutcome()?.formatId)
        assertEquals(false, ui.lastOutcome()?.succeeded)
    }

    @Test
    fun outcome_startsEmpty() {
        assertNull(ExportPickerUi().lastOutcome())
    }
}
