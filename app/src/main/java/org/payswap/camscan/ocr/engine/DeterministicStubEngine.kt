package org.payswap.camscan.ocr.engine

import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.ocr.util.Digests

/**

⚠️ NOT AN OCR IMPLEMENTATION ⚠️ ⚠️ NOT AN OCR IMPLEMENTATION ⚠️

Deterministic plumbing fixture for the OCR seam, the harness, and the tests.

It performs NO text recognition whatsoever: given a well-formed input it

hashes the ENTIRE input domain (dimensions, format, rotation, settings,

every byte of the payload) with SHA-256 and derives fake-but-stable block

text, boxes, confidences, a result id, and a simulated processing duration

from that digest. Same input + same settings + same TimeSource value ⇒

byte-identical [OcrResult.toStableString]. One changed input byte ⇒

different output everywhere.

NEVER SHIP AS A USER-FACING ENGINE. It exists so that (a) the seam, result

model, and harness can be verified end-to-end today, and (b) the future real

engine has an exact-behavior reference to be validated against.

Not thread-safe by design (test fixture); use one instance per test/run.
*/
class DeterministicStubEngine(
private val timeSource: TimeSource,
) : OcrEngine {

private var closed = false

override val engineId: String = "stub-deterministic"

override suspend fun recognize(image: OcrImage, settings: OcrSettings): OcrOutcome {
if (closed) return OcrOutcome.Failure(OcrFailure.CLOSED)

val input = image.normalized()

// Degenerate inputs map to failure VALUES, never exceptions.
if (input.bytes.isEmpty()) return OcrOutcome.Failure(OcrFailure.EMPTY_IMAGE)
if (input.width <= 0 || input.height <= 0) return OcrOutcome.Failure(OcrFailure.CORRUPT_IMAGE)
if (input.format == OcrImageFormat.UNKNOWN) return OcrOutcome.Failure(OcrFailure.UNSUPPORTED_FORMAT)
if (!OcrImage.matchesDeclaredFormat(input.format, input.bytes)) {
return OcrOutcome.Failure(OcrFailure.CORRUPT_IMAGE)
}

// Time enters only here, through the injected seam.
val recognisedAtMillis = timeSource.currentTimeMillis()

val digest = inputDigest(input, settings)
val blocks = (0 until BLOCK_COUNT).map { index ->
val blockDigest = Digests.sha256(digest, Digests.intBytes(index))
OcrTextBlock(
text = blockText(blockDigest),
confidence = blockConfidence(blockDigest),
box = blockBox(index, blockDigest),
blockIndex = index,
)
}
val result = OcrResult(
resultId = RESULT_ID_PREFIX + Digests.hex(digest.copyOfRange(0, RESULT_ID_DIGEST_BYTES)),
pageId = null, // the seam carries no page identity; the persisting layer stamps it
blocks = blocks,
engineId = engineId,
settingsEcho = settings,
recognisedAtMillis = recognisedAtMillis,
// SIMULATED, content-derived: keeps stable strings deterministic.
processingDurationMillis = 1L + (digest[12].toInt() and 0x1F),
)
return OcrOutcome.Success(result)
}

/** Idempotent; after close, recognize reports Failure(CLOSED). */
override fun close() {
closed = true
}

// ---- pure derivation helpers (functions of the digest only) ----------

/**

Domain-separated SHA-256 over the ENTIRE input domain, with length
framing on every variable-length field so no concatenation ambiguity can
alias two different inputs onto one digest.
*/
private fun inputDigest(input: OcrImage, settings: OcrSettings): ByteArray =
Digests.sha256(
Digests.utf8(DOMAIN_TAG),
Digests.intBytes(input.width),
Digests.intBytes(input.height),
Digests.utf8(input.format.name),
Digests.intBytes(input.rotationDegrees),
Digests.utf8(settings.mode.name),
Digests.intBytes(settings.languageHints.size),
*settings.languageHints.map { Digests.utf8(it) }.toTypedArray(),
input.bytes,
)

/** Fake text: three 3-syllable pseudo-words from the block digest. Obviously not real text. */
private fun blockText(blockDigest: ByteArray): String {
fun syllable(i: Int): String = SYLLABLES[blockDigest[i].toInt() and 0x0F]
return listOf(
syllable(0) + syllable(1) + syllable(2),
syllable(3) + syllable(4) + syllable(5),
syllable(6) + syllable(7) + syllable(8),
).joinToString(" ")
}

/** Fake confidence in 0.55..0.95 from the block digest. */
private fun blockConfidence(blockDigest: ByteArray): Float =
0.55f + ((blockDigest[9].toInt() and 0xFF) / 255f) * 0.40f

/**

Fake box: block [index] sits in a 2x2 page grid cell with digest-driven
jitter; by construction 0 <= left <= right <= 1 and 0 <= top <= bottom <= 1.
*/
private fun blockBox(index: Int, blockDigest: ByteArray): OcrBox {
val col = index % 2
val row = index / 2
val jitterX = (blockDigest[10].toInt() and 0xFF) / 255f * BOX_JITTER
val jitterY = (blockDigest[11].toInt() and 0xFF) / 255f * BOX_JITTER
val left = col * 0.5f + jitterX
val top = row * 0.5f + jitterY
return OcrBox(
left = left,
top = top,
right = left + BOX_WIDTH,
bottom = top + BOX_HEIGHT,
)
}

private companion object {
const val DOMAIN_TAG = "camscan/ocr/stub-deterministic/v1"
const val BLOCK_COUNT = 4
const val RESULT_ID_PREFIX = "stub-"
const val RESULT_ID_DIGEST_BYTES = 4
const val BOX_JITTER = 0.04f
const val BOX_WIDTH = 0.44f
const val BOX_HEIGHT = 0.40f

/** Exactly 16 entries so one nibble selects one syllable. */
val SYLLABLES = listOf(
"ba", "de", "ka", "mi", "no", "ru", "sa", "ti",
"vu", "zo", "ha", "le", "pi", "go", "chu", "ny",
)

 }

}
