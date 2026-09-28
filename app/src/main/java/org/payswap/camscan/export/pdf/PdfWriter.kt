package org.payswap.camscan.export.pdf

import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * One exported page handed to [PdfWriter]: the DCTDecode payload plus the
 * geometry the writer needs to size and orient the page.
 *
 * [jpegBytes] must be a complete JPEG stream (the writer embeds it verbatim
 * behind a `/Filter /DCTDecode` image XObject — it never parses or re-encodes
 * it). [widthPx]/[heightPx] are the JPEG's pixel dimensions and drive the
 * MediaBox; [rotationDegrees] is the model's right-angle view rotation and is
 * emitted as the PDF `/Rotate` value.
 */
data class PdfPageImage(
    val jpegBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int = 0,
    /** SOF component count: 1 → /DeviceGray, 3 → /DeviceRGB, 4 → /DeviceCMYK. */
    val colorComponents: Int = COLOR_COMPONENTS_RGB,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdfPageImage) return false
        return widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            rotationDegrees == other.rotationDegrees &&
            colorComponents == other.colorComponents &&
            jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * widthPx + heightPx) + rotationDegrees) + colorComponents) +
            jpegBytes.contentHashCode()

    override fun toString(): String =
        "PdfPageImage(widthPx=$widthPx, heightPx=$heightPx, rotationDegrees=" +
            "$rotationDegrees, colorComponents=$colorComponents, jpegBytes=${jpegBytes.size})"

    companion object {
        const val COLOR_COMPONENTS_RGB = 3
    }
}

/**
 * Deterministic, minimal PDF 1.4 writer (CAMSCAN-PROD-007 §6.1) — pure
 * Kotlin, android-free, JVM-testable.
 *
 * Structure: one catalog (object 1), one page-tree root (object 2), one info
 * dictionary (object 3), then exactly three objects per page in document
 * order — page (4 + 3·i), content stream (5 + 3·i), DCTDecode image XObject
 * (6 + 3·i) — followed by a cross-reference table with exact byte offsets,
 * a trailer, and `startxref`/`%%EOF`.
 *
 * Scale choice (documented per the work order): **1 pixel = 0.75 point**
 * (72 pt/inch over a 96 dpi pixel grid). MediaBox width/height are computed
 * as `px * 3 / 4` in exact quarter-point integer arithmetic and rendered
 * with at most two decimals (`.25`/`.5`/`.75`) — no floating point is
 * involved, so page boxes like a 128×96 px page become exactly
 * `[0 0 96 72]`.
 *
 * Determinism (documented): identical inputs (`pages` + `creationDateMillis`)
 * produce byte-identical output. Object numbering and layout order are fixed;
 * metadata carries only `/Producer (CamScan)` and a `/CreationDate` derived
 * from the injected timestamp in UTC. The trailer deliberately carries **no
 * `/ID`**: it is optional in PDF 1.4 for unencrypted documents, a fixed ID
 * would add no integrity value, and a content-derived or random ID would
 * either add parsing complexity or break byte-determinism.
 *
 * Placement: each page's content stream is the canonical full-box placement
 * `q W 0 0 H 0 0 cm /Im0 Do Q` — the `cm` matrix maps the image XObject's
 * unit square onto the whole MediaBox, so the scan fills the page exactly.
 *
 * API contract (documented): [write] never throws on valid input;
 * [IllegalArgumentException] for an empty page list, empty JPEG bytes, or
 * non-positive pixel dimensions. Nullability is enforced by Kotlin's type
 * system (the "null bytes" failure mode of the packet is unrepresentable).
 */
object PdfWriter {

    const val PDF_HEADER = "%PDF-1.4"
    const val PRODUCER = "CamScan"

    /** Fixed object numbers: catalog / page-tree root / info dictionary. */
    private const val CATALOG_NUMBER = 1
    private const val PAGES_NUMBER = 2
    private const val INFO_NUMBER = 3
    private const val OBJECTS_PER_PAGE = 3

    /**
     * Builds the complete PDF document bytes for [pages] in list order.
     *
     * @param pages ordered page images (document index order is the caller's
     *   responsibility — see [org.payswap.camscan.export.ExportEngine]).
     * @param creationDateMillis epoch milliseconds used for `/CreationDate`
     *   (rendered in UTC; the single source of time for the document).
     * @throws IllegalArgumentException on the malformed inputs documented
     *   above.
     */
    fun write(pages: List<PdfPageImage>, creationDateMillis: Long): ByteArray {
        require(pages.isNotEmpty()) { "pages must not be empty (documented API contract)" }
        for ((index, page) in pages.withIndex()) {
            require(page.jpegBytes.isNotEmpty()) { "page $index: jpegBytes must not be empty" }
            require(page.widthPx > 0 && page.heightPx > 0) {
                "page $index: dimensions must be positive " +
                    "(was ${page.widthPx}x${page.heightPx})"
            }
        }

        val objectCount = 3 + OBJECTS_PER_PAGE * pages.size
        val offsets = IntArray(objectCount + 1)
        val out = ByteArrayOutputStream()

        fun ascii(text: String) {
            out.write(text.toByteArray(Charsets.US_ASCII))
        }

        fun beginObject(number: Int) {
            offsets[number] = out.size()
            ascii("$number 0 obj\n")
        }

        // Header: version line plus the conventional 4 high-bit bytes that
        // flag the file as binary (fixed bytes — determinism preserved).
        ascii("$PDF_HEADER\n")
        out.write(BINARY_MARKER)

        // Object 1: catalog.
        beginObject(CATALOG_NUMBER)
        ascii("<< /Type /Catalog /Pages $PAGES_NUMBER 0 R >>\n")
        ascii("endobj\n")

        // Object 2: page-tree root.
        beginObject(PAGES_NUMBER)
        val kids = pages.indices.joinToString(" ") { "${pageNumber(it)} 0 R" }
        ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\n")
        ascii("endobj\n")

        // Object 3: info dictionary.
        beginObject(INFO_NUMBER)
        ascii("<< /Producer ($PRODUCER) /CreationDate (${pdfCreationDate(creationDateMillis)}) >>\n")
        ascii("endobj\n")

        // Three objects per page: page, content stream, image XObject.
        for (index in pages.indices) {
            val page = pages[index]
            val boxWidth = pxToPoints(page.widthPx)
            val boxHeight = pxToPoints(page.heightPx)

            beginObject(pageNumber(index))
            ascii(
                "<< /Type /Page /Parent $PAGES_NUMBER 0 R " +
                    "/MediaBox [0 0 $boxWidth $boxHeight] " +
                    "/Resources << /XObject << /Im0 ${imageNumber(index)} 0 R >> >> " +
                    "/Contents ${contentNumber(index)} 0 R " +
                    "/Rotate ${normalizeRotation(page.rotationDegrees)} >>\n",
            )
            ascii("endobj\n")

            val content = "q $boxWidth 0 0 $boxHeight 0 0 cm /Im0 Do Q"
            beginObject(contentNumber(index))
            ascii("<< /Length ${content.toByteArray(Charsets.US_ASCII).size} >>\n")
            ascii("stream\n")
            ascii(content)
            ascii("\nendstream\n")
            ascii("endobj\n")

            beginObject(imageNumber(index))
            ascii(
                "<< /Type /XObject /Subtype /Image /Width ${page.widthPx} " +
                    "/Height ${page.heightPx} " +
                    "/ColorSpace ${colorSpaceName(page.colorComponents)} " +
                    "/BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpegBytes.size} >>\n",
            )
            ascii("stream\n")
            out.write(page.jpegBytes)
            ascii("\nendstream\n")
            ascii("endobj\n")
        }

        // Cross-reference table: entry 0 is the free-list head, entries 1..N
        // are the objects above, each exactly 20 bytes long.
        val xrefOffset = out.size()
        val entryCount = objectCount + 1
        ascii("xref\n0 $entryCount\n")
        ascii("0000000000 65535 f \n")
        for (number in 1..objectCount) {
            ascii("${offsets[number].toString().padStart(OFFSET_DIGITS, '0')} 00000 n \n")
        }

        ascii("trailer\n<< /Size $entryCount /Root $CATALOG_NUMBER 0 R /Info $INFO_NUMBER 0 R >>\n")
        ascii("startxref\n$xrefOffset\n")
        ascii("%%EOF\n")
        return out.toByteArray()
    }

    /** Page object number for page [index] (0-based). */
    internal fun pageNumber(index: Int): Int = 4 + OBJECTS_PER_PAGE * index

    /** Content-stream object number for page [index] (0-based). */
    internal fun contentNumber(index: Int): Int = 5 + OBJECTS_PER_PAGE * index

    /** Image XObject number for page [index] (0-based). */
    internal fun imageNumber(index: Int): Int = 6 + OBJECTS_PER_PAGE * index

    /**
     * Exact pixel→point conversion at the documented scale (1 px = 0.75 pt):
     * `px * 3 / 4`, formatted with at most two decimals because the
     * remainder of a quarter is always 0, 25, 50, or 75 hundredths.
     */
    internal fun pxToPoints(px: Int): String {
        val quarterUnits = px * 3
        val whole = quarterUnits / 4
        return when (quarterUnits % 4) {
            0 -> whole.toString()
            1 -> "$whole.25"
            2 -> "$whole.5"
            else -> "$whole.75"
        }
    }

    /**
     * Rotation normalized to a right angle in 0..270: the model contract
     * allows only right-angle rotations; anything else snaps deterministically
     * down to the nearest lower multiple of 90 (documented, never throws).
     */
    internal fun normalizeRotation(degrees: Int): Int {
        val normalized = Math.floorMod(degrees, 360)
        return normalized / 90 * 90
    }

    /**
     * `/CreationDate` for [millis] in the PDF `D:YYYYMMDDHHmmSS+00'00'` form,
     * always in UTC so the output never depends on the host time zone.
     */
    internal fun pdfCreationDate(millis: Long): String {
        val calendar = GregorianCalendar(UTC)
        calendar.timeInMillis = millis

        fun field(value: Int, width: Int): String = value.toString().padStart(width, '0')
        return "D:" +
            field(calendar.get(Calendar.YEAR), 4) +
            field(calendar.get(Calendar.MONTH) + 1, 2) +
            field(calendar.get(Calendar.DAY_OF_MONTH), 2) +
            field(calendar.get(Calendar.HOUR_OF_DAY), 2) +
            field(calendar.get(Calendar.MINUTE), 2) +
            field(calendar.get(Calendar.SECOND), 2) +
            "+00'00'"
    }

    /**
     * ColorSpace name for a JPEG component count. Unexpected counts fall back
     * to /DeviceRGB (the encoder's output contract) — documented, never throws.
     */
    internal fun colorSpaceName(colorComponents: Int): String = when (colorComponents) {
        1 -> "/DeviceGray"
        3 -> "/DeviceRGB"
        4 -> "/DeviceCMYK"
        else -> "/DeviceRGB"
    }

    private val UTC = TimeZone.getTimeZone("UTC")

    /** `%`, 0xE2, 0xE3, 0xCF, 0xD3, `\n` — the binary-flag comment line. */
    private val BINARY_MARKER = byteArrayOf(
        0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A,
    )

    private const val OFFSET_DIGITS = 10
}
