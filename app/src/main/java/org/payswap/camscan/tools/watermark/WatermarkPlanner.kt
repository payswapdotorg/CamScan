package org.payswap.camscan.tools.watermark

import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.TextGlyphSource

// WatermarkPlanner (CAMSCAN-PROD-011 section 6.4): pure computation of the
// rotated tiled glyph-run placements over a page. No rendering here.
//
// Geometry (documented, binding):
//   - run size: width = measure(text).width, height = lineHeight (9 px);
//   - cell size: cellW = runW + tileSpacingPx, cellH = runH + tileSpacingPx;
//   - coverage square: side = ceil(sqrt(pageW^2 + pageH^2)) - the diagonal
//     of the page, which is the smallest square that covers the page under
//     ANY rotation;
//   - the tile grid is centered on the page center with half-extents
//     halfX = side / cellW + 1 and halfY = side / cellH + 1 (integer
//     division plus one cell of safety margin on every side), so the grid
//     always covers the whole page after rotation;
//   - every tile center (pageCenter + i * cellW, pageCenter + j * cellH)
//     is rotated AROUND the page center by rotationDegrees; multiples of
//     90 degrees use exact integer rotation matrices, any other angle uses
//     double cos/sin rounded with Math.round (IEEE mul/div are exactly
//     specified, so the result is deterministic; see the delivery notes
//     for the residual 1-ulp caveat);
//   - the emitted origin of each glyph run is the rotated tile center
//     minus (runW / 2, runH / 2) using integer division;
//   - deterministic iteration order: j (rows) outer, i (columns) inner,
//     both ascending from -half to +half.
//
// Positive rotationDegrees rotates tiles CLOCKWISE on screen (raster y
// grows downward). Empty text yields NO placements.

/** Pure tiled-placement planner. */
class WatermarkPlanner(
    private val glyphSource: TextGlyphSource = TextGlyphSource(),
) {

    /** Origins (top-left of the glyph run) of every watermark tile. */
    fun plan(spec: WatermarkSpec, pageWidth: Int, pageHeight: Int): List<Point> {
        if (spec.text.isEmpty()) return emptyList()
        if (spec.opacity <= 0) return emptyList()

        val measured = glyphSource.measure(spec.text)
        val runW = Math.max(1, measured.width)
        val runH = Math.max(1, glyphSource.lineHeight)
        val cellW = runW + spec.tileSpacingPx
        val cellH = runH + spec.tileSpacingPx

        val side = coverageSide(pageWidth, pageHeight)
        val halfX = side / cellW + 1
        val halfY = side / cellH + 1
        val centerX = pageWidth / 2
        val centerY = pageHeight / 2

        val placements = ArrayList<Point>((2 * halfX + 1) * (2 * halfY + 1))
        var j = -halfY
        while (j <= halfY) {
            var i = -halfX
            while (i <= halfX) {
                val dx = i * cellW
                val dy = j * cellH
                val rotated = rotateAroundCenter(centerX, centerY, dx, dy, spec.rotationDegrees)
                placements.add(
                    Point(rotated.first - runW / 2, rotated.second - runH / 2),
                )
                i++
            }
            j++
        }
        return placements
    }

    /** ceil(sqrt(w^2 + h^2)) with Long intermediates (overflow-safe). */
    internal fun coverageSide(pageWidth: Int, pageHeight: Int): Int {
        val squared = pageWidth.toLong() * pageWidth.toLong() +
            pageHeight.toLong() * pageHeight.toLong()
        val root = Math.sqrt(squared.toDouble())
        return Math.ceil(root).toInt()
    }

     // Rotates (center + (dx, dy)) by [degrees] around the center.
     // Multiples of 90 are exact integer rotations; other angles use
     // Math.round on IEEE trig products.
     // /
    internal fun rotateAroundCenter(
        centerX: Int,
        centerY: Int,
        dx: Int,
        dy: Int,
        degrees: Int,
    ): Pair<Int, Int> {
        val normalized = Math.floorMod(degrees, 360)
        return when (normalized) {
            0 -> Pair(centerX + dx, centerY + dy)
            90 -> Pair(centerX - dy, centerY + dx)
            180 -> Pair(centerX - dx, centerY - dy)
            270 -> Pair(centerX + dy, centerY - dx)
            else -> {
                val radians = Math.toRadians(normalized.toDouble())
                val cos = Math.cos(radians)
                val sin = Math.sin(radians)
                val x = centerX + Math.round(cos * dx - sin * dy).toInt()
                val y = centerY + Math.round(sin * dx + cos * dy).toInt()
                Pair(x, y)
            }
        }
    }
}
