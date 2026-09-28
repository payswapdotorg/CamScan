package org.payswap.camscan.capture.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.payswap.camscan.R
import org.payswap.camscan.processing.CornerF
import org.payswap.camscan.processing.QuadF

/*
 * CAMSCAN-PROD-004 §6.5 — the crop-adjust interaction surface, Worker-1's
 * own touch logic (no new dependencies).
 *
 * Shows the processed page bitmap FIT-CENTER letterboxed inside the view,
 * with the current crop quad as an outline and four draggable corner
 * handles (>= 44dp effective touch radius). [currentQuadInBitmapSpace]
 * emits the adjusted quad in PROCESSED-pixel space (TL, TR, BR, BL order
 * preserved); the fragment then maps it back into source space through
 * the retained homography and re-runs the pipeline from the SOURCE
 * capture (non-destructive per the contract).
 *
 * Deterministic rendering: the fit transform is computed from the view
 * size and bitmap size only. Accessibility: performClick() is honored on
 * tap-up (the content description comes from the layout resource).
 */
class CropAdjustView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null

    /** Corner handle positions in VIEW coordinates: TL, TR, BR, BL (x, y pairs). */
    private val cornerPoints = FloatArray(CORNER_COUNT * 2)

    private var draggingIndex = -1
    private var dragMoved = false
    private var cornersInitialized = false

    private val density = resources.displayMetrics.density

    /** Visual handle radius (>= 11dp). */
    private val handleRadiusPx = max(11f * density, 14f)

    /** Effective touch radius: >= 44dp per the touch-target rule. */
    private val touchRadiusPx = 44f * density

    /** Handle inset from the view edges so handles never clip. */
    private val edgeInsetPx = handleRadiusPx + 2f * density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.review_crop_line)
        style = Paint.Style.STROKE
        strokeWidth = max(2f * density, 2f)
    }

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.review_crop_handle_fill)
        style = Paint.Style.FILL
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.review_crop_handle_stroke)
        style = Paint.Style.STROKE
        strokeWidth = max(2f * density, 2f)
    }

    private val quadPath = Path()
    private val dstRect = RectF()

    /** Emits the adjusted quad (processed-pixel space) — set by the fragment. */
    var onQuadApplied: ((QuadF) -> Unit)? = null

    // Fit transform: view = fit * bitmap + offset.
    private var fitScale = 1f
    private var fitLeft = 0f
    private var fitTop = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Sets the bitmap to adjust; resets the quad to the full page. */
    fun setBitmap(page: Bitmap) {
        bitmap = page
        cornersInitialized = false
        computeFit()
        if (width > 0 && height > 0) {
            resetCorners()
        }
        invalidate()
    }

    /** The adjusted quad in PROCESSED-pixel coordinates, or null without a bitmap. */
    fun currentQuadInBitmapSpace(): QuadF? {
        val page = bitmap ?: return null
        if (fitScale <= 0f) return null
        val maxX = (page.width - 1).toFloat()
        val maxY = (page.height - 1).toFloat()

        fun corner(index: Int): CornerF {
            val vx = cornerPoints[index * 2]
            val vy = cornerPoints[index * 2 + 1]
            val px = ((vx - fitLeft) / fitScale).coerceIn(0f, maxX)
            val py = ((vy - fitTop) / fitScale).coerceIn(0f, maxY)
            return CornerF(px, py)
        }

        return QuadF(corner(0), corner(1), corner(2), corner(3))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeFit()
        if (bitmap != null && !cornersInitialized && w > 0 && h > 0) {
            resetCorners()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = bitmap ?: return
        if (fitScale <= 0f) return

        dstRect.set(
            fitLeft,
            fitTop,
            fitLeft + page.width * fitScale,
            fitTop + page.height * fitScale,
        )
        canvas.drawBitmap(page, null, dstRect, bitmapPaint)

        quadPath.reset()
        quadPath.moveTo(cornerPoints[0], cornerPoints[1])
        quadPath.lineTo(cornerPoints[2], cornerPoints[3])
        quadPath.lineTo(cornerPoints[4], cornerPoints[5])
        quadPath.lineTo(cornerPoints[6], cornerPoints[7])
        quadPath.close()
        canvas.drawPath(quadPath, linePaint)

        for (i in 0 until CORNER_COUNT) {
            val cx = cornerPoints[i * 2]
            val cy = cornerPoints[i * 2 + 1]
            canvas.drawCircle(cx, cy, handleRadiusPx, handleFillPaint)
            canvas.drawCircle(cx, cy, handleRadiusPx, handleStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingIndex = nearestCorner(event.x, event.y)
                dragMoved = false
                return draggingIndex >= 0
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggingIndex >= 0) {
                    cornerPoints[draggingIndex * 2] = event.x.coerceIn(edgeInsetPx, width - edgeInsetPx)
                    cornerPoints[draggingIndex * 2 + 1] = event.y.coerceIn(edgeInsetPx, height - edgeInsetPx)
                    dragMoved = true
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingIndex >= 0 && !dragMoved) {
                    performClick()
                }
                draggingIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ------------------------------------------------------------------ internals

    private fun computeFit() {
        val page = bitmap
        if (page == null || width <= 0 || height <= 0 || page.width <= 0 || page.height <= 0) {
            fitScale = 1f
            fitLeft = 0f
            fitTop = 0f
            return
        }
        fitScale = min(width.toFloat() / page.width, height.toFloat() / page.height)
        fitLeft = (width - page.width * fitScale) / 2f
        fitTop = (height - page.height * fitScale) / 2f
    }

    /** Places the handles on the fit-rectangle corners, inset so they stay visible. */
    private fun resetCorners() {
        val page = bitmap ?: return
        val drawnWidth = page.width * fitScale
        val drawnHeight = page.height * fitScale
        val left = fitLeft + edgeInsetPx
        val top = fitTop + edgeInsetPx
        val right = fitLeft + drawnWidth - edgeInsetPx
        val bottom = fitTop + drawnHeight - edgeInsetPx

        // TL, TR, BR, BL — the order currentQuadInBitmapSpace relies on.
        cornerPoints[0] = left
        cornerPoints[1] = top
        cornerPoints[2] = right
        cornerPoints[3] = top
        cornerPoints[4] = right
        cornerPoints[5] = bottom
        cornerPoints[6] = left
        cornerPoints[7] = bottom
        cornersInitialized = true
    }

    /** Nearest corner within [touchRadiusPx], or -1 when none is reachable. */
    private fun nearestCorner(x: Float, y: Float): Int {
        var bestIndex = -1
        var bestDistance = touchRadiusPx
        for (i in 0 until CORNER_COUNT) {
            val dx = cornerPoints[i * 2] - x
            val dy = cornerPoints[i * 2 + 1] - y
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }
        return bestIndex
    }

    private companion object {
        const val CORNER_COUNT = 4
    }
}
