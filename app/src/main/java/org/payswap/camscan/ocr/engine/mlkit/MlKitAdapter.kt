package org.payswap.camscan.ocr.engine.mlkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.ocr.engine.OcrImage

/**
 * THE android-side adapter — the ONLY file in the OCR tree that imports
 * android.*, com.google.mlkit.*, or com.google.android.gms.* classes.
 * Everything it produces is JVM-clean: [MlKitTextDto] graphs and
 * [RecognizerError] values, consumed by [MlKitOcrEngine] and [MlKitMapper].
 *
 * API facts below were verified against the LANDED 16.0.1 dependency set
 * (artifact bytes in the local Gradle cache), not against memory or docs:
 * - `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` builds
 *   the bundled Latin recognizer (model ships inside the AAR assets —
 *   offline, no play-services wait at recognition time).
 * - `TextRecognizer.process(InputImage): Task<Text>`; the recognizer is
 *   Closeable and released via `close()`.
 * - `InputImage.fromBitmap(Bitmap, rotationDegrees)` accepts ONLY quarter-turn
 *   rotations (0/90/180/270) — the engine pre-flights this; the fold here is
 *   defensive.
 * - gms `Task` 18.2.0 has NO public `cancel()` and `process` has no
 *   `CancellationToken` overload, so the underlying task cannot be cancelled
 *   through the public API (see [MlKitTaskAdapter.cancel]).
 *
 * Threading: listeners are registered with a DIRECT executor — completion is
 * delivered on the task's completion thread, with no main-thread affinity.
 * This avoids the classic gms-Task/runBlocking deadlock (default listeners
 * post to the main looper) and keeps the engine free of Android threading.
 * The bitmap decode in [MlKitRecognizerHandle.process] runs synchronously on
 * the caller's coroutine thread — documented for the PROD-014 wiring point:
 * invoke recognize from a worker dispatcher for large pages.
 */
object MlKitEngineFactory {

    /**
     * Builds the live engine, or null when recognizer-client construction
     * fails (the catalog then falls back to UnavailableEngine — OCR never
     * blocks the scan path). Never throws.
     */
    fun createOrNull(timeSource: TimeSource): MlKitOcrEngine? = try {
        MlKitOcrEngine(MlKitRecognizerHandle(), timeSource)
    } catch (_: Exception) {
        null
    }
}

/**
 * [TextRecognizerHandle] implementation over the real ML Kit client.
 * Construction happens once, in the field initializer, so a construction
 * failure surfaces at [MlKitEngineFactory.createOrNull] time.
 */
class MlKitRecognizerHandle : TextRecognizerHandle {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Prepares the engine input from [image] and starts recognition.
     *
     * The ML Kit-supported path for ENCODED byte payloads (the seam carries
     * PNG/JPEG containers, not raw camera planes): decode the bytes to a
     * Bitmap, then `InputImage.fromBitmap` with the folded rotation. Any
     * decode or construction failure returns null (a VALUE), which the engine
     * maps to CORRUPT_IMAGE — most undecodable payloads are stopped even
     * earlier by the engine's magic-byte pre-flight; this is the second,
     * content-honest gate.
     */
    override fun process(image: OcrImage): RecognizerTask? {
        val bitmap: Bitmap? = try {
            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) return null
        val rotationDegrees = ((image.rotationDegrees % FULL_ROTATION_DEGREES) + FULL_ROTATION_DEGREES) % FULL_ROTATION_DEGREES
        val input = try {
            InputImage.fromBitmap(bitmap, rotationDegrees)
        } catch (_: Exception) {
            return null
        }
        return MlKitTaskAdapter(
            task = recognizer.process(input),
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
        )
    }

    /** Releases the client; never throws across the seam. */
    override fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {
            // Deliberately swallowed: the seam requires close() to never throw.
        }
    }

    private companion object {
        const val FULL_ROTATION_DEGREES = 360
    }
}

/**
 * [RecognizerTask] over a gms `Task<Text>`: registers listeners with a direct
 * executor (see the file KDoc) and converts the recognized [Text] into the
 * JVM-clean DTO graph. Exactly one listener fires per task.
 */
private class MlKitTaskAdapter(
    private val task: Task<Text>,
    private val frameWidth: Int,
    private val frameHeight: Int,
) : RecognizerTask {

    override fun listen(
        onSuccess: (MlKitTextDto?) -> Unit,
        onFailure: (RecognizerError) -> Unit,
    ) {
        task.addOnSuccessListener(DIRECT_EXECUTOR) { text ->
            onSuccess(text?.let { toTextDto(it, frameWidth, frameHeight) })
        }
        task.addOnFailureListener(DIRECT_EXECUTOR) { error ->
            onFailure(classifyFailure(error))
        }
    }

    /**
     * Verified no-op for the landed API (see file KDoc): the engine calls
     * this on coroutine cancellation, and the coroutine resumes as cancelled
     * regardless — any late task completion is discarded by the engine's
     * isActive guard. When a future ML Kit/tasks version exposes task
     * cancellation (Task.cancel() or a CancellationToken overload on
     * process), THIS method is the single wiring point.
     */
    override fun cancel() {
        // Intentionally empty — documented above.
    }

    private companion object {
        /** Direct executor: complete on the completing thread, no main-looper hop. */
        val DIRECT_EXECUTOR: Executor = Executor { it.run() }
    }
}

/**
 * Failure classification (conservative, documented, device-unverified — the
 * station smoke test and W2's androidTest refine it):
 * - `MlKitException` with INVALID_ARGUMENT (input rejected) and
 *   `IllegalArgumentException` (input-contract violation) read as
 *   image-decode/input-rejection class -> CORRUPT_IMAGE upstream;
 * - everything else -> [RecognizerError.Other] with the ML Kit message
 *   preserved verbatim (null message falls back to the exception class name,
 *   honestly).
 */
private fun classifyFailure(error: Exception): RecognizerError = when {
    error is MlKitException && error.errorCode == MlKitException.INVALID_ARGUMENT ->
        RecognizerError.ImageDecode
    error is IllegalArgumentException -> RecognizerError.ImageDecode
    else -> RecognizerError.Other(error.message ?: error::class.java.name)
}

/** Recognized Text -> DTO graph, reading order preserved, boxes verbatim. */
private fun toTextDto(text: Text, frameWidth: Int, frameHeight: Int): MlKitTextDto =
    MlKitTextDto(blocks = text.textBlocks.map { block -> toBlockDto(block, frameWidth, frameHeight) })

private fun toBlockDto(
    block: Text.TextBlock,
    frameWidth: Int,
    frameHeight: Int,
): MlKitBlockDto = MlKitBlockDto(
    text = block.text ?: "",
    box = toBoxDto(block.boundingBox, frameWidth, frameHeight),
    lines = block.lines.map { line -> toLineDto(line, frameWidth, frameHeight) },
)

private fun toLineDto(
    line: Text.Line,
    frameWidth: Int,
    frameHeight: Int,
): MlKitLineDto = MlKitLineDto(
    text = line.text ?: "",
    box = toBoxDto(line.boundingBox, frameWidth, frameHeight),
    // Primitive float from ML Kit's Java API: always present from THIS
    // adapter. The DTO's null models absence for future adapters/synthetic
    // inputs; whether ML Kit ever emits 0f as a "missing" sentinel is a
    // device-side open question — if proven, the sentinel->null mapping
    // lands HERE, in one documented place.
    confidence = line.confidence,
    elements = line.elements.map { element -> toElementDto(element, frameWidth, frameHeight) },
)

private fun toElementDto(
    element: Text.Element,
    frameWidth: Int,
    frameHeight: Int,
): MlKitElementDto = MlKitElementDto(
    text = element.text ?: "",
    box = toBoxDto(element.boundingBox, frameWidth, frameHeight),
)

/**
 * Rect -> pixel box DTO. ML Kit occasionally returns a null boundingBox;
 * rather than dropping recognized text, the adapter substitutes the FULL
 * DECODED frame (0,0,frameWidth,frameHeight) — after the mapper's
 * normalization+clamp that reads as the whole page: an explicit
 * "geometry unknown" marker, never a fabricated sub-region.
 */
private fun toBoxDto(rect: Rect?, frameWidth: Int, frameHeight: Int): MlKitBoxDto =
    if (rect == null) {
        MlKitBoxDto(left = 0, top = 0, right = frameWidth, bottom = frameHeight)
    } else {
        MlKitBoxDto(left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom)
    }
