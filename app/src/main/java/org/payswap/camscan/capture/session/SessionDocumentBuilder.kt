package org.payswap.camscan.capture.session

import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.DocumentSource
import org.payswap.camscan.core.model.Page
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * CAMSCAN-PROD-004 §6.4 (pure half) — assembles the durable core model from
 * a finished [SessionResult], pure Kotlin / android-free so the JVM suite
 * can verify title formatting, ordering, refs and metadata exactly.
 *
 * The android half ([SessionPersistAdapter]) owns PNG encoding, ContentStore
 * puts, and the repository write; it hands the refs it produced to
 * [build] and upserts the result.
 *
 * Determinism: the title is formatted through java.time with an INJECTED
 * zone (never the device default) from a TimeSource-sourced timestamp.
 */
class SessionDocumentBuilder(

    /** Title time zone — injected (default UTC); never ZoneId.systemDefault(). */
    private val zone: ZoneId = ZoneOffset.UTC,
) {

    /** "Scan yyyy-MM-dd HH:mm" at [nowMillis] rendered in the injected zone. */
    fun formatTitle(nowMillis: Long): String =
        TITLE_PREFIX + TIMESTAMP_FORMAT.withZone(zone).format(Instant.ofEpochMilli(nowMillis))

    /** ContentStore key for a processed page PNG (contract: pages/&lt;id&gt;.png). */
    fun processedKey(pageId: String): String = "pages/$pageId.png"

    /** ContentStore key for a stored original capture when the JPEG file exists. */
    fun sourceJpegKey(pageId: String): String = "sources/$pageId.jpg"

    /** ContentStore key for a PNG-encoded source when no capture file exists. */
    fun sourcePngKey(pageId: String): String = "sources/$pageId.png"

    /**
     * Builds the (Document, ordered Pages) pair for a finished session.
     *
     * [processedRefs]/[sourceRefs] must be index-aligned with
     * [result.pages]; a null entry means "not stored" (the nullable model
     * refs stay null). Returns null on a size mismatch — flag discipline,
     * never throws.
     */
    fun build(
        result: SessionResult,
        documentId: String,
        processedRefs: List<String?>,
        sourceRefs: List<String?>,
        nowMillis: Long,
        title: String = formatTitle(nowMillis),
    ): Pair<Document, List<Page>>? {
        if (processedRefs.size != result.pageCount) return null
        if (sourceRefs.size != result.pageCount) return null
        if (documentId.isBlank()) return null

        val pages = result.pages.mapIndexed { index, page ->
            Page(
                id = page.pageId,
                documentId = documentId,
                index = index,
                sourceCaptureRef = sourceRefs[index],
                processedImageRef = processedRefs[index],
                cropQuad = page.cropQuad,
                enhancement = page.enhancement,
                rotationDegrees = page.rotationDegrees,
                ocrResultId = null,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
            )
        }
        val document = Document(
            id = documentId,
            title = title,
            pageIds = pages.map { it.id },
            sourceType = DocumentSource.SCAN,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )
        return document to pages
    }

    companion object {
        const val TITLE_PREFIX = "Scan "

        /** The work order's title pattern, exactly. */
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
