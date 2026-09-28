package org.payswap.camscan.ocr.engine.mlkit

import org.payswap.camscan.ocr.engine.OcrBox
import org.payswap.camscan.ocr.engine.OcrResult
import org.payswap.camscan.ocr.engine.OcrSettings
import org.payswap.camscan.ocr.engine.OcrTextBlock
import org.payswap.camscan.ocr.util.Digests

/**
 * PURE mapper: ML Kit-shaped DTOs plus the declared image dimensions become
 * an [OcrResult]. No ML Kit, no android, no clock, no randomness — same DTOs
 * plus fixed ids/time produce byte-identical [OcrResult.toStableString].
 *
 * Box normalization (documented contract, mirrors the seam's OcrBox doc):
 * ML Kit boxes are pixel rects in the coordinate space of the INPUT image as
 * constructed — normalized LEFT/width, TOP/height, RIGHT/width, BOTTOM/height
 * against the DECLARED [OcrImage] dimensions (pre-rotation), then CLAMPED to
 * 0..1, because ML Kit boxes may exceed the image bounds by a few pixels and
 * because a degenerate upstream box must never break OcrBox's invariants.
 *
 * ROTATED-FRAME ASSUMPTION (the one deliberately open coordinate-space
 * question, to be settled by the station smoke test in
 * DELIVERY-PROD-010.txt): the mapper assumes ML Kit returns boxes in the
 * PRE-ROTATION input frame — the same frame the declared width/height
 * describe. If the station proves ML Kit returns the rotated frame for
 * rotated inputs, the correction lands HERE, in [normalizeBox], and nowhere
 * else.
 *
 * Confidence policy (documented): ML Kit v2 Latin exposes per-LINE
 * confidence; a BLOCK's confidence is the MEAN of its lines' confidences,
 * where a line with a null confidence counts as [DEFAULT_CONFIDENCE]. The
 * default is 1.0f because MISSING is not LOW — defaulting a null to 0.0f
 * would poison [OcrResult.meanConfidence] downward for pages that simply
 * carry no confidence signal. A block with zero lines also takes
 * [DEFAULT_CONFIDENCE] (no lines means no evidence of low quality). Values
 * outside 0..1 are coerced into range and NaN is treated as missing — a
 * malformed upstream confidence must surface as policy, not as a crash.
 *
 * Text joining: [OcrResult] itself owns the fullText rule (blocks joined in
 * reading order with a single newline) and computes it from the block list —
 * this mapper constructs that list and invents NO second join rule. Block
 * text comes from the DTO's `text` field (ML Kit's own line-joined block
 * text), verbatim.
 *
 * resultId convention (same as the PROD-009 stub's): `<prefix>-<8 lowercase
 * hex chars>` from a domain-separated, length-framed SHA-256 over the ENTIRE
 * result domain (engine id, settings echo, recognition instant, every block's
 * text/box/confidence/index). Two recognitions at different instants get
 * different ids; identical content at an identical instant is the same id.
 */
object MlKitMapper {

    /**
     * Confidence substituted for a line that carries no confidence value.
     * Missing is not low — see the class KDoc for the full rationale.
     */
    const val DEFAULT_CONFIDENCE: Float = 1.0f

    /** resultId prefix, mirroring the stub's `stub-` convention. */
    const val RESULT_ID_PREFIX: String = "mlkit-"

    /**
     * Maps a recognized [MlKitTextDto] into an [OcrResult] for the image whose
     * DECLARED pixel dimensions are [imageWidth] x [imageHeight].
     *
     * @throws IllegalArgumentException when dims are non-positive (engines
     * guarantee this via pre-flight; direct callers must too) or when a DTO
     * box is inverted (left > right or top > bottom) — surfaced honestly as
     * ENGINE_ERROR by the engine's never-throws guard instead of being
     * silently "fixed" here.
     */
    fun toResult(
        text: MlKitTextDto,
        imageWidth: Int,
        imageHeight: Int,
        engineId: String,
        settingsEcho: OcrSettings,
        recognisedAtMillis: Long,
        processingDurationMillis: Long,
    ): OcrResult {
        require(imageWidth > 0) { "imageWidth must be positive (got $imageWidth)" }
        require(imageHeight > 0) { "imageHeight must be positive (got $imageHeight)" }
        val blocks = text.blocks.mapIndexed { index, block ->
            OcrTextBlock(
                text = block.text,
                confidence = blockConfidence(block),
                box = normalizeBox(block.box, imageWidth, imageHeight),
                blockIndex = index,
            )
        }
        return OcrResult(
            resultId = mintResultId(blocks, engineId, settingsEcho, recognisedAtMillis),
            pageId = null, // the seam carries no page identity; the persisting layer stamps it
            blocks = blocks,
            engineId = engineId,
            settingsEcho = settingsEcho,
            recognisedAtMillis = recognisedAtMillis,
            processingDurationMillis = processingDurationMillis,
        )
    }

    /**
     * Block confidence = mean of its lines' confidences; null line confidence
     * counts as [DEFAULT_CONFIDENCE]; zero lines -> [DEFAULT_CONFIDENCE];
     * result sanitized into 0..1 (NaN treated as missing).
     */
    internal fun blockConfidence(block: MlKitBlockDto): Float {
        if (block.lines.isEmpty()) return sanitize(DEFAULT_CONFIDENCE)
        var total = 0f
        for (line in block.lines) total += sanitize(line.confidence ?: DEFAULT_CONFIDENCE)
        return sanitize(total / block.lines.size)
    }

    /**
     * THE single coordinate-space conversion (and the single correction point
     * if the station smoke test disproves the pre-rotation frame assumption):
     * pixel edges divided by the declared extents, clamped into 0..1.
     */
    internal fun normalizeBox(box: MlKitBoxDto, imageWidth: Int, imageHeight: Int): OcrBox = OcrBox(
        left = fraction(box.left, imageWidth),
        top = fraction(box.top, imageHeight),
        right = fraction(box.right, imageWidth),
        bottom = fraction(box.bottom, imageHeight),
    )

    /** Pixel fraction of the declared extent, clamped into 0..1. */
    private fun fraction(pixel: Int, extent: Int): Float =
        (pixel.toFloat() / extent.toFloat()).coerceIn(0f, 1f)

    /** NaN reads as missing (-> DEFAULT_CONFIDENCE); everything else clamps. */
    private fun sanitize(confidence: Float): Float = when {
        confidence.isNaN() -> DEFAULT_CONFIDENCE
        else -> confidence.coerceIn(0f, 1f)
    }

    /**
     * Domain-separated, length-framed SHA-256 over the whole result domain —
     * the stub's id convention applied to live results: same content at the
     * same instant -> same id; anything changes -> different id.
     */
    private fun mintResultId(
        blocks: List<OcrTextBlock>,
        engineId: String,
        settingsEcho: OcrSettings,
        recognisedAtMillis: Long,
    ): String {
        val digest = Digests.sha256(
            Digests.utf8(DOMAIN_TAG),
            Digests.utf8(engineId),
            Digests.utf8(settingsEcho.mode.name),
            Digests.intBytes(settingsEcho.languageHints.size),
            *settingsEcho.languageHints.map { Digests.utf8(it) }.toTypedArray(),
            Digests.intBytes((recognisedAtMillis ushr Int.SIZE_BITS).toInt()),
            Digests.intBytes(recognisedAtMillis.toInt()),
            Digests.intBytes(blocks.size),
            *blocks.flatMap { block ->
                listOf(
                    Digests.intBytes(block.text.length),
                    Digests.utf8(block.text),
                    Digests.intBytes(block.box.left.toRawBits()),
                    Digests.intBytes(block.box.top.toRawBits()),
                    Digests.intBytes(block.box.right.toRawBits()),
                    Digests.intBytes(block.box.bottom.toRawBits()),
                    Digests.intBytes(block.confidence.toRawBits()),
                    Digests.intBytes(block.blockIndex),
                )
            }.toTypedArray(),
        )
        return RESULT_ID_PREFIX + Digests.hex(digest.copyOfRange(0, RESULT_ID_DIGEST_BYTES))
    }

    private const val DOMAIN_TAG = "camscan/ocr/mlkit/result-id/v1"
    private const val RESULT_ID_DIGEST_BYTES = 4
}
