package org.payswap.camscan.processing


import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/*
 * CAMSCAN-PROD-003 §6.8 — instrumentation skeleton for the §6.5 adapter.
 *
 * Runs at the LEAD'S INTEGRATION STATION: this sandbox has no emulator,
 * so the round-trip is compile-verified here (assembled into the
 * androidTest APK) and executed where instrumentation is available.
 *
 * Fixture note: all pixels are OPAQUE (alpha 255) on purpose — ARGB_8888
 * premultiplied storage round-trips exact values for opaque pixels
 * (partial-alpha colors would go through premultiply/un-premultiply
 * rounding by the platform, which is outside this contract).
 */
@RunWith(AndroidJUnit4::class)
class BitmapAdapterRoundTripTest {

    @Test
    fun bitmapRoundTripPreservesDimensionsAndPixels() {
        val width = 8
        val height = 6
        val argb = IntArray(width * height) { i ->
            (0xFF shl 24) or
                (((i * 37) % 256) shl 16) or
                (((i * 11) % 256) shl 8) or
                ((i * 5) % 256)
        }
        val buffer = ImageBuffer(width, height, argb)

        val bitmap: Bitmap = BitmapImageAdapter.toBitmap(buffer)
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)

        val back = BitmapImageAdapter.toImageBuffer(bitmap)
        assertEquals(width, back.width)
        assertEquals(height, back.height)
        assertArrayEquals("Bitmap -> ImageBuffer -> Bitmap must be lossless", argb, back.argb)

        // Explicit spot checks for failure diagnostics (redundant with the
        // array assertion).
        assertEquals(argb[0], back.argb[0])
        assertEquals(argb[width - 1], back.argb[width - 1])
        assertEquals(argb[width * height - 1], back.argb[width * height - 1])
    }
}
