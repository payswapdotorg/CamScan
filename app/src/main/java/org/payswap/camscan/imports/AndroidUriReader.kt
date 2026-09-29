package org.payswap.camscan.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [UriReader] (CAMSCAN-PROD-008 §6.1): the ContentResolver seam —
 * `openInputStream` for the bytes, an `OpenableColumns.DISPLAY_NAME` query
 * for the picker's name. The only android-importing file in the engine's
 * package; both reads dispatch to the injected dispatcher (IO in production)
 * so engine calls stay main-safe. Every failure is an honest null (flag
 * discipline — never throws to the engine).
 */
class AndroidUriReader(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UriReader {

    override suspend fun readBytes(uri: String): ByteArray? = withContext(dispatcher) {
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                stream.readBytes()
            }
        } catch (expected: Exception) {
            null
        }
    }

    override suspend fun displayName(uri: String): String? = withContext(dispatcher) {
        queryDisplayName(uri)
    }

    private fun queryDisplayName(uri: String): String? {
        val resolved = Uri.parse(uri)
        return try {
            val queried = context.contentResolver.query(
                resolved,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
            queried ?: resolved.lastPathSegment
        } catch (expected: Exception) {
            null
        }
    }
}
