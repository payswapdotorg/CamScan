package org.payswap.camscan.tools.signature

import org.payswap.camscan.core.time.TimeSource

// SignatureStore (CAMSCAN-PROD-011 section 6.2): an in-memory named
// signature library. Time flows exclusively through the injected
// [TimeSource]; ids are deterministic per store instance ("sig-1",
// "sig-2", ... in insertion order). Persistence wiring is a wave-4
// handoff; this store is the pure in-memory core.

/** One stored signature. */
class SignatureEntry(
    val id: String,
    val name: String,
    val sketch: SignatureSketch,
    val createdMillis: Long,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignatureEntry) return false
        return id == other.id && name == other.name && sketch == other.sketch &&
            createdMillis == other.createdMillis
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + sketch.hashCode()
        result = 31 * result + createdMillis.hashCode()
        return result
    }

    override fun toString(): String =
        "SignatureEntry[id=" + id + ";name=" + name + ";strokes=" + sketch.strokes.size + "]"
}

/** In-memory named signature library with deterministic ordering. */
class SignatureStore(private val timeSource: TimeSource) {

    private val entries = LinkedHashMap<String, SignatureEntry>()
    private var nextId = 1

    /** Adds a sketch under [name]; ids are assigned in insertion order. */
    fun add(name: String, sketch: SignatureSketch): SignatureEntry {
        require(name.isNotEmpty()) { "name must not be empty" }
        val id = PREFIX + nextId
        nextId += 1
        val entry = SignatureEntry(id, name, sketch, timeSource.nowMillis())
        entries[id] = entry
        return entry
    }

    /** All entries in insertion order (deterministic). */
    fun list(): List<SignatureEntry> = entries.values.toList()

    /** The entry with [id], or null. */
    fun get(id: String): SignatureEntry? = entries[id]

    /** Removes the entry with [id]; true when it existed. */
    fun remove(id: String): Boolean = entries.remove(id) != null

    val size: Int get() = entries.size

    companion object {
        const val PREFIX: String = "sig-"
    }
}
