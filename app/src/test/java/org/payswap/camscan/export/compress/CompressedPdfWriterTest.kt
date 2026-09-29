package org.payswap.camscan.export.compress

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.export.pdf.PdfPageImage
import org.payswap.camscan.export.pdf.PdfWriter

// JVM tests for CompressedPdfWriter (CAMSCAN-PROD-008 §6.5/§6.7): honest
// report truthfulness (bytes echoed from a fake resampler), the within-budget
// pass-through skip rule, failure-as-null (never a silent fallback), the
// CompressedPdfOutput metadata, and end-to-end byte-determinism THROUGH the
// REAL PdfWriter behind the PdfAssembler seam.
class CompressedPdfWriterTest {

    // The real writer behind the seam — one adapter line, as delivered.
    private val realAssembler = PdfAssembler { pages, creationDateMillis ->
        PdfWriter.write(pages, creationDateMillis)
    }

    @Test
    fun reportIsTruthful_bytesEchoedFromFakeResampler() {
        val resampler = FakeResampler()
        val writer = CompressedPdfWriter(resampler = resampler, assembler = realAssembler)
        val pages = listOf(
            page("JPEG-ALPHA", 3200, 2400),
            page("JPEG-BRAVO", 2400, 3200, rotationDegrees = 90),
        )

        val output = writer.write(pages, CompressPdfOptions(), CREATED_MILLIS)!!

        assertEquals(2, output.report.pageCount)
        // originalBytes = SUM of the caller-supplied payloads (the "before").
        assertEquals(("JPEG-ALPHA".length + "JPEG-BRAVO".length).toLong(), output.report.originalBytes)
        // compressedBytes = the ASSEMBLED PDF's true size — nothing else.
        assertEquals(output.pdfBytes.size.toLong(), output.report.compressedBytes)

        val first = output.report.pages[0]
        assertEquals(0, first.pageIndex)
        assertEquals("JPEG-ALPHA".length, first.beforeBytes)
        assertEquals("RESAMPLED(JPEG-ALPHA-1600x1200-q70)".length, first.afterBytes)
        assertEquals(3200, first.sourceWidthPx)
        assertEquals(2400, first.sourceHeightPx)
        assertEquals(1600, first.targetWidthPx)
        assertEquals(1200, first.targetHeightPx)
        assertFalse(first.skipped)

        val second = output.report.pages[1]
        assertEquals(1200, second.targetWidthPx)
        assertEquals(1600, second.targetHeightPx)
        assertTrue(!second.skipped)

        // The assembled PDF carries the resampler outputs, in page order,
        // with the rotation identical to normal export.
        val pdf = String(output.pdfBytes, Charsets.US_ASCII)
        assertTrue(pdf.contains("RESAMPLED(JPEG-ALPHA"))
        assertTrue(pdf.contains("RESAMPLED(JPEG-BRAVO"))
        assertTrue(pdf.indexOf("RESAMPLED(JPEG-ALPHA") < pdf.indexOf("RESAMPLED(JPEG-BRAVO"))
        assertTrue(pdf.contains("/Rotate 90"))
    }

    @Test
    fun withinBudgetPage_passesBytesThroughVerbatim_resamplerUntouched() {
        val resampler = FakeResampler()
        val writer = CompressedPdfWriter(resampler = resampler, assembler = realAssembler)
        val payload = "SMALL-WITHIN-BUDGET-PAYLOAD"

        val output = writer.write(listOf(page(payload, 1000, 750)), CompressPdfOptions(), CREATED_MILLIS)!!

        // Skip rule: the resampler is never consulted.
        assertTrue(resampler.calls.isEmpty())
        val report = output.report.pages.single()
        assertTrue(report.skipped)
        assertEquals(payload.length, report.beforeBytes)
        assertEquals(report.beforeBytes, report.afterBytes)
        assertEquals(1000, report.targetWidthPx)
        assertEquals(750, report.targetHeightPx)
        assertEquals(1000, report.sourceWidthPx)
        assertEquals(750, report.sourceHeightPx)
        // The ORIGINAL bytes pass through VERBATIM into the assembled PDF.
        val pdf = String(output.pdfBytes, Charsets.US_ASCII)
        assertTrue(pdf.contains(payload))
        assertEquals(payload.length.toLong(), output.report.originalBytes)
    }

    @Test
    fun resampleFailure_returnsNull_neverSilentlyFallsBack() {
        val resampler = FakeResampler(failWhen = { bytes -> String(bytes, Charsets.US_ASCII).contains("POISON") })
        val assembler = RecordingAssembler()
        val writer = CompressedPdfWriter(resampler = resampler, assembler = assembler)
        val pages = listOf(
            page("JPEG-OK", 3200, 2400),
            page("JPEG-POISON", 3200, 2400),
        )

        // A failed resample is an honest null — the original bytes are NEVER
        // silently substituted, and nothing is assembled.
        assertNull(writer.write(pages, CompressPdfOptions(), CREATED_MILLIS))
        assertEquals(0, assembler.calls)
    }

    @Test
    fun emptyPageList_returnsNull() {
        val writer = CompressedPdfWriter(resampler = FakeResampler(), assembler = realAssembler)
        assertNull(writer.write(emptyList(), CompressPdfOptions(), CREATED_MILLIS))
    }

    @Test
    fun endToEnd_byteDeterminism_fixedResamplerBytesSamePdfTwice() {
        // Mixed pages: two needing a downsample, one within budget (skip).
        val pages = listOf(
            page("JPEG-A", 3200, 2400),
            page("JPEG-B", 1800, 2400),
            page("JPEG-C", 800, 600),
        )
        val first = CompressedPdfWriter(FakeResampler(), realAssembler)
        val second = CompressedPdfWriter(FakeResampler(), realAssembler)

        val one = first.write(pages, CompressPdfOptions(), CREATED_MILLIS)!!
        val two = second.write(pages, CompressPdfOptions(), CREATED_MILLIS)!!

        assertArrayEquals("byte-identical PDFs for identical inputs", one.pdfBytes, two.pdfBytes)
        assertEquals(one.report, two.report)
    }

    @Test
    fun assemblerSeesResampledDimsRotationsAndOrder() {
        val assembler = RecordingAssembler()
        val writer = CompressedPdfWriter(resampler = FakeResampler(), assembler = assembler)

        writer.write(
            listOf(
                page("JPEG-A", 3200, 2400),
                page("JPEG-B", 2400, 3200, rotationDegrees = 90),
            ),
            CompressPdfOptions(quality = 90),
            CREATED_MILLIS,
        )!!

        // The PdfPageImage contract fed to the writer: resampled dims, the
        // caller's rotation, page order — identical to normal export.
        val assembled = assembler.assemblies.single()
        assertEquals(2, assembled.size)
        assertEquals(1600, assembled[0].widthPx)
        assertEquals(1200, assembled[0].heightPx)
        assertEquals(0, assembled[0].rotationDegrees)
        assertEquals(1200, assembled[1].widthPx)
        assertEquals(1600, assembled[1].heightPx)
        assertEquals(90, assembled[1].rotationDegrees)
        assertArrayEquals("RESAMPLED(JPEG-A-1600x1200-q90)".toByteArray(), assembled[0].jpegBytes)
        assertArrayEquals("RESAMPLED(JPEG-B-1200x1600-q90)".toByteArray(), assembled[1].jpegBytes)
    }

    @Test
    fun compressedBytesMayExceedOriginal_truthReportedNeverFallback() {
        val writer = CompressedPdfWriter(resampler = FakeResampler(), assembler = realAssembler)
        // A tiny within-budget payload: the PDF's fixed structural overhead
        // DWARFS it — compressedBytes > originalBytes is the honest truth.
        val output = writer.write(listOf(page("X", 64, 48)), CompressPdfOptions(), CREATED_MILLIS)!!

        assertEquals(1L, output.report.originalBytes)
        assertTrue(
            "PDF overhead exceeds the tiny payload",
            output.report.compressedBytes > output.report.originalBytes,
        )
        // The report carries the truth; there is no silent fallback to
        // originalBytes anywhere in the output.
        assertEquals(output.pdfBytes.size.toLong(), output.report.compressedBytes)
        assertEquals(1, output.report.pageCount)
        assertEquals(0, output.report.pages.single().pageIndex)
        assertTrue(output.report.pages.single().skipped)
    }

    @Test
    fun optionsQuality_flowsToTheResampler() {
        val resampler = FakeResampler()
        val writer = CompressedPdfWriter(resampler = resampler, assembler = realAssembler)

        writer.write(listOf(page("JPEG-A", 3200, 2400)), CompressPdfOptions(quality = 42), CREATED_MILLIS)!!

        assertEquals("JPEG-A->1600x1200q42", resampler.calls.single())
    }

    // ---------------------------------------------------------- fixtures

    // Deterministic fake: the output payload encodes input + target + quality.
    private class FakeResampler(
        private val failWhen: (ByteArray) -> Boolean = { false },
    ) : ImageResampler {
        val calls = mutableListOf<String>()

        override fun resample(
            jpegBytes: ByteArray,
            targetWidthPx: Int,
            targetHeightPx: Int,
            quality: Int,
        ): ResampledJpeg? {
            if (failWhen(jpegBytes)) return null
            val input = String(jpegBytes, Charsets.US_ASCII)
            calls += (input + "->" + targetWidthPx + "x" + targetHeightPx + "q" + quality)
            val payload = "RESAMPLED(" + input + "-" + targetWidthPx + "x" + targetHeightPx + "-q" + quality + ")"
            return ResampledJpeg(payload.toByteArray(), targetWidthPx, targetHeightPx)
        }
    }

    // Assembler fake capturing the exact PdfPageImage lists it is handed.
    private class RecordingAssembler : PdfAssembler {
        val assemblies = mutableListOf<List<PdfPageImage>>()
        var calls = 0

        override fun assemble(pages: List<PdfPageImage>, creationDateMillis: Long): ByteArray {
            calls += 1
            assemblies += pages
            return ("ASSEMBLED-" + pages.size).toByteArray()
        }
    }

    private fun page(
        payload: String,
        widthPx: Int,
        heightPx: Int,
        rotationDegrees: Int = 0,
    ): CompressPageInput = CompressPageInput(
        jpegBytes = payload.toByteArray(),
        widthPx = widthPx,
        heightPx = heightPx,
        rotationDegrees = rotationDegrees,
    )

    private companion object {
        const val CREATED_MILLIS = 1_000_000L
    }
}
