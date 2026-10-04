package org.payswap.camscan.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// PageFitGeometryTest (CAMSCAN-VERIFY-001): fit-center math and the
// view<->page coordinate mapping used by every tool surface.

class PageFitGeometryTest {

    @Test
    fun fit_exactHeight_usesScaleOneWithHorizontalCentering() {
        val fit = PageFitGeometry.fit(100, 100, 50, 100)
        assertEquals(1f, fit.scale, 0.0001f)
        assertEquals(25f, fit.offsetX, 0.0001f)
        assertEquals(0f, fit.offsetY, 0.0001f)
    }

    @Test
    fun fit_tallView_doublesThePageWithoutLetterbox() {
        val fit = PageFitGeometry.fit(100, 200, 50, 100)
        assertEquals(2f, fit.scale, 0.0001f)
        assertEquals(0f, fit.offsetX, 0.0001f)
        assertEquals(0f, fit.offsetY, 0.0001f)
    }

    @Test
    fun fit_wideView_letterboxesWithHorizontalCentering() {
        val fit = PageFitGeometry.fit(200, 100, 50, 100)
        assertEquals(1f, fit.scale, 0.0001f)
        assertEquals(75f, fit.offsetX, 0.0001f)
        assertEquals(0f, fit.offsetY, 0.0001f)
    }

    @Test
    fun toPage_mapsThroughScaleAndOffset() {
        val fit = PageFitGeometry.fit(100, 100, 50, 100)
        val mapped = PageFitGeometry.toPage(60f, 50f, fit)
        assertEquals(35, mapped.x)
        assertEquals(50, mapped.y)
    }

    @Test
    fun degenerateDimensions_produceZeroScaleFit() {
        val fit = PageFitGeometry.fit(0, 100, 50, 100)
        assertEquals(0f, fit.scale, 0.0001f)
        val mapped = PageFitGeometry.toPage(10f, 10f, fit)
        assertEquals(0, mapped.x)
        assertEquals(0, mapped.y)
        assertNull(fit.takeIf { it.scale > 0f })
    }

    @Test
    fun toView_roundTripsThroughToPageForExactCells() {
        val fit = PageFitGeometry.fit(200, 200, 100, 100)
        val page = PageFitGeometry.toPage(137f, 91f, fit)
        val back = PageFitGeometry.toView(page.x, page.y, fit)
        assertTrue(Math.abs(back.x - 137) <= 1)
        assertTrue(Math.abs(back.y - 91) <= 1)
    }
}
