package org.payswap.camscan.ocr

/*
 * LEAD-STATION-ONLY — CAMSCAN-PROD-010 real-engine smoke test.
 * Target path: app/src/androidTest/java/org/payswap/camscan/ocr/
 * NOT part of the committed branch (androidTest is Worker 2's board path);
 * the lead plants and runs it at the PROD-014 integration station.
 *
 * Test 1 proves the bundled Latin model recognizes a Canvas-drawn fixed
 * string end-to-end through the PROD-009 seam (Success, non-empty fullText,
 * engineId echo, boxes in the expected page bands).
 *
 * Test 2 is the BOX-COORDINATE-SPACE PROBE for the mapper's documented
 * assumption: ML Kit boxes are assumed to come back in the PRE-ROTATION
 * input frame (the frame OcrImage.width/height describe). The probe draws
 * vertical text and recognizes it with rotationDegrees = 90: if boxes are
 * pre-rotation the union box is NARROW in x and TALL in y; a rotated frame
 * would transpose those. If this probe fails, the correction lands in ONE
 * documented place: MlKitMapper.normalizeBox.
 */

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.ocr.engine.OcrImage
import org.payswap.camscan.ocr.engine.OcrImageFormat
import org.payswap.camscan.ocr.engine.OcrOutcome
import org.payswap.camscan.ocr.engine.OcrSettings
import org.payswap.camscan.ocr.engine.OcrTextBlock
import org.payswap.camscan.ocr.engine.mlkit.MlKitEngineFactory
import org.payswap.camscan.ocr.engine.mlkit.MlKitOcrEngine

@RunWith(AndroidJUnit4::class)
class MlKitOcrEngineSmokeTest {

@Test
fun bundledLatinRecognizerRecognizesCanvasText() {
val width = 480
val height = 160
val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
val canvas = Canvas(bitmap)
canvas.drawColor(Color.WHITE)
val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
color = Color.BLACK
textSize = 56f
}
canvas.drawText("CAMSCAN SMOKE 123", 20f, 100f, paint)
val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
val image = OcrImage(
bytes = bytes,
width = width,
height = height,
format = OcrImageFormat.PNG,
rotationDegrees = 0,
)

val engine = MlKitEngineFactory.createOrNull(TimeSource.SYSTEM)
?: error("ML Kit recognizer client construction failed on this station")
val outcome = try {
runBlocking { engine.recognize(image, OcrSettings.DEFAULT) }
} finally {
engine.close()
}

assertTrue("expected Success, got " + outcome, outcome is OcrOutcome.Success)
val result = (outcome as OcrOutcome.Success).result
assertEquals("engineId echo", MlKitOcrEngine.ENGINE_ID, result.engineId)
assertTrue("fullText must be non-empty, got '" + result.fullText + "'", result.fullText.isNotBlank())
assertTrue(
"fullText should contain the drawn string tokens, got '" + result.fullText + "'",
result.fullText.uppercase().contains("CAMSCAN") && result.fullText.contains("123"),
)
assertTrue("recognisedAtMillis must be wired through TimeSource", result.recognisedAtMillis > 0L)
assertTrue("processingDurationMillis must be non-negative", result.processingDurationMillis >= 0L)

// Box-space sanity against the DECLARED dims (480x160): the text band is
// roughly x in [20, 440], y in [55, 115] -> generous bands that still catch
// a transposed or unnormalized coordinate space.
val union = unionBox(result.blocks)
assertTrue("union box must stay in the page", union.left >= 0f && union.top >= 0f && union.right <= 1f && union.bottom <= 1f)
assertTrue("union left band, got " + union, union.left < 0.35f)
assertTrue("union right band, got " + union, union.right > 0.6f)
assertTrue("union top band, got " + union, union.top > 0.05f && union.top < 0.6f)
assertTrue("union bottom band, got " + union, union.bottom > 0.3f && union.bottom < 0.95f)
}

@Test
fun boxCoordinateSpaceProbeRotatedFrame() {
val width = 480
val height = 640
val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
val canvas = Canvas(bitmap)
canvas.drawColor(Color.WHITE)
val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
color = Color.BLACK
textSize = 56f
}
// Vertical text (reads bottom-to-top on the bitmap): after ML Kit applies
// rotationDegrees = 90 clockwise, the text is upright for the recognizer.
canvas.save()
canvas.rotate(-90f, width / 2f, height / 2f)
canvas.drawText("CAMSCAN ROTATE", 20f, height / 2f + 18f, paint)
canvas.restore()
val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
val image = OcrImage(
bytes = bytes,
width = width,
height = height,
format = OcrImageFormat.PNG,
rotationDegrees = 90,
)

val engine = MlKitEngineFactory.createOrNull(TimeSource.SYSTEM)
?: error("ML Kit recognizer client construction failed on this station")
val outcome = try {
runBlocking { engine.recognize(image, OcrSettings.DEFAULT) }
} finally {
engine.close()
}

assertTrue("expected Success, got " + outcome, outcome is OcrOutcome.Success)
val result = (outcome as OcrOutcome.Success).result
if (result.blocks.isEmpty()) {
error(
"PROBE INCONCLUSIVE: recognizer returned zero blocks for the rotated input — " +
"re-run this probe manually before touching MlKitMapper"
)
}
assertTrue(
"rotated input should still read the drawn tokens, got '" + result.fullText + "'",
result.fullText.uppercase().contains("CAMSCAN"),
)

// The probe verdict: pre-rotation frame => union box NARROW in x, TALL in y.
val union = unionBox(result.blocks)
val horizontalExtent = union.right - union.left
val verticalExtent = union.bottom - union.top
assertTrue(
"BOX-SPACE ASSUMPTION VIOLATED: expected pre-rotation-frame boxes (narrow x, " +
"tall y) but got horizontal=" + horizontalExtent + " vertical=" + verticalExtent +
" — apply the correction in MlKitMapper.normalizeBox (the single documented " +
"correction point) and re-run",
horizontalExtent < 0.35f && verticalExtent > 0.5f,
)
}

/** Union (bounding) of block boxes in normalized page space. */
private fun unionBox(blocks: List<OcrTextBlock>): org.payswap.camscan.ocr.engine.OcrBox {
if (blocks.isEmpty()) {
return org.payswap.camscan.ocr.engine.OcrBox(0f, 0f, 0f, 0f)
}
return org.payswap.camscan.ocr.engine.OcrBox(
left = blocks.minOf { it.box.left },
top = blocks.minOf { it.box.top },
right = blocks.maxOf { it.box.right },
bottom = blocks.maxOf { it.box.bottom },
)
}
}
