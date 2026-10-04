package org.payswap.camscan.settings.backup

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// CAMSCAN-VERIFY-002 — the android side of the backup destinations.
// Preferred route: the system file picker (ActivityResultContracts.
// CreateDocument / OpenDocument) and a content-resolver stream — the
// user picks where the archive lands. Fallback route (picker
// unavailable or the surrounding flow throws ActivityNotFound): the
// app's external-files directory with a path SHOWN to the user (never
// a silent dump). Pure byte plumbing; all archive semantics live in
// the coordinators + engines.

/** System-picker and fallback destinations of the backup flow. */
object AndroidBackupDestinations {

    /** Writes the archive bytes into a picker-provided tree/stream uri. */
    fun writeToPickedUri(context: Context, uri: Uri, bytes: ByteArray): Boolean {
        return try {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: return false
            output.use { it.write(bytes) }
            true
        } catch (expected: IOException) {
            false
        } catch (expected: SecurityException) {
            false
        }
    }

    /** Reads the picked archive's bytes; null when unreadable or empty. */
    fun readFromPickedUri(context: Context, uri: Uri): ByteArray? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { it.readBytes() }
        } catch (expected: IOException) {
            null
        } catch (expected: SecurityException) {
            null
        }
    }

    /**
     * Fallback destination directory (visible to the user): the app's
     * external-files dir when mounted, else the private files dir.
     */
    fun fallbackDirectory(context: Context): File {
        val external = context.getExternalFilesDir(null)
        if (external != null) {
            return external
        }
        return context.filesDir
    }

    /** Writes the archive bytes into the fallback directory; null on failure. */
    fun writeToFallback(
        context: Context,
        fileName: String,
        bytes: ByteArray,
    ): File? {
        return try {
            val directory = fallbackDirectory(context)
            val target = File(directory, fileName)
            FileOutputStream(target).use { it.write(bytes) }
            target
        } catch (expected: IOException) {
            null
        }
    }
}
