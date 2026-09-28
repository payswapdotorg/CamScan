package org.payswap.camscan.ocr.engine

/**

CamScan OCR seam — the stable interface every OCR engine (deterministic stub,
unavailable placeholder, or a future real on-device engine such as ML Kit /
Tesseract) adapts to. Pure Kotlin: no Android imports, no UI, no I/O side
effects, no third-party dependencies beyond the Kotlin stdlib and
kotlinx-coroutines (for the suspend seam).
Contract rules (binding for every implementation):
Failures are values, not exceptions: expected bad inputs (empty images,
corrupt payloads, unsupported formats, closed engines) are reported as
[OcrOutcome.Failure]. Throwing from [recognize] is a contract violation.
Time enters ONLY through an injected TimeSource constructor parameter
(org.payswap.camscan.core.time.TimeSource); the timestamp travels ON the
result ([OcrResult.recognisedAtMillis]). Engines never read the clock.
[OcrEngine.close] is idempotent; after close, recognize reports
[OcrFailure.CLOSED] instead of throwing or pretending to work.
Deterministic engines must produce byte-identical
[OcrResult.toStableString] output for identical inputs.

*/

/** Container/payload format of an [OcrImage]. */
enum class OcrImageFormat { PNG, JPEG, UNKNOWN }

/** Requested speed/quality posture for a recognition run. */
enum class OcrMode { FAST, BALANCED, ACCURATE }

/**

A page image handed to an [OcrEngine]. A value description of the input —
engines receive bytes plus metadata and own their own decoding. [bytes]
participates in equals/hashCode by CONTENT so structurally identical images
compare equal.
@property bytes raw encoded image payload (may be empty; the deterministic
stub maps that to [OcrFailure.EMPTY_IMAGE]).
@property width declared pixel width; non-positive values are degenerate.
@property height declared pixel height; non-positive values are degenerate.
@property format declared container format; [OcrImageFormat.UNKNOWN] asks
engines to sniff or reject (the stub rejects un-sniffable UNKNOWN with
[OcrFailure.UNSUPPORTED_FORMAT]).
@property rotationDegrees clockwise display rotation; any integer accepted,
[normalized] folds it into 0..359.
/
data class OcrImage(
val bytes: ByteArray,
val width: Int,
val height: Int,
val format: OcrImageFormat = OcrImageFormat.UNKNOWN,
val rotationDegrees: Int = 0,
) {
/* True when the input cannot possibly carry a recognizable image. */
val isDegenerate: Boolean
get() = bytes.isEmpty() || width <= 0 || height <= 0
/**
* Deterministic canonical form: rotation folded into 0..359 and the format
* sniffed from magic bytes when (and only when) it is UNKNOWN and a known
* signature is present. Returns `this` when already canonical.
*/
fun normalized(): OcrImage {
val foldedRotation = ((rotationDegrees % 360) + 360) % 360
val effectiveFormat =
if (format == OcrImageFormat.UNKNOWN) sniffFormat(bytes) ?: OcrImageFormat.UNKNOWN else format
return if (foldedRotation == rotationDegrees && effectiveFormat == format) {
this
} else {
copy(rotationDegrees = foldedRotation, format = effectiveFormat)
}
}

override fun equals(other: Any?): Boolean {
if (this === other) return true
if (other !is OcrImage) return false
return bytes.contentEquals(other.bytes) &&
width == other.width &&
height == other.height &&
format == other.format &&
rotationDegrees == other.rotationDegrees
}

override fun hashCode(): Int {
var result = bytes.contentHashCode()
result = 31 * result + width
result = 31 * result + height
result = 31 * result + format.hashCode()
result = 31 * result + rotationDegrees
return result
}

companion object {
/** PNG magic bytes (public: synthetic-image generation reuses them). */
val PNG_SIGNATURE: ByteArray =
byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** JPEG magic bytes (public: synthetic-image generation reuses them). */
val JPEG_SIGNATURE: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

/**
* Deterministic magic-byte check against the DECLARED format. UNKNOWN
* declares nothing and therefore always "matches"; engines that cannot
* handle UNKNOWN must reject it before calling this.
*/
fun matchesDeclaredFormat(format: OcrImageFormat, bytes: ByteArray): Boolean =
when (format) {
OcrImageFormat.PNG -> bytes.startsWith(PNG_SIGNATURE)
OcrImageFormat.JPEG -> bytes.startsWith(JPEG_SIGNATURE)
OcrImageFormat.UNKNOWN -> true
}

/** Content sniff used by [normalized]; null when no known signature matches. */
fun sniffFormat(bytes: ByteArray): OcrImageFormat? =
when {
bytes.startsWith(PNG_SIGNATURE) -> OcrImageFormat.PNG
bytes.startsWith(JPEG_SIGNATURE) -> OcrImageFormat.JPEG
else -> null
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

}

/**

Engine invocation settings.
@property languageHints BCP-47 language tags; empty means "engine auto-detects".
Blank tags are rejected (a bug or an accident waiting to happen).
@property mode requested speed/quality posture; engines may treat it as a hint
but MUST fold it into any deterministic output derivation.
*/
data class OcrSettings(
val languageHints: List<String> = emptyList(),
val mode: OcrMode = OcrMode.BALANCED,
) {
init {
require(languageHints.all { it.isNotBlank() }) {
"languageHints must not contain blank entries"
}
}
companion object {
val DEFAULT: OcrSettings = OcrSettings()
}

}

/**

Sealed failure taxonomy for the OCR seam. Instances are values: engines

return them, they are never thrown across the seam.
/
sealed interface OcrFailure {
/* Declared format is not usable (e.g. UNKNOWN and not sniffable). */
data object UNSUPPORTED_FORMAT : OcrFailure

/** Zero-length image payload. */
data object EMPTY_IMAGE : OcrFailure

/** Payload exists but is not decodable as declared / dimensions impossible. */
data object CORRUPT_IMAGE : OcrFailure

/** Engine-specific failure; [message] is a stable, human-readable description. */
data class ENGINE_ERROR(val message: String) : OcrFailure

/** Engine was closed before this call. */
data object CLOSED : OcrFailure

}

/** Exactly-one outcome of a recognition attempt. */
sealed interface OcrOutcome {
data class Success(val result: OcrResult) : OcrOutcome
data class Failure(val reason: OcrFailure) : OcrOutcome
}

/**

The OCR engine seam (see file KDoc for the binding contract).
/
interface OcrEngine {
/* Stable engine identity; appears on results and in harness reports. */
val engineId: String

/**

Recognize text in [image] under [settings]. Must not throw for expected
bad inputs — return [OcrOutcome.Failure] instead. Must be safe to invoke
from arbitrary coroutines and must not block the caller unnecessarily.
*/
suspend fun recognize(image: OcrImage, settings: OcrSettings): OcrOutcome

/** Release engine resources. Idempotent; after close, recognize → Failure(CLOSED). */
fun close()

}
