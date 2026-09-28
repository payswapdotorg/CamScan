package org.payswap.camscan.capture.detect

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.payswap.camscan.core.time.TimeSource
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


/**
 * CAMSCAN-PROD-002 §6.5 — the CameraX analysis glue.
 *
 * Thin by design: [analyze] extracts the luminance plane of a YUV_420_888
 * frame into a packed, upright [DetectorFrame], then all decisions run in the
 * pure components (EdgeQuadDetector, DetectionStabilizer). This class owns
 * no detection logic of its own beyond plumbing:
 *
 *  - **Single serial background executor** ([analysisExecutor]): the executor
 *    CameraX is told to invoke [analyze] on. Detection and stabilization run
 *    inside [analyze] on that executor, so at most one frame is ever being
 *    processed and frame ordering is the arrival order.
 *  - **Frame throttling**: at most one analysis per [analyzeIntervalMs]
 *    (default 250 ms, constructor-configurable); throttled frames are closed
 *    immediately and never touch the detector.
 *  - **Exactly-once ImageProxy close**: every path through [analyze] closes
 *    the proxy exactly once (a guarded close + try/finally; the proxy is
 *    closed *before* the heavy detection work so KEEP_ONLY_LATEST can drop
 *    stale frames instead of queueing them).
 *  - **Main-thread callback**: [onDetectionResult] fires on [mainExecutor]
 *    with the stabilizer output plus the frame geometry the overlay needs to
 *    map detection coordinates onto the preview.
 *  - **Upright frames**: the luminance plane is rotated by
 *    imageInfo.rotationDegrees (always a multiple of 90 in CameraX) before
 *    detection, so corners are in the same orientation the user sees on the
 *    preview.
 *  - **Determinism seam**: all timestamps come from [timeSource] — never
 *    System directly.
 *  - **Never throws into CameraX**: any unexpected failure degrades to "no
 *    detection this frame" with a diagnostic log.
 *
 * The analysis resolution (~640x480) and the KEEP_ONLY_LATEST backpressure
 * strategy are configured where the ImageAnalysis use case is built —
 * [org.payswap.camscan.capture.camera.CameraController.bindCamera], the
 * single place every bind goes through.
 */
class DetectionAnalyzer(
    private val detector: DocumentDetector,
    private val stabilizer: DetectionStabilizer,
    private val timeSource: TimeSource,
    private val mainExecutor: Executor,
    analyzeIntervalMs: Long = DEFAULT_ANALYZE_INTERVAL_MS,
) : ImageAnalysis.Analyzer {

    /**
     * The UI payload: the stabilizer output for the last analyzed frame
     * (`null` = no stable quad — the overlay should show its searching
     * state) plus the frame dimensions the corners are expressed in.
     */
    data class DetectionResult(
        val detection: StableDetection?,
        val frameWidth: Int,
        val frameHeight: Int,
    )

    /** Fired on [mainExecutor] after every analyzed (non-throttled) frame. */
    @Volatile
    var onDetectionResult: ((DetectionResult) -> Unit)? = null

    private val executorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }

    /**
     * The single serial background executor detection runs on. Handed to
     * CameraX together with this analyzer (setAnalyzer(executor, analyzer)).
     */
    val analysisExecutor: Executor = executorService

    private val analyzeIntervalMs: Long = analyzeIntervalMs.coerceAtLeast(0)

    private val shutdown = AtomicBoolean(false)

    /** Timestamp of the last frame actually analyzed (throttle reference). */
    private var lastAnalyzedAtMs = Long.MIN_VALUE

    override fun analyze(image: ImageProxy) {
        var closed = false

        fun closeOnce() {
            if (!closed) {
                closed = true
                image.close()
            }
        }

        try {
            if (shutdown.get()) {
                closeOnce()
                return
            }

            // -- throttle ---------------------------------------------------
            val now = timeSource.nowMillis()
            if (lastAnalyzedAtMs != Long.MIN_VALUE && now - lastAnalyzedAtMs < analyzeIntervalMs) {
                closeOnce()
                return
            }
            lastAnalyzedAtMs = now

            // -- YUV_420_888 luminance plane -> packed upright frame ---------
            val frame = extractUprightLuminance(image)
            closeOnce() // before the heavy work: never hold the proxy hostage
            if (frame == null) {
                return
            }

            // -- pure decisions (on this same serial executor) ----------------
            val detection = try {
                detector.detect(frame, now)
            } catch (t: Throwable) {
                Log.w(TAG, "detector threw on ${frame.width}x${frame.height} frame", t)
                null
            }
            val stable = try {
                stabilizer.update(detection)
            } catch (t: Throwable) {
                Log.w(TAG, "stabilizer threw; treating as lost detection", t)
                null
            }

            // -- main-thread notification -------------------------------------
            val result = DetectionResult(stable, frame.width, frame.height)
            val callback = onDetectionResult
            if (callback != null) {
                try {
                    mainExecutor.execute { callback(result) }
                } catch (t: Throwable) {
                    // A dead main executor (host torn down) must not crash the
                    // analysis thread.
                    Log.w(TAG, "failed to post detection result", t)
                }
            }
        } catch (t: Throwable) {
            // Defensive: nothing may propagate into CameraX's pipeline.
            Log.w(TAG, "unexpected failure analyzing frame", t)
        } finally {
            closeOnce()
        }
    }

    /**
     * Stops analysis permanently and releases the background thread. Frames
     * arriving after shutdown are closed immediately. Idempotent; the host
     * (ScanFragment) calls this when its view dies.
     */
    fun shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            executorService.shutdown()
        }
    }

    // ------------------------------------------------------------------ extraction

    /**
     * Extracts the Y plane into a packed [DetectorFrame] rotated upright by
     * [ImageProxy.imageInfo] rotation degrees. Returns null (never throws)
     * when the image is not a luminance-bearing YUV frame or the buffer is
     * too small for its declared geometry.
     */
    private fun extractUprightLuminance(image: ImageProxy): DetectorFrame? {
        if (image.planes.isEmpty()) return null
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride < width * pixelStride) return null
        val needed = rowStride * (height - 1) + pixelStride * (width - 1) + 1
        if (buffer.remaining() < needed) return null

        // -- packed (sensor orientation) -----------------------------------
        val packed = ByteArray(width * height)
        for (y in 0 until height) {
            val rowStart = y * rowStride
            val outRow = y * width
            if (pixelStride == 1) {
                for (x in 0 until width) {
                    packed[outRow + x] = buffer.get(rowStart + x)
                }
            } else {
                for (x in 0 until width) {
                    packed[outRow + x] = buffer.get(rowStart + x * pixelStride)
                }
            }
        }

        // -- rotate upright ---------------------------------------------------
        // CameraX guarantees rotationDegrees is a multiple of 90; anything
        // else is snapped to the nearest quadrant (defensive, logged).
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val quadrant = ((rotation + 45) / 90 * 90) % 360
        if (quadrant != rotation) {
            Log.w(TAG, "non-90-multiple rotationDegrees=$rotation snapped to $quadrant")
        }
        return when (quadrant) {
            0 -> DetectorFrame(width, height, packed)
            90 -> DetectorFrame(height, width, rotate90(packed, width, height))
            180 -> DetectorFrame(width, height, rotate180(packed, width, height))
            else -> DetectorFrame(height, width, rotate270(packed, width, height))
        }
    }

    /** Clockwise 90°: out[x', y'] where x' = H-1-y, y' = x (W'=H, H'=W). */
    private fun rotate90(packed: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(packed.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = packed[y * width + x]
                out[x * height + (height - 1 - y)] = v
            }
        }
        return out
    }

    private fun rotate180(packed: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(packed.size)
        val last = packed.size - 1
        for (i in packed.indices) {
            out[last - i] = packed[i]
        }
        return out
    }

    /** Clockwise 270°: out[x', y'] where x' = y, y' = W-1-x (W'=H, H'=W). */
    private fun rotate270(packed: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(packed.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = packed[y * width + x]
                out[(width - 1 - x) * height + y] = v
            }
        }
        return out
    }

    companion object {
        private const val TAG = "DetectionAnalyzer"
        private const val THREAD_NAME = "camscan-detection"

        /** §6.5 default frame throttle: one analysis per 250 ms. */
        const val DEFAULT_ANALYZE_INTERVAL_MS: Long = 250L
    }
}
