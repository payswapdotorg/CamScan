package org.payswap.camscan.export.pdf

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [JpegDimensionParser] (CAMSCAN-PROD-007 §6.6): the pure SOF
 * marker scan used by the export encoder's JPEG fast path. Fixtures are
 * synthetic JPEG streams built from real marker grammar — no binary files
 * in git.
 */
class JpegDimensionParserTest {

    @Test
    fun baselineJpeg_parsesDimensionsAndComponents() {
        val jpeg = syntheticJpeg(width = 800, height = 600)
        val dims = JpegDimensionParser.parse(jpeg)
        assertNotNull(dims)
        assertEquals(800, dims!!.widthPx)
        assertEquals(600, dims.heightPx)
        assertEquals(3, dims.componentCount)
    }

    @Test
    fun progressiveJpeg_sof2_isRecognized() {
        val jpeg = syntheticJpeg(width = 320, height = 240, sofMarker = 0xC2)
        val dims = JpegDimensionParser.parse(jpeg)
        assertNotNull(dims)
        assertEquals(320, dims!!.widthPx)
        assertEquals(240, dims.heightPx)
    }

    @Test
    fun grayscaleJpeg_reportsSingleComponent() {
        val jpeg = syntheticJpeg(width = 64, height = 64, components = 1)
        assertEquals(1, JpegDimensionParser.parse(jpeg)!!.componentCount)
    }

    @Test
    fun losslessJpeg_sof3_isRecognized() {
        // SOF3 (0xC3) is in the SOF family and must be accepted.
        val jpeg = syntheticJpeg(width = 10, height = 20, sofMarker = 0xC3)
        val dims = JpegDimensionParser.parse(jpeg)
        assertNotNull(dims)
        assertEquals(10, dims!!.widthPx)
        assertEquals(20, dims.heightPx)
    }

    @Test
    fun markersAfterSof_areNotRequired() {
        // Dimension data precedes DHT/SOS/EOI in real streams; the parser
        // must return at the SOF without walking the entropy-coded data.
        val jpeg = syntheticJpeg(width = 640, height = 480, trailingSegments = true)
        assertEquals(640, JpegDimensionParser.parse(jpeg)!!.widthPx)
    }

    @Test
    fun nonJpegBytes_returnNull() {
        assertNull(JpegDimensionParser.parse("PNGISH-NOT-A-JPEG".toByteArray()))
        assertNull(JpegDimensionParser.parse(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test
    fun truncatedStreams_returnNull() {
        val jpeg = syntheticJpeg(width = 800, height = 600)
        assertNull("SOI only", JpegDimensionParser.parse(jpeg.copyOf(2)))
        // The baseline fixture lays its SOF header out at bytes 20..29
        // (marker, length, precision, height, width, components); cutting at
        // 25 lands inside the header, before the width bytes.
        assertNull("cut inside the SOF header", JpegDimensionParser.parse(jpeg.copyOf(25)))
        assertNull("empty", JpegDimensionParser.parse(ByteArray(0)))
        assertNull("too small to be anything", JpegDimensionParser.parse(byteArrayOf(1, 2)))
    }

    @Test
    fun markerWithoutSof_returnsNull() {
        // APP segment + EOI but no frame: no dimensions available.
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        writeSegment(out, 0xE0, ByteArray(14))
        out.write(0xFF); out.write(0xD9)
        assertNull(JpegDimensionParser.parse(out.toByteArray()))
    }

    @Test
    fun fillBytesBeforeMarker_areSkipped() {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        // APP0 with fill bytes (0xFF padding) before the next marker.
        out.write(0xFF); out.write(0xE0)
        out.write(0x00); out.write(0x10)
        repeat(14) { out.write(0x00) }
        out.write(0xFF); out.write(0xFF); out.write(0xFF); out.write(0xC0)
        writeSofBody(out, width = 12, height = 34, components = 3)
        out.write(0xFF); out.write(0xD9)
        val dims = JpegDimensionParser.parse(out.toByteArray())
        assertNotNull(dims)
        assertEquals(12, dims!!.widthPx)
        assertEquals(34, dims.heightPx)
    }

    @Test
    fun zeroDimensions_returnNull() {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        out.write(0xFF); out.write(0xC0)
        writeSofBody(out, width = 0, height = 0, components = 3)
        assertNull(JpegDimensionParser.parse(out.toByteArray()))
    }

    @Test
    fun nonMarkerGarbage_returnsNull() {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        repeat(20) { out.write(0x42) }
        assertNull(JpegDimensionParser.parse(out.toByteArray()))
    }

    // ------------------------------------------------------------------
    // Fixture helpers (real JPEG marker grammar, synthetic payloads)
    // ------------------------------------------------------------------

    private fun syntheticJpeg(
        width: Int,
        height: Int,
        sofMarker: Int = 0xC0,
        components: Int = 3,
        trailingSegments: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8) // SOI
        writeSegment(out, 0xE0, ByteArray(14)) // APP0/JFIF
        if (trailingSegments) {
            writeSegment(out, 0xDB, ByteArray(65)) // DQT
        }
        out.write(0xFF); out.write(sofMarker)
        writeSofBody(out, width, height, components)
        if (trailingSegments) {
            writeSegment(out, 0xC4, ByteArray(20)) // DHT
            out.write(0xFF); out.write(0xDA) // SOS
            out.write(0x00); out.write(0x08) // minimal scan header
        }
        out.write(0xFF); out.write(0xD9) // EOI
        return out.toByteArray()
    }

    private fun writeSegment(out: ByteArrayOutputStream, marker: Int, payload: ByteArray) {
        out.write(0xFF); out.write(marker)
        val length = payload.size + 2
        out.write(length shr 8); out.write(length and 0xFF)
        out.write(payload)
    }

    private fun writeSofBody(out: ByteArrayOutputStream, width: Int, height: Int, components: Int) {
        val length = 8 + 3 * components
        out.write(length shr 8); out.write(length and 0xFF)
        out.write(8) // sample precision
        out.write(height shr 8); out.write(height and 0xFF)
        out.write(width shr 8); out.write(width and 0xFF)
        out.write(components)
        repeat(3 * components) { out.write(0x11) }
    }
}
