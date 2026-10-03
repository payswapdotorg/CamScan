package org.payswap.camscan.tools.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// CAMSCAN-PROD-015 §6.1 — the deterministic ZIP container engine.
// Layout (FROZEN): member "manifest.txt" first, then one member per
// manifest entry, in manifest order. Every zip entry carries the FIXED
// timestamp [BackupArchiveWriter.FIXED_EPOCH_MILLIS] so two builds of the
// same input in the same environment are byte-identical (documented
// constant: 2000-01-01T00:00:00Z; java.util.zip derives the stored DOS
// time from this one constant, so builds never vary). On read, the
// sha256 of EVERY member is verified against the manifest; any mismatch,
// layout deviation or unreadable zip is the sealed
// [BackupArchiveError.Corrupt].

/** Sealed failure of reading or decoding a backup archive. */
sealed class BackupArchiveError(message: String) : RuntimeException(message) {

    /** The archive exists but its bytes do not decode to a valid backup. */
    class Corrupt(reason: String) : BackupArchiveError("corrupt backup archive: " + reason)

    /** No archive exists at the requested path. */
    class Missing(path: String) : BackupArchiveError("no backup archive at: " + path)
}

/** Verified payload of a successfully read backup archive. */
data class BackupArchiveContents(
    val manifest: BackupManifest,
    val members: Map<String, ByteArray>,
) {

    /** Returns the member bytes for a path, or null when absent. */
    fun member(path: String): ByteArray? = members[path]

    /** Manifest entries whose path starts with the prefix, in manifest order (used by the restore applier to enumerate a document subtree). */
    fun membersUnder(prefix: String): List<BackupEntry> {
        return manifest.entries.filter { it.pathInArchive.startsWith(prefix) }
    }
}

/** Deterministic writer of the backup ZIP container. */
object BackupArchiveWriter {

    /** Fixed member name of the serialized manifest. */
    const val MANIFEST_ENTRY_NAME = "manifest.txt"

    /** Fixed zip-entry timestamp (2000-01-01T00:00:00Z) — the single documented constant every member carries so builds are reproducible. */
    const val FIXED_EPOCH_MILLIS: Long = 946684800000L

    /** Validates the manifest against the contents, then writes the deterministic archive bytes to the path through the [BackupFileIo] seam. Throws IllegalArgumentException when a declared member is missing, undeclared content is present, or a member's size or sha256 does not match its descriptor. */
    fun write(
        archivePath: String,
        manifest: BackupManifest,
        contents: Map<String, ByteArray>,
        io: BackupFileIo,
    ) {
        io.write(archivePath, buildBytes(manifest, contents))
    }

    /** Builds the deterministic archive bytes (manifest member first, then members in manifest order, fixed timestamps). Throws the same validation errors as [write]. */
    fun buildBytes(
        manifest: BackupManifest,
        contents: Map<String, ByteArray>,
    ): ByteArray {
        val manifestText = manifest.toText()
        validateContents(manifest, contents)
        val byteStream = ByteArrayOutputStream()
        val zipStream = ZipOutputStream(byteStream)
        zipStream.use { zos ->
            putEntry(zos, MANIFEST_ENTRY_NAME, manifestText.toByteArray(Charsets.UTF_8))
            for (entry in manifest.entries) {
                putEntry(
                    zos,
                    entry.pathInArchive,
                    contents.getValue(entry.pathInArchive),
                )
            }
        }
        return byteStream.toByteArray()
    }

    private fun validateContents(
        manifest: BackupManifest,
        contents: Map<String, ByteArray>,
    ) {
        val declaredPaths = HashSet<String>()
        for (entry in manifest.entries) {
            if (entry.pathInArchive == MANIFEST_ENTRY_NAME) {
                throw IllegalArgumentException(
                    "reserved member name cannot be declared: " + MANIFEST_ENTRY_NAME,
                )
            }
            declaredPaths.add(entry.pathInArchive)
            val bytes = contents[entry.pathInArchive]
            if (bytes == null) {
                throw IllegalArgumentException(
                    "missing content for declared member: " + entry.pathInArchive,
                )
            }
            if (bytes.size.toLong() != entry.sizeBytes) {
                throw IllegalArgumentException(
                    "size mismatch for member " + entry.pathInArchive +
                        ": declared " + entry.sizeBytes.toString() +
                        ", actual " + bytes.size.toString(),
                )
            }
            val actualSha = Sha256Text.sha256Hex(bytes)
            if (actualSha != entry.sha256Hex) {
                throw IllegalArgumentException(
                    "sha256 mismatch for member " + entry.pathInArchive +
                        ": declared " + entry.sha256Hex + ", actual " + actualSha,
                )
            }
        }
        for (path in contents.keys) {
            if (path == MANIFEST_ENTRY_NAME) {
                throw IllegalArgumentException(
                    "reserved member name cannot be supplied: " + MANIFEST_ENTRY_NAME,
                )
            }
            if (!declaredPaths.contains(path)) {
                throw IllegalArgumentException(
                    "content supplied but not declared in manifest: " + path,
                )
            }
        }
    }

    private fun putEntry(
        zipStream: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val zipEntry = ZipEntry(name)
        zipEntry.time = FIXED_EPOCH_MILLIS
        zipStream.putNextEntry(zipEntry)
        zipStream.write(bytes)
        zipStream.closeEntry()
    }
}

/** Reader and verifier of the backup ZIP container. */
object BackupArchiveReader {

    /** Reads and fully verifies the archive at the path through the [BackupFileIo] seam. Throws [BackupArchiveError.Missing] when no archive exists and [BackupArchiveError.Corrupt] for ANY decoding failure: unreadable zip bytes, "manifest.txt" not first, an unparseable manifest, member layout deviating from the manifest (missing, extra or reordered members), a size mismatch or a sha256 mismatch of any member. */
    fun read(archivePath: String, io: BackupFileIo): BackupArchiveContents {
        val bytes = io.read(archivePath)
            ?: throw BackupArchiveError.Missing(archivePath)
        val memberList = try {
            unzipAll(bytes)
        } catch (e: Exception) {
            throw BackupArchiveError.Corrupt("unreadable zip stream (" + e.javaClass.simpleName + ")")
        }
        if (memberList.isEmpty()) {
            throw BackupArchiveError.Corrupt("archive is empty")
        }
        if (memberList[0].first != BackupArchiveWriter.MANIFEST_ENTRY_NAME) {
            throw BackupArchiveError.Corrupt(
                "first member is not " + BackupArchiveWriter.MANIFEST_ENTRY_NAME +
                    " but " + memberList[0].first,
            )
        }
        val manifestText = String(memberList[0].second, Charsets.UTF_8)
        val manifest = BackupManifest.parse(manifestText)
            ?: throw BackupArchiveError.Corrupt("manifest does not parse")
        if (memberList.size != manifest.entryCount + 1) {
            throw BackupArchiveError.Corrupt(
                "member count mismatch: manifest declares " +
                    manifest.entryCount.toString() + " entries, archive has " +
                    (memberList.size - 1).toString() + " members",
            )
        }
        val members = LinkedHashMap<String, ByteArray>()
        for (index in manifest.entries.indices) {
            val declared = manifest.entries[index]
            val actual = memberList[index + 1]
            if (actual.first != declared.pathInArchive) {
                throw BackupArchiveError.Corrupt(
                    "member order mismatch at position " + (index + 1).toString() +
                        ": expected " + declared.pathInArchive +
                        ", found " + actual.first,
                )
            }
            if (actual.second.size.toLong() != declared.sizeBytes) {
                throw BackupArchiveError.Corrupt(
                    "size mismatch for member " + declared.pathInArchive,
                )
            }
            val actualSha = Sha256Text.sha256Hex(actual.second)
            if (actualSha != declared.sha256Hex) {
                throw BackupArchiveError.Corrupt(
                    "sha256 mismatch for member " + declared.pathInArchive +
                        ": declared " + declared.sha256Hex + ", actual " + actualSha,
                )
            }
            members[declared.pathInArchive] = actual.second
        }
        return BackupArchiveContents(manifest, members)
    }

    private fun unzipAll(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipStream ->
            while (true) {
                val entry = zipStream.nextEntry ?: break
                val content = zipStream.readBytes()
                result.add(entry.name to content)
                zipStream.closeEntry()
            }
        }
        return result
    }
}
