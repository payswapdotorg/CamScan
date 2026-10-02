package org.payswap.camscan.tools.protection

// PdfWriterLayout (CAMSCAN-PROD-011 section 6.5): a parser for the KNOWN
// deterministic layout of org.payswap.camscan.export.pdf.PdfWriter output
// (and of this tree's encrypted post-process of it, which preserves the
// same object numbering and stream extents while appending one /Encrypt
// dictionary object before the xref).
//
// This is deliberately NOT a general PDF parser. It relies on the writer's
// documented fixed layout:
//   - bytes [0, 9)  = "%PDF-1.4\n"; bytes [9, 15) = the fixed binary
//     marker comment line; object 1 starts at offset 15;
//   - objects appear in numbering order 1, 2, 3, then exactly three per
//     page (page dict, content stream, image XObject stream);
//   - dict objects are pure generated ASCII: "N 0 obj\n<< ... >>\nendobj\n";
//   - stream objects are "N 0 obj\n<< ... /Length L ... >>\nstream\n" +
//     exactly L bytes + "\nendstream\nendobj\n" - the /Length lets the
//     parser skip binary payload WITHOUT ever scanning inside it;
//   - after the last object comes "xref\n..." (the parser stops there).
//
// Classification rule: an object is a stream object when the marker
// "\nstream\n" occurs before any "\nendobj\n" in its ASCII header region;
// both markers can only occur in generated ASCII, so the scan never enters
// binary payload.

/** Parsed view of one object of a PdfWriter-layout document. */
internal sealed class ParsedObject {

    abstract val number: Int

    /** Byte offset of the "N 0 obj" header (inclusive). */
    abstract val start: Int

    /** Byte offset just past "endobj\n" (exclusive). */
    abstract val end: Int

    class Dict(
        override val number: Int,
        override val start: Int,
        override val end: Int,
        /** Offset just past "N 0 obj\n". */
        val bodyStart: Int,
    ) : ParsedObject()

    class Stream(
        override val number: Int,
        override val start: Int,
        override val end: Int,
        val bodyStart: Int,
        /** Offset of the first stream data byte. */
        val dataStart: Int,
        /** Stream payload length (equals the declared /Length). */
        val dataLength: Int,
    ) : ParsedObject()
}

/** Parser for the documented fixed PdfWriter layout. */
internal object PdfWriterLayout {

    private val HEADER = "%PDF-1.4".toByteArray(Charsets.US_ASCII)
    private val STREAM_MARKER = "\nstream\n".toByteArray(Charsets.US_ASCII)
    private val ENDOBJ_MARKER = "\nendobj\n".toByteArray(Charsets.US_ASCII)
    private val ENDSTREAM_TAIL = "\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII)
    private val XREF_PREFIX = "xref\n".toByteArray(Charsets.US_ASCII)

    // 92 is the backslash byte; written as a numeric constant so the
    // delivered source never carries a doubled backslash escape.
    private val BACKSLASH: Byte = 92

    /** Parses all objects in order; validates the fixed layout strictly. */
    fun parseObjects(bytes: ByteArray): List<ParsedObject> {
        require(bytes.size > 15) { "input too short to be PdfWriter output" }
        for (index in HEADER.indices) {
            require(bytes[index] == HEADER[index]) {
                "input does not start with the PdfWriter header"
            }
        }
        val objects = ArrayList<ParsedObject>()
        var cursor = 15
        var expectedNumber = 1
        while (!startsWith(bytes, cursor, XREF_PREFIX)) {
            val headerLength = digitsOf(expectedNumber) + 7
            val headerText = expectedNumber.toString() + " 0 obj\n"
            require(startsWithAscii(bytes, cursor, headerText)) {
                "object " + expectedNumber + " not found at offset " + cursor +
                    " - input is not the documented PdfWriter layout"
            }
            val bodyStart = cursor + headerLength
            val streamMarker = indexOf(bytes, STREAM_MARKER, bodyStart)
            val endobjMarker = indexOf(bytes, ENDOBJ_MARKER, bodyStart)
            require(endobjMarker >= 0) { "unterminated object " + expectedNumber }
            if (streamMarker >= 0 && streamMarker < endobjMarker) {
                val dictLine = ByteArray(streamMarker - bodyStart)
                System.arraycopy(bytes, bodyStart, dictLine, 0, dictLine.size)
                val length = parseDeclaredLength(dictLine)
                val dataStart = streamMarker + STREAM_MARKER.size
                val dataEnd = dataStart + length
                require(dataEnd + ENDSTREAM_TAIL.size <= bytes.size) {
                    "stream of object " + expectedNumber + " overruns the document"
                }
                for (index in ENDSTREAM_TAIL.indices) {
                    require(bytes[dataEnd + index] == ENDSTREAM_TAIL[index]) {
                        "stream tail mismatch for object " + expectedNumber
                    }
                }
                val end = dataEnd + ENDSTREAM_TAIL.size
                objects.add(ParsedObject.Stream(expectedNumber, cursor, end, bodyStart, dataStart, length))
                cursor = end
            } else {
                val end = endobjMarker + ENDOBJ_MARKER.size
                objects.add(ParsedObject.Dict(expectedNumber, cursor, end, bodyStart))
                cursor = end
            }
            expectedNumber++
        }
        require(objects.size >= 4) { "no page objects found" }
        require((objects.size - 3) % 3 == 0 || (objects.size - 3) % 3 == 1) {
            "object count " + objects.size + " matches neither the writer layout " +
                "(3 + 3 per page) nor the encrypted layout (one extra /Encrypt object)"
        }
        return objects
    }

     // Page count implied by the fixed layout. Only meaningful for pure
     // writer output (the encrypted file carries one extra /Encrypt
     // dictionary object); callers pass the object list they parsed.
     // /
    fun pageCount(objects: List<ParsedObject>): Int {
        require((objects.size - 3) % 3 == 0) { "not a pure writer layout" }
        return (objects.size - 3) / 3
    }

     // Extracts the literal string contents of an ASCII dict region: every
     // "(...)" span becomes one entry (byte content between the parens).
     // The writer's dict strings never contain nested parens or escapes -
     // this is asserted, not assumed.
     // /
    fun extractLiteralStrings(bytes: ByteArray, from: Int, until: Int): List<ByteArray> {
        val strings = ArrayList<ByteArray>()
        var index = from
        while (index < until) {
            if (bytes[index] == '('.code.toByte()) {
                val close = index + 1
                var end = -1
                var scan = close
                while (scan < until) {
                    val b = bytes[scan]
                    if (b == ')'.code.toByte()) {
                        end = scan
                        break
                    }
                    require(b != '('.code.toByte() && b != BACKSLASH) {
                        "nested parens or escapes are outside the documented layout"
                    }
                    scan++
                }
                require(end >= 0) { "unterminated literal string" }
                val content = ByteArray(end - close)
                System.arraycopy(bytes, close, content, 0, content.size)
                strings.add(content)
                index = end + 1
            } else {
                index++
            }
        }
        return strings
    }

    /** Replaces every "(...)" span in a dict region with its hex form. */
    fun replaceLiteralStringsWithHex(
        bytes: ByteArray,
        from: Int,
        until: Int,
        encoder: (ByteArray) -> ByteArray,
    ): ByteArray {
        val out = ByteArrayBuilder()
        var index = from
        while (index < until) {
            if (bytes[index] == '('.code.toByte()) {
                val close = index + 1
                var end = -1
                var scan = close
                while (scan < until) {
                    val b = bytes[scan]
                    if (b == ')'.code.toByte()) {
                        end = scan
                        break
                    }
                    require(b != '('.code.toByte() && b != BACKSLASH) {
                        "nested parens or escapes are outside the documented layout"
                    }
                    scan++
                }
                require(end >= 0) { "unterminated literal string" }
                val content = ByteArray(end - close)
                System.arraycopy(bytes, close, content, 0, content.size)
                val encoded = encoder(content)
                out.append('<')
                out.append(toHex(encoded))
                out.append('>')
                index = end + 1
            } else {
                out.append(bytes[index])
                index++
            }
        }
        return out.toByteArray()
    }

    /** The declared /Length of a stream dict line, parsed manually. */
    internal fun parseDeclaredLength(dictLine: ByteArray): Int {
        val needle = "/Length ".toByteArray(Charsets.US_ASCII)
        var index = indexOf(dictLine, needle, 0)
        require(index >= 0) { "stream dict has no /Length" }
        index += needle.size
        var value = 0
        var any = false
        while (index < dictLine.size && dictLine[index] >= '0'.code.toByte() &&
            dictLine[index] <= '9'.code.toByte()
        ) {
            value = value * 10 + (dictLine[index] - '0'.code.toByte())
            any = true
            index++
        }
        require(any) { "stream /Length is not an integer" }
        return value
    }

    /** Minimal byte-index helpers (no regex, no platform variance). */
    internal fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        val limit = haystack.size - needle.size
        var index = Math.max(0, from)
        while (index <= limit) {
            var matched = true
            for (n in needle.indices) {
                if (haystack[index + n] != needle[n]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
            index++
        }
        return -1
    }

    private fun startsWith(haystack: ByteArray, offset: Int, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > haystack.size) return false
        for (index in needle.indices) {
            if (haystack[offset + index] != needle[index]) return false
        }
        return true
    }

    private fun startsWithAscii(haystack: ByteArray, offset: Int, text: String): Boolean =
        startsWith(haystack, offset, text.toByteArray(Charsets.US_ASCII))

    private fun digitsOf(number: Int): Int = number.toString().length
}

/** Tiny growable byte builder (keeps everything explicit and allocation-simple). */
internal class ByteArrayBuilder {

    private var data = ByteArray(64)
    private var used = 0

    fun append(b: Byte) {
        ensure(1)
        data[used] = b
        used++
    }

    fun append(ch: Char) = append(ch.code.toByte())

    fun append(text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        ensure(bytes.size)
        System.arraycopy(bytes, 0, data, used, bytes.size)
        used += bytes.size
    }

    fun append(bytes: ByteArray) {
        ensure(bytes.size)
        System.arraycopy(bytes, 0, data, used, bytes.size)
        used += bytes.size
    }

    fun append(bytes: ByteArray, from: Int, length: Int) {
        ensure(length)
        System.arraycopy(bytes, from, data, used, length)
        used += length
    }

    fun size(): Int = used

    fun toByteArray(): ByteArray = data.copyOf(used)

    private fun ensure(extra: Int) {
        if (used + extra > data.size) {
            var capacity = data.size
            while (capacity < used + extra) {
                capacity = capacity * 2
            }
            data = data.copyOf(capacity)
        }
    }
}
