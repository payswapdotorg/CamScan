package org.payswap.camscan.document.persistence

import org.payswap.camscan.core.model.Corner
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.model.PageEnhancementMode

/**

Dependency-free, byte-stable JSON codec for the persistence index

(CAMSCAN-PROD-006). Schema version 1.

Aligned to the FROZEN model: [Document] carries no pages — pages travel as

an explicit map keyed by document id — and Page.cropQuad is

List<Corner>?, serialized as a flat number array [x0, y0, x1, y1, ...]

in corner list order (null when absent or empty).

Write path is canonical: fixed field order, documents sorted by id, pages

sorted by index, no whitespace — the same logical state always serializes

to identical bytes (pinned by test). Read path accepts standard JSON,

maps with schema defaults for absent optional fields, ignores unknown

fields (forward-compatible), and throws [IndexParseException] on

structural mismatch or an unsupported schemaVersion. The parse exception

is internal to the persistence layer; the repository converts it into

honest recovery, never a UI-facing crash.
*/
object IndexJsonCodec {

const val SCHEMA_VERSION = 1

class IndexParseException(message: String, cause: Throwable? = null) :
Exception(message, cause)
/** Full index contents: documents plus their page lists, keyed by id. */
data class IndexSnapshot(
val documents: List<Document>,
val pagesByDocumentId: Map<String, List<Page>>,
)

// ---------------------------------------------------------------- write

fun serialize(
documents: List<Document>,
pagesByDocumentId: Map<String, List<Page>>,
): String {
val builder = StringBuilder()
builder.append("{\"schemaVersion\":").append(SCHEMA_VERSION)
builder.append(",\"documents\":[")
documents.sortedBy { it.id }.forEachIndexed { docIdx, doc ->
if (docIdx > 0) builder.append(',')
appendDocument(builder, doc, pagesByDocumentId[doc.id].orEmpty())
}
builder.append("]}")
return builder.toString()
}

private fun appendDocument(builder: StringBuilder, doc: Document, pages: List<Page>) {
builder.append("{\"id\":")
appendString(builder, doc.id)
builder.append(",\"title\":")
appendString(builder, doc.title)
builder.append(",\"createdAtMillis\":").append(doc.createdAtMillis)
builder.append(",\"updatedAtMillis\":").append(doc.updatedAtMillis)
builder.append(",\"pages\":[")
pages.sortedBy { it.index }.forEachIndexed { pageIdx, page ->
if (pageIdx > 0) builder.append(',')
appendPage(builder, page)
}
builder.append("]}")
}

private fun appendPage(builder: StringBuilder, page: Page) {
builder.append("{\"id\":")
appendString(builder, page.id)
builder.append(",\"documentId\":")
appendString(builder, page.documentId)
builder.append(",\"index\":").append(page.index)
builder.append(",\"sourceCaptureRef\":")
appendNullableString(builder, page.sourceCaptureRef)
builder.append(",\"processedImageRef\":")
appendNullableString(builder, page.processedImageRef)
builder.append(",\"cropQuad\":")
appendCornerList(builder, page.cropQuad)
builder.append(",\"enhancement\":")
appendString(builder, page.enhancement.name)
builder.append(",\"rotationDegrees\":").append(page.rotationDegrees)
builder.append(",\"ocrResultId\":")
appendNullableString(builder, page.ocrResultId)
builder.append(",\"createdAtMillis\":").append(page.createdAtMillis)
builder.append(",\"updatedAtMillis\":").append(page.updatedAtMillis)
builder.append('}')
}

/**

Canonical form for the frozen model's cropQuad: List<Corner>?: a flat
number array [x0, y0, x1, y1, ...] in corner list order; null when
absent or empty.
*/
private fun appendCornerList(builder: StringBuilder, corners: List<Corner>?) {
if (corners == null || corners.isEmpty()) {
builder.append("null")
return
}
builder.append('[')
corners.forEachIndexed { idx, corner ->
if (idx > 0) builder.append(',')
builder.append(formatFloat(corner.x))
builder.append(',')
builder.append(formatFloat(corner.y))
}
builder.append(']')
}

private fun formatFloat(value: Float): String {
// Non-finite values are normalized to 0f: the model only ever holds
// normalized coordinates, and JSON has no NaN/Infinity literals.
if (value.isNaN() || value.isInfinite()) return "0.0"
return value.toString()
}

private fun appendNullableString(builder: StringBuilder, value: String?) {
if (value == null) {
builder.append("null")
} else {
appendString(builder, value)
}
}

private fun appendString(builder: StringBuilder, value: String) {
builder.append('"')
for (ch in value) {
when (ch) {
'"' -> builder.append("\\\"")
'\\' -> builder.append("\\\\")
'\n' -> builder.append("\\n")
'\r' -> builder.append("\\r")
'\t' -> builder.append("\\t")
'\b' -> builder.append("\\b")
'\u000C' -> builder.append("\\f")
else ->
if (ch < ' ') {
builder.append("\\u")
builder.append(String.format("%04x", ch.code))
} else {
builder.append(ch)
}
}
}
builder.append('"')
}

// ----------------------------------------------------------------- read

fun deserialize(json: String): IndexSnapshot {
val parser = Parser(json)
parser.skipWhitespace()
val root = parser.parseValue() as? Map<*, *>
?: throw IndexParseException("index root is not an object")
val schemaVersion = (root["schemaVersion"] as? Number)?.toInt()
?: throw IndexParseException("index is missing schemaVersion")
if (schemaVersion != SCHEMA_VERSION) {
throw IndexParseException("unsupported schemaVersion $schemaVersion")
}
@Suppress("UNCHECKED_CAST")
val rawDocuments = root["documents"] as? List<Map<String, Any?>>
?: throw IndexParseException("documents is not an array")
val documents = ArrayList<Document>(rawDocuments.size)
val pagesByDocumentId = LinkedHashMap<String, List<Page>>()
rawDocuments.forEach { raw ->
val document = mapDocument(raw)
documents.add(document)
val pages = ArrayList<Page>()
val rawPages = raw["pages"] as? List<Map<String, Any?>>
if (rawPages != null) {
rawPages.forEach { pageMap ->
pages.add(mapPage(pageMap))
}
}
pagesByDocumentId[document.id] = pages
}
return IndexSnapshot(documents, pagesByDocumentId)
}

private fun mapDocument(map: Map<String, Any?>): Document = Document(
id = map.requireString("id"),
title = map.string("title") ?: "",
createdAtMillis = map.long("createdAtMillis") ?: 0L,
updatedAtMillis = map.long("updatedAtMillis") ?: 0L,
)

private fun mapPage(map: Map<String, Any?>): Page = Page(
id = map.requireString("id"),
documentId = map.requireString("documentId"),
index = map.int("index") ?: 0,
sourceCaptureRef = map.string("sourceCaptureRef"),
processedImageRef = map.string("processedImageRef"),
cropQuad = map.cornerList("cropQuad"),
enhancement = enumOr(map.string("enhancement"), PageEnhancementMode.ORIGINAL),
rotationDegrees = map.int("rotationDegrees") ?: 0,
ocrResultId = map.string("ocrResultId"),
createdAtMillis = map.long("createdAtMillis") ?: 0L,
updatedAtMillis = map.long("updatedAtMillis") ?: 0L,
)

private fun Map<String, Any?>.requireString(key: String): String =
this[key] as? String ?: throw IndexParseException("missing required string: $key")

private fun Map<String, Any?>.string(key: String): String? = this[key] as? String

private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

/** Reads the flat [x0, y0, x1, y1, ...] corner array; null when absent, empty, odd-length, or non-numeric. */
private fun Map<String, Any?>.cornerList(key: String): List<Corner>? {
val flat = this[key] as? List<*> ?: return null
if (flat.isEmpty() || flat.size % 2 != 0) return null
val corners = ArrayList<Corner>(flat.size / 2)
var i = 0
while (i < flat.size) {
val x = (flat[i] as? Number)?.toFloat() ?: return null
val y = (flat[i + 1] as? Number)?.toFloat() ?: return null
corners.add(Corner(x, y))
i += 2
}
return corners
}

private fun enumOr(name: String?, fallback: PageEnhancementMode): PageEnhancementMode =
name?.let { runCatching { PageEnhancementMode.valueOf(it) }.getOrNull() } ?: fallback

/**

Minimal recursive-descent JSON reader producing Map/List/String/

Long-Double/Boolean/null values.
*/
private class Parser(private val text: String) {

private var pos = 0

fun skipWhitespace() {
while (pos < text.length && text[pos].isWhitespace()) pos++
}

fun parseValue(): Any? {
skipWhitespace()
if (pos >= text.length) throw IndexParseException("unexpected end of index")
return when (val ch = text[pos]) {
'{' -> parseObject()
'[' -> parseArray()
'"' -> parseString()
't' -> parseLiteral("true", true)
'f' -> parseLiteral("false", false)
'n' -> parseLiteral("null", null)
else ->
if (ch == '-' || ch.isDigit()) parseNumber()
else throw IndexParseException("unexpected character '$ch' at $pos")
}
}

private fun parseObject(): Map<String, Any?> {
pos++
val result = LinkedHashMap<String, Any?>()
skipWhitespace()
if (pos < text.length && text[pos] == '}') {
pos++
return result
}
while (true) {
skipWhitespace()
if (pos >= text.length || text[pos] != '"') {
throw IndexParseException("expected object key at $pos")
}
val key = parseString()
skipWhitespace()
if (pos >= text.length || text[pos] != ':') {
throw IndexParseException("expected ':' at $pos")
}
pos++
result[key] = parseValue()
skipWhitespace()
when (text.getOrNull(pos)) {
',' -> pos++
'}' -> {
pos++
return result
}
else -> throw IndexParseException("expected ',' or '}' at $pos")
}
}
}

private fun parseArray(): List<Any?> {
pos++
val result = ArrayList<Any?>()
skipWhitespace()
if (pos < text.length && text[pos] == ']') {
pos++
return result
}
while (true) {
result.add(parseValue())
skipWhitespace()
when (text.getOrNull(pos)) {
',' -> pos++
']' -> {
pos++
return result
}
else -> throw IndexParseException("expected ',' or ']' at $pos")
}
}
}

private fun parseString(): String {
pos++
val builder = StringBuilder()
while (true) {
if (pos >= text.length) throw IndexParseException("unterminated string")
when (val ch = text[pos]) {
'"' -> {
pos++
return builder.toString()
}
'\\' -> {
pos++
when (val esc = text.getOrNull(pos)) {
'"' -> builder.append('"')
'\\' -> builder.append('\\')
'/' -> builder.append('/')
'n' -> builder.append('\n')
'r' -> builder.append('\r')
't' -> builder.append('\t')
'b' -> builder.append('\b')
'f' -> builder.append('\u000C')
'u' -> {
val hex = text.substring(pos + 1, pos + 5)
builder.append(hex.toInt(16).toChar())
pos += 4
}
else -> throw IndexParseException("bad escape '$esc'")
}
pos++
}
else -> {
builder.append(ch)
pos++
}
}
}
}

private fun parseNumber(): Any {
val start = pos
if (text[pos] == '-') pos++
while (pos < text.length && (text[pos].isDigit() || text[pos] == '.' ||
text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' ||
text[pos] == '-')
) {
pos++
}
val raw = text.substring(start, pos)
return raw.toLongOrNull() ?: raw.toDoubleOrNull()
?: throw IndexParseException("bad number '$raw'")
}

private fun parseLiteral(literal: String, value: Any?): Any? {
if (!text.startsWith(literal, pos)) {
throw IndexParseException("bad literal at $pos")
}
pos += literal.length
return value
}
}
}
