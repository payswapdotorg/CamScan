package org.payswap.camscan.processing


import org.payswap.camscan.core.model.PageEnhancementMode

/*
 * CAMSCAN-PROD-003 §6.2 — the processing engine's output page.
 *
 * This is the concrete type behind the scan-engine contract's
 * "ProcessedPage" seam (docs/SCAN-ENGINE-CONTRACT.md: exact class names
 * may vary, the seams may not) — [PerspectiveCorrector.correct] returns it
 * and [EnhancementEngine.process] consumes/returns it.
 *
 * The lead-owned durable `Page` model (core/model/Documents.kt) is NOT
 * touched: converting ProcessedImage -> Page (with ContentStore refs,
 * normalized cropQuad, timestamps via TimeSource) happens at the PROD-004
 * session seam.
 */
class ProcessedImage(

    /** Rendered pixels (packed ARGB, row-major). */
    val buffer: ImageBuffer,

    /** Retained source-to-page geometry metadata. */
    val geometry: ProcessedGeometry,

    /**
     * Enhancement applied to THIS buffer. ORIGINAL at correction time;
     * [EnhancementEngine.process] stamps the mode it applied.
     */
    val enhancement: PageEnhancementMode,

    /**
     * Stable content-addressed id (see [ContentId]). Assigned at
     * correction time and PRESERVED verbatim by enhancement — it
     * identifies the page, not the pixel state, so a mode chain keeps
     * one identity (the contract's "stable id" requirement).
     */
    val id: String,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessedImage) return false
        return buffer == other.buffer &&
            geometry == other.geometry &&
            enhancement == other.enhancement &&
            id == other.id
    }

    override fun hashCode(): Int {
        var result = buffer.hashCode()
        result = 31 * result + geometry.hashCode()
        result = 31 * result + enhancement.hashCode()
        result = 31 * result + id.hashCode()
        return result
    }

    override fun toString(): String =
        "ProcessedImage(id=$id, ${buffer.width}x${buffer.height}, " +
            "enhancement=$enhancement, aspect=${geometry.pageAspect})"
}

/**
 * Deterministic content-addressed page ids: 64-bit FNV-1a over the pixel
 * dimensions and ARGB content. Same pixels -> same id (the
 * byte-stability contract); different pixels -> different id with
 * overwhelming probability. NOT a cryptographic hash — advisory identity
 * only. Pure integer math: no RNG, no time, no device state.
 */
object ContentId {

    private const val FNV_OFFSET_BASIS = 0xcbf29ce484222325UL
    private const val FNV_PRIME = 0x100000001b3UL

    fun of(buffer: ImageBuffer): String {
        var hash = FNV_OFFSET_BASIS.toLong()
        hash = mix(hash, buffer.width)
        hash = mix(hash, buffer.height)
        for (pixel in buffer.argb) {
            hash = mix(hash, pixel)
        }
        return "%016x".format(hash)
    }

    private fun mix(hash: Long, value: Int): Long {
        var acc = hash
        acc = (acc xor (value.toLong() and 0xFF)) * FNV_PRIME.toLong()
        acc = (acc xor ((value ushr 8).toLong() and 0xFF)) * FNV_PRIME.toLong()
        acc = (acc xor ((value ushr 16).toLong() and 0xFF)) * FNV_PRIME.toLong()
        acc = (acc xor ((value ushr 24).toLong() and 0xFF)) * FNV_PRIME.toLong()
        return acc
    }
}
