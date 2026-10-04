package org.payswap.camscan.tools.ui

import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.Rect
import org.payswap.camscan.tools.signature.AnchorCorner
import org.payswap.camscan.tools.signature.SignaturePlacement

// SignatureApplyUi (CAMSCAN-VERIFY-001): pure draft state for the apply-
// signature surface. The frozen SignatureApplier places ink with a
// (anchor corner, width fraction, margin) vocabulary; drag-to-place maps a
// drop point onto the BEST reachable placement of that vocabulary, and
// previewRect mirrors the applier's documented clamping so the frame the
// user sees is where the engine paints. Android-free, JVM-testable.

/** Draft of one signature placement (size step + drag drop point). */
class SignatureApplyUi {

    private var fractionIndex: Int = DEFAULT_FRACTION_INDEX
    private var drop: Point? = null

    /** Current signature width as a fraction of the page width. */
    fun fraction(): Float = SIZE_FRACTIONS[fractionIndex]

    /** Steps the size up (larger); false when already the largest step. */
    fun stepSizeUp(): Boolean {
        if (fractionIndex >= SIZE_FRACTIONS.size - 1) return false
        fractionIndex += 1
        return true
    }

    /** Steps the size down (smaller); false when already the smallest step. */
    fun stepSizeDown(): Boolean {
        if (fractionIndex <= 0) return false
        fractionIndex -= 1
        return true
    }

    /** Records a drag drop point in full-resolution page coordinates. */
    fun dragTo(dropX: Int, dropY: Int) {
        drop = Point(dropX, dropY)
    }

    /** The latest drop point (page coordinates), or null before any drag. */
    fun lastDrop(): Point? = drop

    /**
     * The engine placement for the current draft. A recorded drop resolves
     * to the best reachable placement; no drop falls back to the default
     * anchor corner with the default margin.
     */
    fun placement(pageWidth: Int, pageHeight: Int, inkBoxWidth: Int, inkBoxHeight: Int): SignaturePlacement {
        val recorded = drop
        if (recorded == null) {
            return SignaturePlacement(
                DEFAULT_ANCHOR,
                fraction(),
                defaultMargin(pageWidth, pageHeight, inkBoxWidth, inkBoxHeight),
            )
        }
        return Companion.resolvePlacement(
            recorded.x, recorded.y, pageWidth, pageHeight, inkBoxWidth, inkBoxHeight,
        ).let {
            SignaturePlacement(it.anchor, fraction(), it.marginPx)
        }
    }

    companion object {
        /** Size ladder as fractions of the page width (ascending). */
        val SIZE_FRACTIONS: List<Float> = listOf(0.15f, 0.25f, 0.35f, 0.50f, 0.70f)

        const val DEFAULT_FRACTION_INDEX: Int = 2

        /** Placement before any drag: anchored bottom-right, 5% margin. */
        val DEFAULT_ANCHOR: AnchorCorner = AnchorCorner.BOTTOM_RIGHT

        const val DEFAULT_MARGIN_FRACTION: Float = 0.05f

        /**
         * Resolves a drop point to the engine placement vocabulary. The
         * anchor corner is the quadrant of the drop relative to the page
         * center; the margin is the least-squares best value such that the
         * anchored ink box lands nearest to centering itself on the drop
         * point, clamped into the range where the applier applies it
         * un-pulled. The size fraction is NOT chosen here (the caller keeps
         * its own ladder value).
         */
        fun resolvePlacement(
            dropX: Int,
            dropY: Int,
            pageWidth: Int,
            pageHeight: Int,
            inkBoxWidth: Int,
            inkBoxHeight: Int,
        ): SignaturePlacement {
            val clampedWidth = Math.min(inkBoxWidth, pageWidth)
            val clampedHeight = Math.min(inkBoxHeight, pageHeight)
            val anchor = if (dropX * 2 < pageWidth) {
                if (dropY * 2 < pageHeight) AnchorCorner.TOP_LEFT else AnchorCorner.BOTTOM_LEFT
            } else {
                if (dropY * 2 < pageHeight) AnchorCorner.TOP_RIGHT else AnchorCorner.BOTTOM_RIGHT
            }
            // Target top-left so the CLAMPED box centers on the drop point.
            val targetX = dropX - clampedWidth / 2
            val targetY = dropY - clampedHeight / 2
            // Per-corner least-squares optimum of the single shared margin.
            val raw = when (anchor) {
                AnchorCorner.TOP_LEFT -> (targetX + targetY) / 2.0
                AnchorCorner.TOP_RIGHT -> ((pageWidth - clampedWidth - targetX) + targetY) / 2.0
                AnchorCorner.BOTTOM_LEFT -> (targetX + (pageHeight - clampedHeight - targetY)) / 2.0
                AnchorCorner.BOTTOM_RIGHT ->
                    ((pageWidth - clampedWidth - targetX) + (pageHeight - clampedHeight - targetY)) / 2.0
            }
            val maxMargin = Math.max(
                0,
                Math.min(pageWidth - clampedWidth, pageHeight - clampedHeight),
            )
            val margin = Math.max(0, Math.min(Math.round(raw).toInt(), maxMargin))
            return SignaturePlacement(anchor, DEFAULT_RESOLVE_FRACTION, margin)
        }

        /**
         * Mirrors the SignatureApplier's documented destination math for
         * [placement] with an ink box of inkBoxWidth x inkBoxHeight: the
         * frame the preview should draw. Equal inputs produce the same
         * destination the applier computes.
         */
        fun previewRect(
            placement: SignaturePlacement,
            pageWidth: Int,
            pageHeight: Int,
            inkBoxWidth: Int,
            inkBoxHeight: Int,
        ): Rect {
            val clampedWidth = Math.min(inkBoxWidth, pageWidth)
            val clampedHeight = Math.min(inkBoxHeight, pageHeight)
            val destX: Int
            val destY: Int
            when (placement.anchor) {
                AnchorCorner.TOP_LEFT -> {
                    destX = Math.min(placement.marginPx, pageWidth - clampedWidth)
                    destY = Math.min(placement.marginPx, pageHeight - clampedHeight)
                }
                AnchorCorner.TOP_RIGHT -> {
                    destX = Math.max(0, pageWidth - placement.marginPx - clampedWidth)
                    destY = Math.min(placement.marginPx, pageHeight - clampedHeight)
                }
                AnchorCorner.BOTTOM_LEFT -> {
                    destX = Math.min(placement.marginPx, pageWidth - clampedWidth)
                    destY = Math.max(0, pageHeight - placement.marginPx - clampedHeight)
                }
                AnchorCorner.BOTTOM_RIGHT -> {
                    destX = Math.max(0, pageWidth - placement.marginPx - clampedWidth)
                    destY = Math.max(0, pageHeight - placement.marginPx - clampedHeight)
                }
            }
            return Rect(destX, destY, clampedWidth, clampedHeight)
        }

        /**
         * Size of the scaled ink box for a sketch bounding box at
         * [fraction] of the page width: (width, height), or null when the
         * sketch box is null (empty sketch).
         */
        fun inkBoxSize(
            sketchBoxWidth: Int,
            sketchBoxHeight: Int,
            pageWidth: Int,
            fraction: Float,
        ): Pair<Int, Int>? {
            if (sketchBoxWidth <= 0 || sketchBoxHeight <= 0 || pageWidth <= 0) return null
            val scaledWidth = Math.round(pageWidth * fraction.toDouble()).toInt()
            val scale = scaledWidth.toDouble() / Math.max(1, sketchBoxWidth)
            val scaledHeight = Math.round(sketchBoxHeight * scale).toInt()
            return Pair(Math.max(1, scaledWidth), Math.max(1, scaledHeight))
        }

        /** Default margin for the no-drag state, capped to the valid range. */
        fun defaultMargin(pageWidth: Int, pageHeight: Int, inkBoxWidth: Int, inkBoxHeight: Int): Int {
            val clampedWidth = Math.min(inkBoxWidth, pageWidth)
            val clampedHeight = Math.min(inkBoxHeight, pageHeight)
            val maxMargin = Math.max(
                0,
                Math.min(pageWidth - clampedWidth, pageHeight - clampedHeight),
            )
            return Math.max(0, Math.min(Math.round(pageWidth * DEFAULT_MARGIN_FRACTION.toDouble()).toInt(), maxMargin))
        }

        // resolvePlacement has no ladder context; the caller re-wraps the
        // result with its own fraction, so this constant is never observable.
        private const val DEFAULT_RESOLVE_FRACTION: Float = 1.0f
    }
}
