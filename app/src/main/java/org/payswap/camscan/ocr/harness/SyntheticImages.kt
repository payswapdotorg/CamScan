package org.payswap.camscan.ocr.harness

import org.payswap.camscan.ocr.engine.OcrImage
import org.payswap.camscan.ocr.engine.OcrImageFormat

/**

Deterministic synthetic image generation for the OCR harness and tests.

Payloads are repeating byte patterns of controlled sizes — generated in

code, so NO binary fixtures live in git. PNG/JPEG payloads carry their real

magic signatures so format-validating engines treat them as well-formed.

Everything here is a pure function of its arguments.
*/
object SyntheticImages {

/**

A synthetic [OcrImage]: [sizeBytes] payload of a period-[patternPeriod]
sawtooth pattern offset by [patternSeed], prefixed with the format's
magic signature when the format declares one.
*/
fun repeating(
width: Int,
height: Int,
format: OcrImageFormat,
rotationDegrees: Int = 0,
sizeBytes: Int = DEFAULT_SIZE_BYTES,
patternPeriod: Int = DEFAULT_PATTERN_PERIOD,
patternSeed: Int = 0,
): OcrImage {
require(sizeBytes >= 0) { "sizeBytes must be >= 0 (got $sizeBytes)" }
require(patternPeriod > 0) { "patternPeriod must be > 0 (got $patternPeriod)" }
val payload = ByteArray(sizeBytes) { i -> ((i % patternPeriod) + patternSeed).toByte() }
val bytes = when (format) {
OcrImageFormat.PNG -> OcrImage.PNG_SIGNATURE + payload
OcrImageFormat.JPEG -> OcrImage.JPEG_SIGNATURE + payload
OcrImageFormat.UNKNOWN -> payload
}
return OcrImage(
bytes = bytes,
width = width,
height = height,
format = format,
rotationDegrees = rotationDegrees,
)
}

/** An image whose payload is empty — the canonical EMPTY_IMAGE fixture. */
fun empty(
width: Int,
height: Int,
format: OcrImageFormat,
rotationDegrees: Int = 0,
): OcrImage = OcrImage(
bytes = ByteArray(0),
width = width,
height = height,
format = format,
rotationDegrees = rotationDegrees,
)

/** A copy of [image] with the byte at [index] replaced by [value] (pure). */
fun withMutatedByte(image: OcrImage, index: Int, value: Byte): OcrImage {
require(index in image.bytes.indices) { "byte index $index out of bounds" }
val mutated = image.bytes.copyOf()
mutated[index] = value
return image.copy(bytes = mutated)
}

/**

The default harness input set: a deterministic mix of formats, sizes,
rotation, and expected-failure inputs. Indices are a documented contract
with the harness tests:
0..3 -> well-formed PNG/JPEG inputs (engines return success)
4 -> UNKNOWN format, un-sniffable -> UNSUPPORTED_FORMAT
5 -> empty payload -> EMPTY_IMAGE
6 -> zero width -> CORRUPT_IMAGE
7 -> corrupt PNG signature -> CORRUPT_IMAGE
*/
fun defaultHarnessSet(): List<OcrImage> = listOf(
repeating(width = 512, height = 512, format = OcrImageFormat.PNG, patternSeed = 1),
repeating(
width = 640,
height = 480,
format = OcrImageFormat.PNG,
rotationDegrees = 90,
patternSeed = 2,
),
repeating(width = 800, height = 600, format = OcrImageFormat.JPEG, patternSeed = 3),
repeating(
width = 300,
height = 400,
format = OcrImageFormat.PNG,
sizeBytes = 2048,
patternPeriod = 32,
patternSeed = 4,
),
repeating(width = 256, height = 256, format = OcrImageFormat.UNKNOWN, patternSeed = 5),
empty(width = 512, height = 512, format = OcrImageFormat.PNG),
repeating(width = 512, height = 512, format = OcrImageFormat.PNG, patternSeed = 6)
.copy(width = 0),
withMutatedByte(
repeating(width = 512, height = 512, format = OcrImageFormat.PNG, patternSeed = 7),
index = 0,
value = 0x00,
),
)

private const val DEFAULT_SIZE_BYTES = 1024
private const val DEFAULT_PATTERN_PERIOD = 64

}
