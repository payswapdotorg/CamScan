package org.payswap.camscan.export.longimage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import org.payswap.camscan.tools.exportops.LongImagePlan
import org.payswap.camscan.tools.exportops.LongImagePlacement
import org.payswap.camscan.tools.exportops.SeamPolicy

// CAMSCAN-VERIFY-002 — the android.graphics strip applier. Geometry is
// 100% the planner's (placements, offsets, scaled sizes, seam bands);
// this class only rasterizes that plan. Rotation: a page's right-angle
// rotation is folded to 0..359 and applied via Matrix when the page is
// NOT 0 (the engine already swapped the planned width/height for 90/270
// so the placement rect matches the rotated shape). Separator bands
// under SeamPolicy.Separator are painted with the strip's background —
// the planner defines the band GEOMETRY, not its content (documented).
// Pixel budget: targetWidth * totalHeight is capped at 64 megapixels
// (an honest guard against OOM on pathological documents); exceeding it
// returns null. PNG encoding via Bitmap.compress.

/** android.graphics [StripRasterizer] for the long-image export flow. */
class AndroidStripRasterizer : StripRasterizer {

    override fun render(
        plan: LongImagePlan,
        pages: List<LongImagePageBytes>,
    ): ByteArray? {
        if (plan.targetWidthPx <= 0 || plan.totalHeightPx <= 0) return null
        val totalPixels = plan.targetWidthPx.toLong() * plan.totalHeightPx.toLong()
        if (totalPixels > MAX_TOTAL_PIXELS) return null
        val bytesById = HashMap<String, LongImagePageBytes>(pages.size)
        for (page in pages) {
            bytesById[page.pageId] = page
        }
        val strip = Bitmap.createBitmap(
            plan.targetWidthPx,
            plan.totalHeightPx,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(strip)
        canvas.drawColor(BACKGROUND_COLOR)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (placement in plan.placements) {
            val pageBytes = bytesById[placement.pageId] ?: return null
            val decoded = decodeRotated(pageBytes) ?: return null
            val destination = RectF(
                placement.xOffsetPx.toFloat(),
                placement.yOffsetPx.toFloat(),
                (placement.xOffsetPx + placement.scaledWidthPx).toFloat(),
                (placement.yOffsetPx + placement.scaledHeightPx).toFloat(),
            )
            canvas.drawBitmap(decoded, null, destination, paint)
            decoded.recycle()
        }
        paintSeparatorBands(canvas, plan)
        val output = ByteArrayOutputStream()
        val encoded = strip.compress(Bitmap.CompressFormat.PNG, 100, output)
        strip.recycle()
        if (!encoded) return null
        return output.toByteArray()
    }

    /** Decodes the stored bytes and applies the folded right-angle rotation. */
    private fun decodeRotated(page: LongImagePageBytes): Bitmap? {
        val base = BitmapFactory.decodeByteArray(page.bytes, 0, page.bytes.size) ?: return null
        val rotation = ((page.rotationDegrees % 360) + 360) % 360
        if (rotation == 0) {
            return base
        }
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        val rotated = Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
        if (rotated !== base) {
            base.recycle()
        }
        return rotated
    }

    /** Separator bands are painted as background-colored gaps (documented). */
    private fun paintSeparatorBands(canvas: Canvas, plan: LongImagePlan) {
        if (plan.seamPolicy !is SeamPolicy.Separator) return
        val bandHeight = plan.seamPolicy.heightPx
        val paint = Paint()
        paint.color = BACKGROUND_COLOR
        var previous: LongImagePlacement? = null
        for (placement in plan.placements) {
            val before = previous
            if (before != null) {
                val top = before.yOffsetPx + before.scaledHeightPx
                canvas.drawRect(
                    0f,
                    top.toFloat(),
                    plan.targetWidthPx.toFloat(),
                    (top + bandHeight).toFloat(),
                    paint,
                )
            }
            previous = placement
        }
    }

    companion object {
        /** White strip background (scanned pages live on paper white). */
        const val BACKGROUND_COLOR = 0xFFFFFFFF.toInt()

        /** 64 megapixel budget (ARGB_8888 would need ~256 MB beyond it). */
        const val MAX_TOTAL_PIXELS = 64_000_000L
    }
}
