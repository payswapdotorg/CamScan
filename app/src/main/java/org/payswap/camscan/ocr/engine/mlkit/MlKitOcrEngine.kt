package org.payswap.camscan.ocr.engine.mlkit

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.ocr.engine.OcrEngine
import org.payswap.camscan.ocr.engine.OcrFailure
import org.payswap.camscan.ocr.engine.OcrImage
import org.payswap.camscan.ocr.engine.OcrImageFormat
import org.payswap.camscan.ocr.engine.OcrOutcome
import org.payswap.camscan.ocr.engine.OcrSettings

/**
 * The live OCR engine binding: Google ML Kit on-device text recognition
 * (bundled Latin model, `com.google.mlkit:text-recognition:16.0.1`) behind
 * the frozen PROD-009 [OcrEngine] seam.
 *
 * This file is deliberately JVM-clean (no ML Kit, no gms-tasks, no android
 * imports) so the engine's control flow is unit-testable on the JVM. All
 * contact with the real recognizer happens behind the [TextRecognizerHandle]
 * seam; the android-side implementation (and the ONLY file importing the live
 * ML Kit classes) is `MlKitAdapter.kt`.
 *
 * Coroutine bridge: recognize awaits the recognizer task via
 * [suspendCancellableCoroutine]. The cancellation handler invokes
 * [RecognizerTask.cancel] — the single wiring point for cancelling the
 * underlying ML Kit task (verified against the landed 16.0.1 dependency set:
 * gms `Task` 18.2.0 exposes no public `cancel()` and `TextRecognizer.process`
 * has no `CancellationToken` overload, so the adapter's implementation is a
 * documented no-op for now; the coroutine itself still cancels promptly and
 * any late task result is discarded, so no coroutine is ever leaked waiting).
 *
 * Documented failure taxonomy (values, never exceptions, per the seam
 * contract — `MlKitAdapter` and the tests mirror this table):
 *
 * - empty bytes OR non-positive width/height -> [OcrFailure.EMPTY_IMAGE]
 *   (this order's packet maps BOTH to EMPTY_IMAGE; note the PROD-009 stub
 *   maps non-positive dims to CORRUPT_IMAGE instead — engine-specific
 *   pre-flight, reported as an open question, not a seam change)
 * - declared format UNKNOWN -> [OcrFailure.UNSUPPORTED_FORMAT]
 *   (rejected outright, no magic-byte sniffing; the stub sniffs first via
 *   `OcrImage.normalized()` — another documented, deliberate divergence)
 * - declared PNG/JPEG whose magic bytes do not match -> [OcrFailure.CORRUPT_IMAGE]
 * - rotationDegrees not a multiple of 90 -> [OcrFailure.ENGINE_ERROR]
 *   (ML Kit `InputImage.fromBitmap` only accepts 0/90/180/270 — verified
 *   against the landed artifact; the seam accepts any integer, so an
 *   engine-specific limitation is reported honestly rather than silently
 *   quantized)
 * - input construction/decode failure (payload not decodable as declared)
 *   -> [OcrFailure.CORRUPT_IMAGE]
 * - task failure classified image-decode-class by the adapter
 *   -> [OcrFailure.CORRUPT_IMAGE]
 * - recognizer delivers null Text -> [OcrFailure.CORRUPT_IMAGE]
 * - every other task failure -> [OcrFailure.ENGINE_ERROR] with the ML Kit
 *   message preserved verbatim
 * - unexpected internal exception -> [OcrFailure.ENGINE_ERROR] (recognize
 *   never throws; `CancellationException` is rethrown, mirroring the harness)
 * - recognize after close() -> [OcrFailure.CLOSED], never throws
 * - task success with ZERO blocks -> [OcrOutcome.Success] with empty blocks
 *   and empty fullText (an honest blank page, not a failure)
 *
 * Lifecycle: [close] releases the recognizer client exactly once (idempotent,
 * double-close safe, never throws). A recognize already in flight when close
 * happens is allowed to complete under its own power; new recognizes report
 * CLOSED. Time enters ONLY through the injected [TimeSource];
 * `recognisedAtMillis` is stamped once, after pre-flight, at the request
 * instant (mirroring the stub's post-pre-flight stamp), and
 * `processingDurationMillis` is measured as TimeSource deltas around input
 * construction plus the await (with a fixed/stepping fake clock the value is
 * fully deterministic; a real clock reports measured time).
 */
class MlKitOcrEngine(
    private val handle: TextRecognizerHandle,
    private val timeSource: TimeSource,
) : OcrEngine {

    override val engineId: String = ENGINE_ID

    private val closed = AtomicBoolean(false)

    override suspend fun recognize(image: OcrImage, settings: OcrSettings): OcrOutcome {
        if (closed.get()) return OcrOutcome.Failure(OcrFailure.CLOSED)

        // ---- pre-flight: degenerate inputs never reach the recognizer ----
        if (image.bytes.isEmpty() || image.width <= 0 || image.height <= 0) {
            return OcrOutcome.Failure(OcrFailure.EMPTY_IMAGE)
        }
        if (image.format == OcrImageFormat.UNKNOWN) {
            return OcrOutcome.Failure(OcrFailure.UNSUPPORTED_FORMAT)
        }
        if (!OcrImage.matchesDeclaredFormat(image.format, image.bytes)) {
            return OcrOutcome.Failure(OcrFailure.CORRUPT_IMAGE)
        }
        if (image.rotationDegrees % ROTATION_STEP_DEGREES != 0) {
            return OcrOutcome.Failure(
                OcrFailure.ENGINE_ERROR(
                    "rotationDegrees must be a multiple of $ROTATION_STEP_DEGREES for engine " +
                        "$ENGINE_ID (got ${image.rotationDegrees})"
                )
            )
        }

        // Failures are values: the seam contract forbids throwing, so internal
        // misbehavior is converted — CancellationException keeps flowing.
        return try {
            recognizeValidInput(image, settings)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            OcrOutcome.Failure(
                OcrFailure.ENGINE_ERROR(
                    "engine threw ${failure::class.java.name}: ${failure.message ?: "<no message>"}"
                )
            )
        }
    }

    /**
     * Recognition of a pre-flight-validated image: start the task, await it
     * cancellably, map the outcome through the pure [MlKitMapper].
     */
    private suspend fun recognizeValidInput(image: OcrImage, settings: OcrSettings): OcrOutcome {
        val startedAtMillis = timeSource.nowMillis()
        val task = handle.process(image)
            ?: return OcrOutcome.Failure(OcrFailure.CORRUPT_IMAGE)
        val value = awaitTask(task)
        val endedAtMillis = timeSource.nowMillis()
        return when (value) {
            is RecognizerValue.Recognized -> {
                val text = value.text
                if (text == null) {
                    OcrOutcome.Failure(OcrFailure.CORRUPT_IMAGE)
                } else {
                    OcrOutcome.Success(
                        MlKitMapper.toResult(
                            text = text,
                            imageWidth = image.width,
                            imageHeight = image.height,
                            engineId = engineId,
                            settingsEcho = settings,
                            recognisedAtMillis = startedAtMillis,
                            processingDurationMillis = endedAtMillis - startedAtMillis,
                        )
                    )
                }
            }
            is RecognizerValue.Failed -> when (val error = value.error) {
                is RecognizerError.ImageDecode -> OcrOutcome.Failure(OcrFailure.CORRUPT_IMAGE)
                is RecognizerError.Other -> OcrOutcome.Failure(OcrFailure.ENGINE_ERROR(error.message))
            }
        }
    }

    /**
     * Task-to-coroutine bridge. The cancellation handler is registered BEFORE
     * the listeners so that a task which completes synchronously inside
     * [RecognizerTask.listen] resumes the coroutine normally, and a
     * cancellation that races completion finds the handler already installed.
     * Late deliveries after cancellation are dropped via the isActive guard —
     * the coroutine has already resumed as cancelled.
     */
    private suspend fun awaitTask(task: RecognizerTask): RecognizerValue =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { task.cancel() }
            task.listen(
                onSuccess = { text ->
                    if (continuation.isActive) {
                        continuation.resume(RecognizerValue.Recognized(text), null)
                    }
                },
                onFailure = { error ->
                    if (continuation.isActive) {
                        continuation.resume(RecognizerValue.Failed(error), null)
                    }
                },
            )
        }

    /**
     * Idempotent: releases the recognizer client exactly once. Never throws —
     * the adapter's close already swallows release errors; this guard makes
     * the guarantee unconditional for the seam.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            handle.close()
        } catch (_: Exception) {
            // Deliberately swallowed: close() must never throw across the seam.
        }
    }

    companion object {
        /** Stable engine identity — the bundled ML Kit v2 Latin recognizer. */
        const val ENGINE_ID: String = "mlkit-text-v2-latin"

        /** InputImage.fromBitmap accepts only quarter-turn rotations. */
        private const val ROTATION_STEP_DEGREES = 90
    }
}

/**
 * JVM-clean seam over the ML Kit recognizer client (construction, input
 * preparation, task production, release). The android-side implementation is
 * `MlKitRecognizerHandle` in `MlKitAdapter.kt`; tests substitute fakes.
 *
 * `process` returns null (not an exception) when [image] cannot be turned
 * into an engine input — payload not decodable, input construction rejected —
 * which the engine maps to [OcrFailure.CORRUPT_IMAGE].
 */
interface TextRecognizerHandle {
    /**
     * Starts recognition of [image]. Returns the awaitable task, or null when
     * the input could not be constructed (decode/construction failure).
     */
    fun process(image: OcrImage): RecognizerTask?

    /** Releases the client. Called at most once by the engine. */
    fun close()
}

/**
 * JVM-clean abstraction over one in-flight recognition (the shape of a gms
 * `Task<Text>` minus the gms types). Exactly one of the two callbacks fires,
 * at most once per listen.
 */
interface RecognizerTask {
    /**
     * Registers completion callbacks. Implementations may deliver
     * synchronously inside this call when the underlying work is already
     * complete. [onSuccess] receives the converted DTO graph, or null when
     * the recognizer delivered a null Text (the engine maps that to
     * [OcrFailure.CORRUPT_IMAGE]).
     */
    fun listen(onSuccess: (MlKitTextDto?) -> Unit, onFailure: (RecognizerError) -> Unit)

    /**
     * Requests cancellation of the underlying work. Invoked by the engine's
     * coroutine-cancellation handler; see [MlKitOcrEngine] for the documented
     * state of ML Kit's cancellation support.
     */
    fun cancel()
}

/**
 * Failure classification produced by the adapter: the android side knows the
 * ML Kit exception classes, so it sorts failures into these two honest
 * buckets and the engine maps them without any ML Kit knowledge.
 */
sealed interface RecognizerError {
    /** Failure classified as image-decode/input-rejection class. */
    data object ImageDecode : RecognizerError

    /** Any other failure; [message] is preserved verbatim from ML Kit. */
    data class Other(val message: String) : RecognizerError
}

/** One delivered task outcome inside the bridge. */
private sealed interface RecognizerValue {
    data class Recognized(val text: MlKitTextDto?) : RecognizerValue

    data class Failed(val error: RecognizerError) : RecognizerValue
}
