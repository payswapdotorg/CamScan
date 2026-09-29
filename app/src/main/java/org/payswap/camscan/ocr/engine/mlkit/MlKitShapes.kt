package org.payswap.camscan.ocr.engine.mlkit

/**
 * Pure-Kotlin DTO mirrors of Google ML Kit's recognized-text object graph
 * (Text -> TextBlock -> Line -> Element, v2 reading order).
 *
 * These shapes are the boundary between the ONE android-side adapter
 * (`MlKitAdapter.kt` — the only file in this package that may import ML Kit,
 * gms-tasks, or android classes) and everything else here, which stays
 * JVM-clean: the [MlKitMapper] consumes exactly these DTOs, so mapping,
 * normalization, confidence policy, and determinism are all unit-testable
 * without a device, without ML Kit, and without Android.
 *
 * Boxes are PIXEL rects in the coordinate space of the input image as it was
 * handed to the recognizer (left/top/right/bottom edges). The adapter copies
 * them verbatim from ML Kit's `android.graphics.Rect` values; no unit
 * conversion happens here — normalization to 0..1 page space is
 * [MlKitMapper]'s documented job.
 */
data class MlKitBoxDto(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** One recognized element (a word or word fragment) inside a line. */
data class MlKitElementDto(
    val text: String,
    val box: MlKitBoxDto,
)

/**
 * One recognized line of text.
 *
 * @property confidence ML Kit v2 Latin's per-LINE quality estimate in 0..1,
 * where the producing adapter exposes one. The type is nullable because
 * absence is representable: ML Kit's Java API returns a primitive `float`
 * (never null), so the landed adapter always delivers a value, but the DTO
 * models "no confidence available" for future adapters and for synthetic
 * test inputs — see [MlKitMapper.DEFAULT_CONFIDENCE] for the documented
 * policy a null lands under.
 * @property elements the line's elements in reading order. Defaults to empty
 * so the documented four-field constructor shape stays usable; ML Kit's own
 * hierarchy always nests elements under lines and the adapter populates them
 * for downstream word-level consumers (search/highlight are later waves).
 */
data class MlKitLineDto(
    val text: String,
    val box: MlKitBoxDto,
    val confidence: Float?,
    val elements: List<MlKitElementDto> = emptyList(),
)

/** One recognized block (a paragraph-like grouping of lines). */
data class MlKitBlockDto(
    val text: String,
    val box: MlKitBoxDto,
    val lines: List<MlKitLineDto>,
)

/** The whole recognized page: blocks in ML Kit's reading order. */
data class MlKitTextDto(
    val blocks: List<MlKitBlockDto>,
)
