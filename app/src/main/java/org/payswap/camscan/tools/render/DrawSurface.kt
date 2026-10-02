package org.payswap.camscan.tools.render

// DrawSurface: the pure Kotlin rasterizer (CAMSCAN-PROD-011 section 6.1)
// over an IntArray ARGB buffer. Determinism contract: the same plan plus
// the same input buffer always produces a byte-identical output buffer.
//
// Rasterization rules (documented, binding):
//   - Stroke: classic all-octant integer Bresenham per segment between
//     consecutive points; a single-point stroke plots that point once.
//     Consecutive duplicate points are NOT deduplicated (callers thin);
//     with semi-transparent stroke colors re-plotting re-blends by design.
//   - Square brush: a stroke of widthPx paints a square brush centered on
//     each rasterized pixel; with h = widthPx / 2 (integer division) the
//     square spans dx and dy in [-h, widthPx - 1 - h]. widthPx 1 -> single
//     pixel, 3 -> a symmetric 3x3 square, 2 -> the documented asymmetric
//     [-1, 0] span. There is NO anti-aliasing: determinism first.
//   - FillRect / Stroke / BlitGlyphs composite source-over using the op
//     color's own alpha byte (alpha 255 = plain replace fast path).
//   - BlendRect uses the op's alpha parameter as the source alpha and
//     ignores the color's own alpha byte.
//   - MultiplyRect multiplies each RGB channel: out = round_half_up(dst * src / 255).
//   - Blending math: exact non-premultiplied source-over in integer
//     arithmetic with round-half-up; the formula
//     (2*N + A) / (2*A) rounds the exact rational N/A half-up for all
//     non-negative inputs (ties are unreachable for odd denominators).
//   - Everything is clipped to the surface bounds; out-of-bounds pixels are
//     skipped silently.

 // Pure rasterizer over an ARGB [IntArray] buffer of [width] x [height].
 // Glyph ops resolve through [glyphSource]; an op carrying a different
 // glyphSourceId fails fast with IllegalArgumentException.
 // /
class DrawSurface(
    val width: Int,
    val height: Int,
    val glyphSource: TextGlyphSource = TextGlyphSource(),
) {

    init {
        require(width > 0 && height > 0) { "surface dimensions must be positive" }
    }

    /** Renders [plan] onto a fresh fully transparent buffer. */
    fun render(plan: DrawPlan): IntArray = render(IntArray(width * height), plan)

    /** Renders [plan] onto a copy of [base]; [base] itself is never mutated. */
    fun render(base: IntArray, plan: DrawPlan): IntArray {
        require(base.size == width * height) { "base buffer size mismatch" }
        val buffer = base.copyOf()
        for (op in plan.ops) {
            when (op) {
                is DrawOp.Stroke -> drawStroke(buffer, op)
                is DrawOp.FillRect -> drawFillRect(buffer, op)
                is DrawOp.BlitGlyphs -> drawBlitGlyphs(buffer, op)
                is DrawOp.BlendRect -> drawBlendRect(buffer, op)
                is DrawOp.MultiplyRect -> drawMultiplyRect(buffer, op)
            }
        }
        return buffer
    }

    // ------------------------------------------------------------------
    // Stroke rasterization
    // ------------------------------------------------------------------

    private fun drawStroke(buffer: IntArray, op: DrawOp.Stroke) {
        if (op.points.isEmpty()) return
        if (op.points.size == 1) {
            plotBrush(buffer, op.points[0].x, op.points[0].y, op.widthPx, op.colorArgb)
            return
        }
        for (index in 0 until op.points.size - 1) {
            bresenham(buffer, op.points[index], op.points[index + 1], op.widthPx, op.colorArgb)
        }
    }

    /** Classic all-octant integer Bresenham between two points. */
    private fun bresenham(buffer: IntArray, from: Point, to: Point, widthPx: Int, colorArgb: Long) {
        var x0 = from.x
        var y0 = from.y
        val x1 = to.x
        val y1 = to.y
        val dx = Math.abs(x1 - x0)
        val dy = Math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        while (true) {
            plotBrush(buffer, x0, y0, widthPx, colorArgb)
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                x0 += sx
            }
            if (e2 < dx) {
                err += dx
                y0 += sy
            }
        }
    }

    /** Plots the square brush of [widthPx] centered at (x, y). */
    private fun plotBrush(buffer: IntArray, x: Int, y: Int, widthPx: Int, colorArgb: Long) {
        if (widthPx <= 0) return
        val half = widthPx / 2
        val span = widthPx - 1 - half
        for (dy in -half..span) {
            for (dx in -half..span) {
                setPixel(buffer, x + dx, y + dy, colorArgb)
            }
        }
    }

    // ------------------------------------------------------------------
    // Rectangular ops
    // ------------------------------------------------------------------

    private fun drawFillRect(buffer: IntArray, op: DrawOp.FillRect) {
        val rect = op.rect
        val xStart = Math.max(0, rect.x)
        val yStart = Math.max(0, rect.y)
        val xEnd = Math.min(width, rect.right)
        val yEnd = Math.min(height, rect.bottom)
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                setPixel(buffer, x, y, op.colorArgb)
            }
        }
    }

    private fun drawBlendRect(buffer: IntArray, op: DrawOp.BlendRect) {
        val rect = op.rect
        val xStart = Math.max(0, rect.x)
        val yStart = Math.max(0, rect.y)
        val xEnd = Math.min(width, rect.right)
        val yEnd = Math.min(height, rect.bottom)
        val srcColor = (op.colorArgb and 0x00FFFFFFL) or 0xFF000000L
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                blendPixel(buffer, x, y, srcColor, op.alpha)
            }
        }
    }

    private fun drawMultiplyRect(buffer: IntArray, op: DrawOp.MultiplyRect) {
        val rect = op.rect
        val xStart = Math.max(0, rect.x)
        val yStart = Math.max(0, rect.y)
        val xEnd = Math.min(width, rect.right)
        val yEnd = Math.min(height, rect.bottom)
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                multiplyPixel(buffer, x, y, op.colorArgb)
            }
        }
    }

    // ------------------------------------------------------------------
    // Glyph blitting
    // ------------------------------------------------------------------

    private fun drawBlitGlyphs(buffer: IntArray, op: DrawOp.BlitGlyphs) {
        if (op.glyphSourceId != glyphSource.id) {
            throw IllegalArgumentException(
                "glyph source mismatch: op wants " + op.glyphSourceId +
                    " but surface renders with " + glyphSource.id,
            )
        }
        var cursorX = op.origin.x
        val baselineY = op.origin.y
        for (index in op.text.indices) {
            val ch = op.text[index]
            val glyph = glyphSource.glyph(ch) ?: continue
            for (row in 0 until glyph.heightPx) {
                for (col in 0 until glyph.widthPx) {
                    if (glyph.pixel(col, row)) {
                        setPixel(buffer, cursorX + col, baselineY + row, op.colorArgb)
                    }
                }
            }
            cursorX += glyphSource.advance(ch)
        }
    }

    // ------------------------------------------------------------------
    // Pixel-level primitives
    // ------------------------------------------------------------------

    private fun setPixel(buffer: IntArray, x: Int, y: Int, colorArgb: Long) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        val sa = Argb.alpha(colorArgb)
        if (sa == 0) return
        val index = y * width + x
        if (sa == 255) {
            buffer[index] = (colorArgb and 0xFFFFFFFFL).toInt()
            return
        }
        buffer[index] = blendValue(buffer[index], colorArgb, sa)
    }

    private fun blendPixel(buffer: IntArray, x: Int, y: Int, srcColor: Long, srcAlpha: Int) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        if (srcAlpha <= 0) return
        if (srcAlpha >= 255) {
            buffer[y * width + x] = (srcColor and 0xFFFFFFFFL).toInt()
            return
        }
        val index = y * width + x
        buffer[index] = blendValue(buffer[index], srcColor, srcAlpha)
    }

    private fun multiplyPixel(buffer: IntArray, x: Int, y: Int, colorArgb: Long) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        val index = y * width + x
        val dst = buffer[index]
        val dstA = (dst ushr 24) and 0xFF
        val srcA = Argb.alpha(colorArgb)
        val sa = if (srcA == 255) dstA else (srcA * dstA + 127) / 255
        val r = multiplyChannel(Argb.red(colorArgb), (dst ushr 16) and 0xFF)
        val g = multiplyChannel(Argb.green(colorArgb), (dst ushr 8) and 0xFF)
        val b = multiplyChannel(Argb.blue(colorArgb), dst and 0xFF)
        buffer[index] = (sa shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** out = round_half_up(src * dst / 255); exact for all inputs. */
    private fun multiplyChannel(src: Int, dst: Int): Int = (src * dst + 127) / 255

     // Exact non-premultiplied source-over with round-half-up.
     // A = 255*sa + da*(255-sa) is 255x the exact output alpha; each channel
     // uses (2*N + A) / (2*A) which rounds the exact rational N/A half-up.
     // /
    internal fun blendValue(dstPacked: Int, srcColor: Long, sa: Int): Int {
        val da = (dstPacked ushr 24) and 0xFF
        val a = 255 * sa + da * (255 - sa)
        if (a <= 0) return 0x00000000
        val outA = (2 * a + 255) / 510
        val inv = 255 - sa
        val sr = Argb.red(srcColor)
        val sg = Argb.green(srcColor)
        val sb = Argb.blue(srcColor)
        val dr = (dstPacked ushr 16) and 0xFF
        val dg = (dstPacked ushr 8) and 0xFF
        val db = dstPacked and 0xFF
        val twoA = 2 * a
        val r = (2 * (sr * sa * 255 + dr * da * inv) + a) / twoA
        val g = (2 * (sg * sa * 255 + dg * da * inv) + a) / twoA
        val b = (2 * (sb * sa * 255 + db * da * inv) + a) / twoA
        return (outA shl 24) or (r shl 16) or (g shl 8) or b
    }
}
