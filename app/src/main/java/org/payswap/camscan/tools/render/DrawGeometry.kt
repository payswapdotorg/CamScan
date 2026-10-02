package org.payswap.camscan.tools.render

// Minimal raster geometry value types shared by the whole tools tree
// (CAMSCAN-PROD-011 section 6.1). Pure Kotlin, android-free, JVM-testable.
//
// Coordinate convention (documented, binding for every tool): raster
// coordinates with x growing right and y growing DOWN (the natural IntArray
// ARGB buffer order). Rect uses (x, y) = top-left corner plus width/height.

/** Immutable raster point. */
class Point(val x: Int, val y: Int) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Point) return false
        return x == other.x && y == other.y
    }

    override fun hashCode(): Int = 31 * x + y

    override fun toString(): String = "(" + x + "," + y + ")"
}

/** Immutable size in pixels. */
class Size(val width: Int, val height: Int) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Size) return false
        return width == other.width && height == other.height
    }

    override fun hashCode(): Int = 31 * width + height

    override fun toString(): String = width.toString() + "x" + height
}

/** Immutable axis-aligned rectangle. (x, y) is the top-left corner. */
class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {

    val left: Int get() = x
    val top: Int get() = y
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Rect) return false
        return x == other.x && y == other.y && width == other.width && height == other.height
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        result = 31 * result + width
        result = 31 * result + height
        return result
    }

    override fun toString(): String = "[" + x + "," + y + " " + width + "x" + height + "]"
}

/** ARGB color helpers. Colors live in the low 32 bits of a Long. */
object Argb {

    const val WHITE: Long = 0xFFFFFFFFL
    const val BLACK: Long = 0xFF000000L
    const val TRANSPARENT: Long = 0x00000000L

    fun alpha(colorArgb: Long): Int = ((colorArgb ushr 24) and 0xFFL).toInt()

    fun red(colorArgb: Long): Int = ((colorArgb ushr 16) and 0xFFL).toInt()

    fun green(colorArgb: Long): Int = ((colorArgb ushr 8) and 0xFFL).toInt()

    fun blue(colorArgb: Long): Int = (colorArgb and 0xFFL).toInt()

    fun argb(a: Int, r: Int, g: Int, b: Int): Long =
        ((a.toLong() and 0xFF) shl 24) or
            ((r.toLong() and 0xFF) shl 16) or
            ((g.toLong() and 0xFF) shl 8) or
            (b.toLong() and 0xFF)

    /** Lowercase 8-digit hex form (stable serialized representation). */
    fun toHex(colorArgb: Long): String {
        val HEX = "0123456789abcdef"
        val v = colorArgb and 0xFFFFFFFFL
        val chars = CharArray(8)
        var i = 7
        var rest = v
        while (i >= 0) {
            chars[i] = HEX[(rest and 0xFL).toInt()]
            rest = rest ushr 4
            i--
        }
        return String(chars)
    }
}
