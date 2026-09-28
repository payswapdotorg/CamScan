package org.payswap.camscan.ocr.engine

import java.util.Locale

/**

Normalized bounding box in 0..1 page space (left/top/right/bottom edges).

Coordinates are fractions of page width/height so results survive rescaling

of the underlying page image.
*/
data class OcrBox(
val left: Float,
val top: Float,
val right: Float,
val bottom: Float,
) {
init {
require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f) {
"OcrBox must lie in 0..1 page space (got l=$left t=$top r=$right b=$bottom)"
}
require(right >= left) { "OcrBox right ($right) must be >= left ($left)" }
require(bottom >= top) { "OcrBox bottom ($bottom) must be >= top ($top)" }
}

companion object {
/** The whole page. */
val FULL_PAGE: OcrBox = OcrBox(0f, 0f, 1f, 1f)
}

}

/**

One recognized text block, positioned in normalized page space.
@property confidence quality estimate in 0..1 (1 = certain).
@property box normalized bounding box.
@property blockIndex position in reading order; [OcrResult] requires strictly
increasing, non-negative indices across its block list.
*/
data class OcrTextBlock(
val text: String,
val confidence: Float,
val box: OcrBox,
val blockIndex: Int,
) {
init {
require(blockIndex >= 0) { "blockIndex must be >= 0 (got $blockIndex)" }
require(confidence in 0f..1f) { "confidence must be in 0..1 (got $confidence)" }
}
}

/**

The OCR result that persists and indexes.
Lifecycle/linkage notes:
[resultId] is the persistent identity; core.model.Page.ocrResultId
references it.
[pageId] is filled by the persisting layer (the recognize seam carries no
page identity), so engines return null and callers stamp it via
copy(pageId = ...) when attaching a result to a page.
[fullText] and [meanConfidence] are COMPUTED from [blocks] and can never
disagree with them.
[recognisedAtMillis] comes from the engine's injected TimeSource — the
RESULT carries the timestamp; nothing here reads the system clock.
[blocks] MUST be in strictly increasing reading order; construction fails
fast otherwise (engines sort before constructing).
[processingDurationMillis] is engine-reported. Deterministic engines (the
stub) derive it from content so stable strings stay comparable; real
engines may report measured time.

*/
data class OcrResult(
val resultId: String,
val pageId: String? = null,
val blocks: List<OcrTextBlock>,
val engineId: String,
val settingsEcho: OcrSettings,
val recognisedAtMillis: Long,
val processingDurationMillis: Long,
) {
init {
require(resultId.isNotEmpty()) { "resultId must not be empty" }
var previousIndex = -1
for (block in blocks) {
require(block.blockIndex > previousIndex) {
"blocks must be in strictly increasing reading order " +
"(blockIndex ${block.blockIndex} follows $previousIndex)"
}
previousIndex = block.blockIndex
}
}

/** Blocks joined deterministically: reading order, one newline between blocks. */
val fullText: String = blocks.joinToString(separator = "\n") { it.text }

/** Mean block confidence; 0f for an empty block list. */
val meanConfidence: Float =
if (blocks.isEmpty()) 0f else blocks.fold(0f) { acc, block -> acc + block.confidence } / blocks.size

/**
* Stable, deterministic, human-diffable serialization: plain `key=value`
* lines, fixed order, fixed float precision (6 decimals, Locale.ROOT),
* backslash/newline escaping in free-text fields. NO reflection, NO
* serialization frameworks. Two results with identical content produce
* byte-identical output — this is the parity/verification medium.
*/
fun toStableString(): String {
val lines = ArrayList<String>(11 + blocks.size * 3)
lines += "ocrResultFormat=1"
lines += "resultId=$resultId"
lines += "pageId=${pageId ?: ""}"
lines += "engineId=$engineId"
lines += "recognisedAtMillis=$recognisedAtMillis"
lines += "processingDurationMillis=$processingDurationMillis"
lines += "settings.mode=${settingsEcho.mode.name}"
lines += "settings.languageHints=${escapeStableText(settingsEcho.languageHints.joinToString(","))}"
lines += "meanConfidence=${stableFloat(meanConfidence)}"
lines += "blockCount=${blocks.size}"
lines += "fullText=${escapeStableText(fullText)}"
for (block in blocks) {
val label = "block[${block.blockIndex}]"
lines += "$label.confidence=${stableFloat(block.confidence)}"
val boxText = stableFloat(block.box.left) + "," +
stableFloat(block.box.top) + "," +
stableFloat(block.box.right) + "," +
stableFloat(block.box.bottom)
lines += "$label.box=$boxText"
lines += "$label.text=${escapeStableText(block.text)}"
}
return lines.joinToString("\n")
}

}

/** Fixed-precision float rendering for stable strings (Locale.ROOT, 6 decimals). */
internal fun stableFloat(value: Float): String = String.format(Locale.ROOT, "%.6f", value)

/**

Escapes free text for single-line stable-string fields: backslash, newline
and carriage return become two-character escape sequences, backslash first
so the mapping is unambiguous.
*/
internal fun escapeStableText(value: String): String =
value.replace("\", "\\").replace("\n", "\n").replace("\r", "\r")
