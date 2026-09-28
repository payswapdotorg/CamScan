package org.payswap.camscan.capture.detect

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.payswap.camscan.R


/**
 * CAMSCAN-PROD-002 §6.6 — the live document framing overlay.
 *
 * Draws on top of the preview:
 *  - **stable/warning**: the stable quad's polygon border plus a marker at
 *    each of the four corners (fill at ~10% alpha so the page underneath
 *    stays visible);
 *  - **searching**: a subtle dashed inset guide rectangle showing where to
 *    place the document.
 *
 * Coloring is three-state (stable / searching / warning — colors
 * scan_framing_stable / scan_framing_searching / scan_framing_warning in
 * values/colors_capture.xml). The warning state is evidence-based: a stable
 * detection carrying any quality flag (NO_PAGE, PARTIAL_PAGE, BLUR, GLARE,
 * LOW_CONTRAST, MOTION_UNSTABLE) colors the quad as a warning instead of
 * stable — it is a *display* state only and never gates capture.
 *
 * Coordinate mapping: detection corners are in analysis-frame pixels; the
 * preview fills this view FILL_CENTER-style (uniform scale = max of the two
 * axis ratios, centered) — the same fit PreviewView applies, so the overlay
 * lands on the on-screen page. The frame geometry arrives with every
 * [show] result ([DetectionAnalyzer.DetectionResult]).
 *
 * Pure presentation: no state machine, no callbacks, never throws —
 * malformed input (wrong corner count) simply draws the searching guide.
 */
class FramingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Presentation state — derived from the latest [show] input. */
    enum class FramingState { SEARCHING, STABLE, WARNING }

    private var state = FramingState.SEARCHING
    private var corners: List<Corner> = emptyList()
    private var frameWidth = 0
    private var frameHeight = 0

    private val density = resources.displayMetrics.density
    private val strokePx = STROKE_DP * density
    private val cornerRadiusPx = CORNER_MARKER_DP * density
    private val guideInsetFraction = GUIDE_INSET_FRACTION

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val quadPath = Path()

    /**
     * Renders one stabilizer result. `detection == null` switches to the
     * searching guide; a flagged detection renders the warning coloring.
     */
    fun show(detection: StableDetection?, frameWidth: Int, frameHeight: Int) {
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        if (detection == null || detection.corners.size != CORNER_COUNT || frameWidth <= 0 || frameHeight <= 0) {
            state = FramingState.SEARCHING
            corners = emptyList()
        } else {
            corners = detection.corners
            state = if (detection.qualityFlags.isEmpty()) FramingState.STABLE else FramingState.WARNING
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (state) {
            FramingState.SEARCHING -> drawSearchGuide(canvas)
            FramingState.STABLE, FramingState.WARNING -> drawQuad(canvas)
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun drawSearchGuide(canvas: Canvas) {
        val color = colorOf(R.color.scan_framing_searching)
        val inset = minOf(width, height) * guideInsetFraction
        if (inset <= 0f || width <= 0 || height <= 0) return
        val guide = RectF(inset, inset, width - inset, height - inset)
        strokePaint.color = color
        strokePaint.alpha = SEARCHING_STROKE_ALPHA
        strokePaint.pathEffect = DashPathEffect(floatArrayOf(dashOn(), dashOff()), 0f)
        canvas.drawRect(guide, strokePaint)
        strokePaint.pathEffect = null
    }

    private fun drawQuad(canvas: Canvas) {
        if (width <= 0 || height <= 0 || frameWidth <= 0 || frameHeight <= 0) return
        val mapped = mapToView()
        if (mapped.size != CORNER_COUNT) return
        val color = colorOf(
            when (state) {
                FramingState.WARNING -> R.color.scan_framing_warning
                else -> R.color.scan_framing_stable
            },
        )

        fillPaint.color = color
        fillPaint.alpha = FILL_ALPHA
        quadPath.reset()
        quadPath.moveTo(mapped[0].x, mapped[0].y)
        for (i in 1 until CORNER_COUNT) {
            quadPath.lineTo(mapped[i].x, mapped[i].y)
        }
        quadPath.close()
        canvas.drawPath(quadPath, fillPaint)

        strokePaint.color = color
        strokePaint.alpha = STABLE_STROKE_ALPHA
        strokePaint.pathEffect = CornerPathEffect(cornerRadiusPx * 0.5f)
        canvas.drawPath(quadPath, strokePaint)
        strokePaint.pathEffect = null

        cornerPaint.color = color
        cornerPaint.alpha = CORNER_ALPHA
        for (point in mapped) {
            canvas.drawCircle(point.x, point.y, cornerRadiusPx, cornerPaint)
        }
    }

    /** FILL_CENTER mapping of analysis-frame pixels into view pixels. */
    private fun mapToView(): List<Point> {
        val scale = maxOf(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val dx = (width - frameWidth * scale) / 2f
        val dy = (height - frameHeight * scale) / 2f
        return corners.map { Point(dx + it.x * scale, dy + it.y * scale) }
    }

    private fun colorOf(colorRes: Int): Int = androidx.core.content.ContextCompat.getColor(context, colorRes)

    private fun dashOn(): Float = 6f * density
    private fun dashOff(): Float = 6f * density

    private data class Point(val x: Float, val y: Float)

    companion object {
        private const val CORNER_COUNT = 4

        /** Quad stroke width. */
        private const val STROKE_DP = 3f

        /** Corner marker radius. */
        private const val CORNER_MARKER_DP = 6f

        /** Inset of the searching guide rectangle (fraction of min dimension). */
        private const val GUIDE_INSET_FRACTION = 0.08f

        /** Stroke alpha of the stable/warning quad (preview stays visible). */
        private const val STABLE_STROKE_ALPHA = 230

        /** Fill alpha of the stable/warning quad (~10%). */
        private const val FILL_ALPHA = 26

        /** Corner marker alpha. */
        private const val CORNER_ALPHA = 255

        /** Stroke alpha of the dashed searching guide (subtle). */
        private const val SEARCHING_STROKE_ALPHA = 90
    }
}
