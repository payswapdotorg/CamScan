package org.payswap.camscan.tools.conversion

// CAMSCAN-PROD-015 §6.2 — the pure input model of the Office conversion
// adapters: a document title plus ordered pages of ordered OCR text
// lines. HONEST SCOPE: conversion is TEXT-LEVEL — paragraph/row structure
// only, never layout, fonts or images; parity against the reference
// converters is explicitly UNVERIFIED.

/** Pure input model for text-level Office conversion. */
data class ConversionDocument(
    val title: String,
    val pages: List<ConversionPage>,
)

/** One page of a [ConversionDocument]: ordered OCR text lines. */
data class ConversionPage(
    val lines: List<String>,
)

/** Paragraph/line mapping helpers shared by the conversion adapters. */
object ConversionMapping {

    /** Paragraph segmentation rule (documented, tested): a line is BLANK iff its trimmed content is empty; paragraphs are maximal runs of consecutive non-blank lines. Consecutive blank lines collapse (no empty paragraphs); leading and trailing blank lines are ignored. */
    fun paragraphsOf(page: ConversionPage): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in page.lines) {
            if (isBlankLine(line)) {
                if (current.isNotEmpty()) {
                    result.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current)
        }
        return result
    }

    /** Each paragraph as one text block, its lines joined by a single LF. */
    fun paragraphTexts(page: ConversionPage): List<String> {
        return paragraphsOf(page).map { paragraph -> paragraph.joinToString("\n") }
    }

    /** True when a line is blank under the documented rule (trim-empty). */
    fun isBlankLine(line: String): Boolean = line.trim().isEmpty()
}
