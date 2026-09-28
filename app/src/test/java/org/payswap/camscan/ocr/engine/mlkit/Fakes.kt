package org.payswap.camscan.ocr.engine.mlkit

import org.payswap.camscan.ocr.engine.OcrImage

/**
 * JVM test fakes for the [TextRecognizerHandle] / [RecognizerTask] seams —
 * zero ML Kit, zero android: recognition that completes / fails / hangs / is
 * rejected / misbehaves ON DEMAND, with every interaction recorded.
 */

/** Scripted outcome a [FakeRecognizerTask] delivers to its listeners. */
sealed interface FakeScript {
    /** Deliver a converted text DTO (null = the recognizer returned null Text). */
    data class Deliver(val text: MlKitTextDto?) : FakeScript

    /** Fail with a classified error. */
    data class Fail(val error: RecognizerError) : FakeScript

    /** Never complete — for cancellation tests. */
    data object Hang : FakeScript
}

/**
 * Fake recognizer task. When a [FakeScript.Deliver]/[FakeScript.Fail] script
 * is set BEFORE [listen], it is delivered synchronously inside [listen]
 * (mirroring an already-completed gms task on a direct executor); scripts set
 * later (or [complete]/[fail] calls) deliver asynchronously on demand.
 */
class FakeRecognizerTask(
    var script: FakeScript = FakeScript.Hang,
) : RecognizerTask {

    var listenCount: Int = 0
        private set
    var cancelCount: Int = 0
        private set

    private var onSuccess: ((MlKitTextDto?) -> Unit)? = null
    private var onFailure: ((RecognizerError) -> Unit)? = null
    private var delivered = false

    override fun listen(
        onSuccess: (MlKitTextDto?) -> Unit,
        onFailure: (RecognizerError) -> Unit,
    ) {
        listenCount++
        this.onSuccess = onSuccess
        this.onFailure = onFailure
        deliverScript()
    }

    override fun cancel() {
        cancelCount++
    }

    /** Manual (asynchronous) success delivery — the late-completion path. */
    fun complete(text: MlKitTextDto?) {
        delivered = true
        onSuccess?.invoke(text)
    }

    /** Manual (asynchronous) failure delivery — the late-completion path. */
    fun fail(error: RecognizerError) {
        delivered = true
        onFailure?.invoke(error)
    }

    private fun deliverScript() {
        if (delivered) return
        when (val current = script) {
            is FakeScript.Deliver -> {
                delivered = true
                onSuccess?.invoke(current.text)
            }
            is FakeScript.Fail -> {
                delivered = true
                onFailure?.invoke(current.error)
            }
            FakeScript.Hang -> Unit
        }
    }
}

/**
 * Fake recognizer handle: records every [process] call (so tests can prove
 * pre-flight failures NEVER reach the recognizer), returns the scripted task
 * (or null = input construction rejection, or an exception = misbehavior),
 * and counts [close] calls.
 */
class FakeRecognizerHandle(
    private val taskFactory: (OcrImage) -> RecognizerTask? = { null },
) : TextRecognizerHandle {

    var processCount: Int = 0
        private set
    var closeCount: Int = 0
        private set
    val processedImages = mutableListOf<OcrImage>()

    override fun process(image: OcrImage): RecognizerTask? {
        processCount++
        processedImages += image
        return taskFactory(image)
    }

    override fun close() {
        closeCount++
    }
}
