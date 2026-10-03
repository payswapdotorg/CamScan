package org.payswap.camscan.tools.conversion

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// CAMSCAN-PROD-015 §6.2 — deterministic ZIP building for the OOXML
// adapters. Entries are written in the exact order the caller lists,
// every entry carries the FIXED timestamp FIXED_EPOCH_MILLIS
// (2000-01-01T00:00:00Z) so two exports of the same document in the
// same environment are byte-identical.

/** Deterministic zip builder for adapter-generated OOXML packages. */
object ConversionZip {

    /** Fixed zip-entry timestamp for reproducible exports. */
    const val FIXED_EPOCH_MILLIS: Long = 946684800000L

    /** Builds zip bytes from (name, text) parts, in the given order. */
    fun build(parts: List<Pair<String, String>>): ByteArray {
        val byteStream = ByteArrayOutputStream()
        val zipStream = ZipOutputStream(byteStream)
        zipStream.use { zos ->
            for (part in parts) {
                val zipEntry = ZipEntry(part.first)
                zipEntry.time = FIXED_EPOCH_MILLIS
                zos.putNextEntry(zipEntry)
                zos.write(part.second.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return byteStream.toByteArray()
    }
}
