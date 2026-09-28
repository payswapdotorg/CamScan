package org.payswap.camscan.capture.detect


import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt


/**
 * CAMSCAN-PROD-002 §6.2 — the pure-Kotlin document detector (no new
 * dependencies: no OpenCV, no ML Kit — architecture-lock §Discipline, clean
 * -room image math only).
 *
 * Pipeline (downsample → gradients → threshold → contour support → largest
 * convex quadrilateral → ordering → confidence → flags):
 *
 *  1. **Downsample** the packed luminance frame with a deterministic box
 *     filter to a working resolution ([maxWorkDimension] on the long edge).
 *  2. **Otsu threshold** the working image into bright/dark classes (a
 *     document page under normal lighting is the bright foreground on a
 *     darker surface). Uniform frames (stddev below a floor) yield `null`.
 *  3. **Largest bright component** (4-connectivity flood fill) — the page
 *     hypothesis; a component washing over essentially the whole frame with
 *     border contact on all sides is background, not a page.
 *  4. **Convex hull + largest inscribed quadrilateral** (exhaustive
 *     maximum-area search over the hull's cyclic order, hull simplified and
 *     capped) — the quad hypothesis in working coordinates.
 *  5. **Corner refinement at full resolution**: each coarse corner snaps to
 *     the strongest normalized-Sobel gradient inside a small window, so
 *     reported corners land within a few pixels of the true page corner.
 *  6. **Contour-support validation at full resolution**: sample the quad
 *     perimeter; the fraction of samples sitting on strong page/background
 *     transitions feeds confidence, and the sharpest perimeter gradient
 *     (relative to the Otsu class separation) feeds the BLUR flag.
 *  7. **Ordering + flags + confidence**: canonical TL/TR/BR/BL via
 *     [QuadGeometry.orderCorners]; flags from evidence (border-touching quad
 *     ⇒ PARTIAL_PAGE; weak global contrast ⇒ LOW_CONTRAST; saturated region ⇒
 *     GLARE; soft edges ⇒ BLUR; sub-minimum quad area ⇒ NO_PAGE); confidence
 *     = 0.5·edge-support + 0.3·regularity + 0.2·area-sanity, clamped to
 *     [0, 1].
 *
 * Deterministic by construction: every step is a pure function of the frame
 * bytes (integer arithmetic where practical, no randomness, no time reads).
 * The analysis timestamp arrives as a parameter.
 *
 * Scope note (honest limitation): the page hypothesis is the bright
 * foreground — a bright page on a darker background, the dominant real-world
 * document-scanning scene and the §6.7 fixture family. A dark page on a
 * bright surface is not reliably detected in this work order; see the
 * delivery report's open questions.
 */
class EdgeQuadDetector(
    /** Working-resolution long edge; smaller = faster, coarser corners. */
    private val maxWorkDimension: Int = DEFAULT_MAX_WORK_DIMENSION,
    /** Quads below this area fraction of the frame get NO_PAGE. */
    private val minQuadAreaFraction: Double = DEFAULT_MIN_QUAD_AREA_FRACTION,
    /** Frame stddev below this flags LOW_CONTRAST. */
    private val lowContrastStdThreshold: Double = DEFAULT_LOW_CONTRAST_STD,
    /** Otsu class separation (bright-mean − dark-mean) below this flags LOW_CONTRAST. */
    private val lowContrastSeparationThreshold: Double = DEFAULT_LOW_CONTRAST_SEPARATION,
    /** Luminance at/above this counts as saturated (glare evidence). */
    private val glareLumaThreshold: Int = DEFAULT_GLARE_LUMA,
    /** Saturated-pixel fraction at/above this flags GLARE. */
    private val glareAreaFraction: Double = DEFAULT_GLARE_AREA_FRACTION,
    /** Perimeter sharpness (max gradient / half the class separation) below this flags BLUR. */
    private val blurSharpnessThreshold: Double = DEFAULT_BLUR_SHARPNESS,
    /** Quad proximity to the frame border (working pixels) that flags PARTIAL_PAGE. */
    private val borderTouchWorkPx: Int = DEFAULT_BORDER_TOUCH_WORK_PX,
) : DocumentDetector {

    override fun detect(frame: DetectorFrame, timestampMs: Long): DocumentDetection? {
        if (!frame.isWellFormed) return null
        if (frame.width < MIN_FRAME_DIMENSION || frame.height < MIN_FRAME_DIMENSION) return null

        // -- 1. downsample ---------------------------------------------------
        val work = downsample(frame)
        val w = work.width
        val h = work.height
        val pixels = work.luminance
        val total = w * h

        // -- 2. histogram, stats, Otsu ---------------------------------------
        val hist = IntArray(LUMA_LEVELS)
        for (v in pixels) hist[v]++
        var sum = 0.0
        for (level in 0 until LUMA_LEVELS) sum += level.toDouble() * hist[level]
        val mean = sum / total
        var variance = 0.0
        for (level in 0 until LUMA_LEVELS) {
            val d = level - mean
            variance += d * d * hist[level]
        }
        val std = sqrt(variance / total)
        if (std < UNIFORM_STD_FLOOR) return null // blank/uniform: nothing qualifies

        val threshold = otsuThreshold(hist, total, sum)
        var darkWeight = 0.0
        var darkSum = 0.0
        for (level in 0..threshold) {
            darkWeight += hist[level]
            darkSum += level.toDouble() * hist[level]
        }
        val darkMean = if (darkWeight > 0) darkSum / darkWeight else 0.0
        val brightMean = if (total - darkWeight > 0) (sum - darkSum) / (total - darkWeight) else 0.0
        val separation = brightMean - darkMean

        // -- 3. largest bright component --------------------------------------
        val foreground = BooleanArray(total) { pixels[it] > threshold }
        val component = largestComponent(foreground, w, h) ?: return null
        if (isBackgroundWash(component, w, h)) return null

        // -- 4. hull + largest inscribed quad (working space) ------------------
        val hull = convexHull(component.points, w)
        if (hull.size < CORNER_COUNT) return null
        val simplified = simplifyHull(hull)
        if (simplified.size < CORNER_COUNT) return null
        val quadWork = largestInscribedQuad(simplified) ?: return null

        // -- evidence flags from global measurements ---------------------------
        val flags = linkedSetOf<DetectionQualityFlag>()
        if (std < lowContrastStdThreshold || separation < lowContrastSeparationThreshold) {
            flags.add(DetectionQualityFlag.LOW_CONTRAST)
        }
        if (saturatedFraction(pixels, total) >= glareAreaFraction) {
            flags.add(DetectionQualityFlag.GLARE)
        }

        // -- 5. map to full resolution + refine corners -------------------------
        val scaleX = frame.width.toDouble() / w
        val scaleY = frame.height.toDouble() / h
        val coarseFull = quadWork.map { corner ->
            Corner(
                (corner.x * scaleX).roundToInt().coerceIn(0, frame.width - 1),
                (corner.y * scaleY).roundToInt().coerceIn(0, frame.height - 1),
            )
        }
        val refined = coarseFull.map { refineCorner(frame, it, separation) }

        // -- 6. ordering + perimeter support (full resolution) ------------------
        val ordered = QuadGeometry.orderCorners(refined)
        if (ordered.size != CORNER_COUNT || !QuadGeometry.isConvex(ordered)) return null

        val borderMarginFull = max(2.0, borderTouchWorkPx * max(scaleX, scaleY))
        val touchesBorder = ordered.any { corner ->
            corner.x <= borderMarginFull ||
                corner.y <= borderMarginFull ||
                corner.x >= frame.width - 1 - borderMarginFull ||
                corner.y >= frame.height - 1 - borderMarginFull
        }
        if (touchesBorder) flags.add(DetectionQualityFlag.PARTIAL_PAGE)

        val perimeter = perimeterStats(frame, ordered, separation)
        val edgeSupport = perimeter.supportRatio
        if (separation >= MIN_SEPARATION_FOR_SHARPNESS &&
            perimeter.sharpness(separation) < blurSharpnessThreshold
        ) {
            flags.add(DetectionQualityFlag.BLUR)
        }

        // -- 7. confidence + page-area gate -------------------------------------
        val quadArea = QuadGeometry.area(ordered)
        val areaFraction = quadArea / (frame.width.toDouble() * frame.height)
        if (areaFraction < minQuadAreaFraction) {
            flags.add(DetectionQualityFlag.NO_PAGE)
        }

        val regularity = quadRegularity(ordered)
        val confidence = (
            EDGE_SUPPORT_WEIGHT * edgeSupport +
                REGULARITY_WEIGHT * regularity +
                AREA_SANITY_WEIGHT * areaSanity(areaFraction)
            ).coerceIn(0.0, 1.0)

        return DocumentDetection(
            corners = ordered,
            confidence = confidence.toFloat(),
            qualityFlags = flags,
            frameTimestampMs = timestampMs,
        )
    }

    // ------------------------------------------------------------------ downsample

    private class WorkImage(val width: Int, val height: Int, val luminance: IntArray)

    private fun downsample(frame: DetectorFrame): WorkImage {
        val longEdge = max(frame.width, frame.height)
        val scale = min(1.0, maxWorkDimension.toDouble() / longEdge)
        val w = max(MIN_WORK_DIMENSION, (frame.width * scale).roundToInt()).coerceAtMost(frame.width)
        val h = max(MIN_WORK_DIMENSION, (frame.height * scale).roundToInt()).coerceAtMost(frame.height)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = y * frame.height / h
            val y1 = max(y0 + 1, (y + 1) * frame.height / h)
            for (x in 0 until w) {
                val x0 = x * frame.width / w
                val x1 = max(x0 + 1, (x + 1) * frame.width / w)
                var sumL = 0L
                var count = 0
                for (yy in y0 until y1) {
                    val row = yy * frame.width
                    for (xx in x0 until x1) {
                        sumL += frame.luminance[row + xx].toInt() and 0xFF
                        count++
                    }
                }
                out[y * w + x] = (sumL / count).toInt()
            }
        }
        return WorkImage(w, h, out)
    }

    // ------------------------------------------------------------------ Otsu

    private fun otsuThreshold(hist: IntArray, total: Int, totalSum: Double): Int {
        var weightDark = 0.0
        var sumDark = 0.0
        var bestVariance = -1.0
        var bestThreshold = 0
        for (t in 0 until LUMA_LEVELS) {
            weightDark += hist[t]
            if (weightDark <= 0.0) continue
            val weightBright = total - weightDark
            if (weightBright <= 0.0) break
            sumDark += t.toDouble() * hist[t]
            val meanDark = sumDark / weightDark
            val meanBright = (totalSum - sumDark) / weightBright
            val between = (weightDark / total) * (weightBright / total) *
                (meanDark - meanBright) * (meanDark - meanBright)
            if (between > bestVariance) {
                bestVariance = between
                bestThreshold = t
            }
        }
        return bestThreshold
    }

    // ------------------------------------------------------------------ components

    private class Component(
        val area: Int,
        val points: IntArray,
        val touchesAllSides: Boolean,
    )

    private fun largestComponent(foreground: BooleanArray, w: Int, h: Int): Component? {
        val total = w * h
        val visited = BooleanArray(total)
        val stack = IntArray(total)
        var best: Component? = null
        for (start in 0 until total) {
            if (visited[start] || !foreground[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            val points = IntList()
            var left = false
            var top = false
            var right = false
            var bottom = false
            while (sp > 0) {
                val idx = stack[--sp]
                points.add(idx)
                val x = idx % w
                val y = idx / w
                if (x == 0) left = true
                if (y == 0) top = true
                if (x == w - 1) right = true
                if (y == h - 1) bottom = true
                if (x > 0 && foreground[idx - 1] && !visited[idx - 1]) {
                    visited[idx - 1] = true
                    stack[sp++] = idx - 1
                }
                if (y > 0 && foreground[idx - w] && !visited[idx - w]) {
                    visited[idx - w] = true
                    stack[sp++] = idx - w
                }
                if (x < w - 1 && foreground[idx + 1] && !visited[idx + 1]) {
                    visited[idx + 1] = true
                    stack[sp++] = idx + 1
                }
                if (y < h - 1 && foreground[idx + w] && !visited[idx + w]) {
                    visited[idx + w] = true
                    stack[sp++] = idx + w
                }
            }
            val touchesAllSides = left && top && right && bottom
            val component = Component(points.size, points.toArray(), touchesAllSides)
            if (best == null || component.area > best.area) {
                best = component
            }
        }
        return best
    }

    private fun isBackgroundWash(component: Component, w: Int, h: Int): Boolean {
        val fraction = component.area.toDouble() / (w * h)
        return fraction >= MAX_COMPONENT_FRACTION && component.touchesAllSides
    }

    /** Deterministic growable Int storage (no boxing). */
    private class IntList(initialCapacity: Int = 64) {
        private var data = IntArray(initialCapacity)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)
    }

    // ------------------------------------------------------------------ hull + quad

    private fun convexHull(points: IntArray, w: Int): List<Corner> {
        val distinct = points.map { Corner(it % w, it / w) }
            .distinct()
            .sortedWith(compareBy({ it.x }, { it.y }))
        if (distinct.size < CORNER_COUNT) return distinct
        // Monotone chain; `<= 0` drops collinear vertices, so the hull is
        // strictly convex and naturally compact.
        val lower = mutableListOf<Corner>()
        for (p in distinct) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0L) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = mutableListOf<Corner>()
        for (i in distinct.indices.reversed()) {
            val p = distinct[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0L) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        val hull = lower + upper
        return if (hull.size >= CORNER_COUNT) hull else distinct
    }

    private fun cross(o: Corner, a: Corner, b: Corner): Long =
        (a.x - o.x).toLong() * (b.y - o.y) - (a.y - o.y).toLong() * (b.x - o.x)

    private fun simplifyHull(hull: List<Corner>): List<Corner> {
        if (hull.size <= HULL_CAP) return hull
        // Keep the 8 directional extremes unconditionally (a quad's corners
        // dominate them), then stride-decimate the rest deterministically.
        val extremes = linkedSetOf(
            extreme(hull) { it.x + it.y },
            extreme(hull) { it.x - it.y },
            extreme(hull) { -(it.x + it.y) },
            extreme(hull) { -(it.x - it.y) },
            extreme(hull) { it.x },
            extreme(hull) { -it.x },
            extreme(hull) { it.y },
            extreme(hull) { -it.y },
        )
        val stride = ceil(hull.size / HULL_CAP.toDouble()).toInt().coerceAtLeast(1)
        return hull.filterIndexed { index, corner -> index % stride == 0 || corner in extremes }
    }

    private inline fun extreme(points: List<Corner>, selector: (Corner) -> Int): Corner {
        var best = points[0]
        var bestValue = selector(best)
        for (i in 1 until points.size) {
            val value = selector(points[i])
            if (value > bestValue) {
                bestValue = value
                best = points[i]
            }
        }
        return best
    }

    /**
     * Maximum-area convex quadrilateral over the simplified hull's cyclic
     * order (any 4 hull vertices in cyclic order already form a convex quad).
     */
    private fun largestInscribedQuad(hull: List<Corner>): List<Corner>? {
        val m = hull.size
        if (m < CORNER_COUNT) return null
        var bestA = hull[0]
        var bestB = hull[1]
        var bestC = hull[2]
        var bestD = hull[3]
        var bestArea = 0.0
        for (i in 0 until m - 3) {
            for (j in i + 1 until m - 2) {
                for (k in j + 1 until m - 1) {
                    for (l in k + 1 until m) {
                        val area = QuadGeometry.area(hull[i], hull[j], hull[k], hull[l])
                        if (area > bestArea) {
                            bestArea = area
                            bestA = hull[i]
                            bestB = hull[j]
                            bestC = hull[k]
                            bestD = hull[l]
                        }
                    }
                }
            }
        }
        if (bestArea <= 0.0) return null
        val candidate = listOf(bestA, bestB, bestC, bestD)
        if (minSideLength(candidate) < MIN_SIDE_WORK_PX) return null
        return candidate
    }

    private fun minSideLength(quad: List<Corner>): Double {
        var minimum = Double.MAX_VALUE
        for (i in 0 until CORNER_COUNT) {
            minimum = min(minimum, QuadGeometry.distance(quad[i], quad[(i + 1) % CORNER_COUNT]))
        }
        return minimum
    }

    // ------------------------------------------------------------------ full-resolution evidence

    /**
     * Normalized Sobel gradient magnitude at a full-resolution pixel, with
     * clamped reads at the frame border (a crisp step of contrast c measures
     * c/2 — the scale the BLUR/edge-support thresholds reason about).
     */
    private fun fullResGradient(frame: DetectorFrame, x: Int, y: Int): Double {
        val w = frame.width
        val h = frame.height
        val xm = (x - 1).coerceAtLeast(0)
        val xp = (x + 1).coerceAtMost(w - 1)
        val ym = (y - 1).coerceAtLeast(0)
        val yp = (y + 1).coerceAtMost(h - 1)
        val p = frame.luminance
        fun value(xx: Int, yy: Int): Double = (p[yy * w + xx].toInt() and 0xFF).toDouble()
        val gx = (
            -value(xm, ym) + value(xp, ym) +
                -2.0 * value(xm, y) + 2.0 * value(xp, y) +
                -value(xm, yp) + value(xp, yp)
            ) / 8.0
        val gy = (
            -value(xm, ym) + value(xm, yp) +
                -2.0 * value(x, ym) + 2.0 * value(x, yp) +
                -value(xp, ym) + value(xp, yp)
            ) / 8.0
        return sqrt(gx * gx + gy * gy)
    }

    /** Snaps a coarse corner to the strongest gradient in a small window. */
    private fun refineCorner(frame: DetectorFrame, coarse: Corner, separation: Double): Corner {
        if (separation < MIN_SEPARATION_FOR_REFINEMENT) return coarse
        val scale = max(frame.width.toDouble() / maxWorkDimension, 1.0)
        val radius = max(MIN_REFINE_RADIUS, (scale * REFINE_RADIUS_SCALE_FACTOR).roundToInt())
        val x0 = (coarse.x - radius).coerceAtLeast(0)
        val x1 = (coarse.x + radius).coerceAtMost(frame.width - 1)
        val y0 = (coarse.y - radius).coerceAtLeast(0)
        val y1 = (coarse.y + radius).coerceAtMost(frame.height - 1)
        var bestX = coarse.x
        var bestY = coarse.y
        var bestGradient = -1.0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val gradient = fullResGradient(frame, x, y)
                if (gradient > bestGradient) {
                    bestGradient = gradient
                    bestX = x
                    bestY = y
                }
            }
        }
        val acceptance = REFINE_ACCEPTANCE * 0.5 * separation
        return if (bestGradient >= acceptance) Corner(bestX, bestY) else coarse
    }

    private class PerimeterStats(val samples: Int, val supported: Int, val maxGradient: Double) {
        val supportRatio: Double
            get() = if (samples > 0) supported.toDouble() / samples else 0.0

        fun sharpness(separation: Double): Double =
            if (separation > 0) maxGradient / (0.5 * separation) else 0.0
    }

    private fun perimeterStats(frame: DetectorFrame, quad: List<Corner>, separation: Double): PerimeterStats {
        val supportThreshold = EDGE_SUPPORT_FRACTION * separation
        var samples = 0
        var supported = 0
        var maxGradient = 0.0
        for (edge in 0 until CORNER_COUNT) {
            val a = quad[edge]
            val b = quad[(edge + 1) % CORNER_COUNT]
            val length = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
            val steps = max(4, ceil(length / PERIMETER_STEP_PX).toInt())
            for (s in 0 until steps) {
                val t = s.toDouble() / steps
                val sx = (a.x + t * (b.x - a.x)).roundToInt().coerceIn(0, frame.width - 1)
                val sy = (a.y + t * (b.y - a.y)).roundToInt().coerceIn(0, frame.height - 1)
                val gradient = fullResGradient(frame, sx, sy)
                samples++
                if (gradient >= supportThreshold) supported++
                if (gradient > maxGradient) maxGradient = gradient
            }
        }
        return PerimeterStats(samples, supported, maxGradient)
    }

    // ------------------------------------------------------------------ confidence shape

    private fun quadRegularity(quad: List<Corner>): Double {
        if (quad.size != CORNER_COUNT) return 0.0
        var angleError = 0.0
        for (i in 0 until CORNER_COUNT) {
            val p0 = quad[(i + 3) % CORNER_COUNT]
            val p1 = quad[i]
            val p2 = quad[(i + 1) % CORNER_COUNT]
            val v1x = (p0.x - p1.x).toDouble()
            val v1y = (p0.y - p1.y).toDouble()
            val v2x = (p2.x - p1.x).toDouble()
            val v2y = (p2.y - p1.y).toDouble()
            val n1 = hypot(v1x, v1y)
            val n2 = hypot(v2x, v2y)
            if (n1 <= EPSILON || n2 <= EPSILON) return 0.0
            val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1.0, 1.0)
            val angleDegrees = acos(cos) * DEGREES_PER_RADIAN
            angleError += abs(angleDegrees - RIGHT_ANGLE_DEGREES)
        }
        angleError /= CORNER_COUNT
        val top = QuadGeometry.distance(quad[0], quad[1])
        val bottom = QuadGeometry.distance(quad[2], quad[3])
        val left = QuadGeometry.distance(quad[3], quad[0])
        val right = QuadGeometry.distance(quad[1], quad[2])
        fun mismatch(a: Double, b: Double): Double = abs(a - b) / (a + b)
        val sideMismatch = mismatch(top, bottom) + mismatch(left, right)
        val angleScore = (1.0 - angleError / ANGLE_ERROR_MAX_DEGREES).coerceIn(0.0, 1.0)
        val sideScore = (1.0 - 2.0 * sideMismatch).coerceIn(0.0, 1.0)
        return angleScore * sideScore
    }

    private fun areaSanity(areaFraction: Double): Double = when {
        areaFraction in MIN_SANE_AREA_FRACTION..MAX_SANE_AREA_FRACTION -> 1.0
        areaFraction < MIN_SANE_AREA_FRACTION -> (areaFraction / MIN_SANE_AREA_FRACTION).coerceIn(0.0, 1.0)
        else -> ((1.0 - areaFraction) / (1.0 - MAX_SANE_AREA_FRACTION)).coerceIn(0.0, 1.0)
    }

    private fun saturatedFraction(pixels: IntArray, total: Int): Double {
        var saturated = 0
        for (v in pixels) if (v >= glareLumaThreshold) saturated++
        return saturated.toDouble() / total
    }

    private companion object {
        private const val CORNER_COUNT = 4
        private const val LUMA_LEVELS = 256
        private const val EPSILON = 1e-9

        private const val MIN_FRAME_DIMENSION = 24
        private const val MIN_WORK_DIMENSION = 12
        private const val UNIFORM_STD_FLOOR = 1.0
        private const val MAX_COMPONENT_FRACTION = 0.85
        private const val HULL_CAP = 28
        private const val MIN_SIDE_WORK_PX = 3.0
        private const val MIN_SEPARATION_FOR_REFINEMENT = 8.0
        private const val MIN_SEPARATION_FOR_SHARPNESS = 8.0
        private const val MIN_REFINE_RADIUS = 6
        private const val REFINE_RADIUS_SCALE_FACTOR = 3.0
        private const val REFINE_ACCEPTANCE = 0.25
        private const val PERIMETER_STEP_PX = 2.0
        private const val EDGE_SUPPORT_FRACTION = 0.35

        private const val EDGE_SUPPORT_WEIGHT = 0.5
        private const val REGULARITY_WEIGHT = 0.3
        private const val AREA_SANITY_WEIGHT = 0.2

        private const val MIN_SANE_AREA_FRACTION = 0.05
        private const val MAX_SANE_AREA_FRACTION = 0.90

        private const val RIGHT_ANGLE_DEGREES = 90.0
        private const val ANGLE_ERROR_MAX_DEGREES = 40.0
        private const val DEGREES_PER_RADIAN = 180.0 / Math.PI

        private const val DEFAULT_MAX_WORK_DIMENSION = 192
        private const val DEFAULT_MIN_QUAD_AREA_FRACTION = 0.01
        private const val DEFAULT_LOW_CONTRAST_STD = 20.0
        private const val DEFAULT_LOW_CONTRAST_SEPARATION = 40.0
        private const val DEFAULT_GLARE_LUMA = 250
        private const val DEFAULT_GLARE_AREA_FRACTION = 0.015
        private const val DEFAULT_BLUR_SHARPNESS = 0.45
        private const val DEFAULT_BORDER_TOUCH_WORK_PX = 2
    }
}
