package org.payswap.camscan.tools.backup

// In-memory fake of the backup IO seam for JVM tests (CAMSCAN-PROD-015
// §6.6): a sorted-map store behind the pure [BackupFileIo] interface.

/** In-memory [BackupFileIo] fake used by the backup engine tests. */
class InMemoryBackupIo : BackupFileIo {

    private val store = LinkedHashMap<String, ByteArray>()

    override fun write(path: String, bytes: ByteArray) {
        store[path] = bytes.copyOf()
    }

    override fun read(path: String): ByteArray? {
        val bytes = store[path] ?: return null
        return bytes.copyOf()
    }

    override fun list(prefix: String): List<String> {
        return store.keys.filter { path -> path.startsWith(prefix) }.sorted()
    }

    /** Number of files currently stored. */
    fun size(): Int = store.size

    /** All stored paths, insertion order. */
    fun paths(): List<String> = store.keys.toList()
}
