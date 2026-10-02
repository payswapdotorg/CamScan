package org.payswap.camscan.tools.watermark

// WatermarkSpec (CAMSCAN-PROD-011 section 6.4): the pure description of a
// document watermark plus its binding to a document id. The spec carries
// NO page geometry - the planner derives placements from page dimensions.

 // Watermark description.
 //
 // @param text the glyph-run text (ASCII from the 5x7 font; non-covered
 // characters render as nothing but still consume no advance);
 // @param opacity source alpha 0..255 for every glyph pixel (the spec
 // color's own alpha byte is IGNORED by the applier - documented);
 // @param rotationDegrees rotation of the tile grid, clockwise on screen
 // (raster coordinates have y pointing down);
 // @param colorArgb RGB watermark color;
 // @param tileSpacingPx gap in pixels between neighbouring tiles, >= 0.
 // /
class WatermarkSpec(
    val text: String,
    val opacity: Int,
    val rotationDegrees: Int,
    val colorArgb: Long,
    val tileSpacingPx: Int,
) {

    init {
        require(opacity in 0..255) { "opacity must be within 0..255" }
        require(tileSpacingPx >= 0) { "tileSpacingPx must be non-negative" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WatermarkSpec) return false
        return text == other.text && opacity == other.opacity &&
            rotationDegrees == other.rotationDegrees && colorArgb == other.colorArgb &&
            tileSpacingPx == other.tileSpacingPx
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + opacity
        result = 31 * result + rotationDegrees
        result = 31 * result + colorArgb.hashCode()
        result = 31 * result + tileSpacingPx
        return result
    }

    override fun toString(): String =
        "WatermarkSpec[text=" + text + ";opacity=" + opacity +
            ";rotation=" + rotationDegrees + ";spacing=" + tileSpacingPx + "]"
}

/** Binding of a watermark spec to a document (document ids are opaque keys). */
class DocumentWatermark(
    val documentId: String,
    val spec: WatermarkSpec,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocumentWatermark) return false
        return documentId == other.documentId && spec == other.spec
    }

    override fun hashCode(): Int = 31 * documentId.hashCode() + spec.hashCode()

    override fun toString(): String =
        "DocumentWatermark[doc=" + documentId + ";spec=" + spec + "]"
}
