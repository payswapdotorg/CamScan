package org.payswap.camscan.document.persistence

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.payswap.camscan.core.storage.ContentStore

/**

File-backed [ContentStore] over a single app-private directory

(default: context.filesDir/content). CAMSCAN-PROD-006.

Ref vocabulary: [A-Za-z0-9._-]+ ending in .bin (e.g. <uuid>.bin).

Refs outside the vocabulary are refused by open/delete/exists (this makes

path traversal impossible — separators can never appear in a ref).

[put] honors a refHint only after sanitizing it into the vocabulary; a hint

that sanitizes to nothing falls back to the injected name generator.

Atomicity: put writes to a hidden temp file in the same directory and

renames onto the target (same-filesystem rename). The single

android-importing line in this file is the [fromContext] factory; the rest

of the class is java.io-only and runs on the JVM under test.
*/
class FileContentStore(
val rootDir: File,
private val newRefName: () -> String = { UUID.randomUUID().toString() },
) : ContentStore {

override suspend fun put(key: String, bytes: ByteArray): String {
if (!rootDir.exists() && !rootDir.mkdirs()) {
throw IOException("ContentStore root not creatable: $rootDir")

}
val ref = sanitizeHint(key) ?: (newRefName() + REF_SUFFIX)
val target = File(rootDir, ref)
val tmp = File(rootDir, ".$ref.${UUID.randomUUID()}.tmp")

try {
FileOutputStream(tmp).use { out -> out.write(bytes) }
if (!tmp.renameTo(target)) {
// Cross-platform fallback; never leaves the tmp behind.
tmp.copyTo(target, overwrite = true)
tmp.delete()
}
} finally {
if (tmp.exists()) tmp.delete()
}
return ref
}

override suspend fun open(ref: String): ByteArray? {
if (!isSafeRef(ref)) return null
val file = File(rootDir, ref)
if (!file.isFile) return null
return try {
file.readBytes()
} catch (e: IOException) {
null
}
}

override suspend fun delete(ref: String): Boolean {
if (!isSafeRef(ref)) return false
val file = File(rootDir, ref)
return file.isFile && file.delete()
}

override suspend fun exists(ref: String): Boolean {
if (!isSafeRef(ref)) return false
return File(rootDir, ref).isFile
}

companion object {
private const val REF_SUFFIX = ".bin"
private val REF_PATTERN = Regex("[A-Za-z0-9.-]+\\.bin")
private val UNSAFE_CHARS = Regex("[^A-Za-z0-9.-]")

/** The only android-importing line of the persistence layer. */
fun fromContext(context: Context): FileContentStore =
FileContentStore(File(context.filesDir, "content"))

private fun isSafeRef(ref: String): Boolean = REF_PATTERN.matches(ref)

private fun sanitizeHint(hint: String): String? {
val cleaned = UNSAFE_CHARS.replace(hint, "").trim('.')
if (cleaned.isEmpty()) return null
val name = if (cleaned.endsWith(REF_SUFFIX)) cleaned else cleaned + REF_SUFFIX
return if (REF_PATTERN.matches(name)) name else null
}
}

}
