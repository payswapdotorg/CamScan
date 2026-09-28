package org.payswap.camscan.ocr.harness

import kotlin.coroutines.cancellation.CancellationException
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.ocr.engine.OcrEngine
import org.payswap.camscan.ocr.engine.OcrFailure
import org.payswap.camscan.ocr.engine.OcrImage
import org.payswap.camscan.ocr.engine.OcrOutcome
import org.payswap.camscan.ocr.engine.OcrSettings
import org.payswap.camscan.ocr.engine.escapeStableText
import org.payswap.camscan.ocr.engine.stableFloat
import org.payswap.camscan.ocr.util.Digests

/**

One harness record per input image: what went in, what came out, and the
canonical evidence string. Plain data — no behavior beyond [toStableString].
Measured duration ([measuredDurationMillis], taken as deltas of the INJECTED
TimeSource via [TimeSource.nowMillis] — never the raw clock) is deliberately
kept OUT of the stable string. Engine-reported values
([recognisedAtMillis], [processingDurationMillis]) are included and are
expected to be content-deterministic for harness-relevant engines.
/
data class OcrHarnessRecord(
val inputIndex: Int,
val imageSha256: String,
val imageBytes: Int,
val imageWidth: Int,
val imageHeight: Int,
val imageFormat: String,
val imageRotationDegrees: Int,
val outcome: String, // "SUCCESS" | "FAILURE"
val failureReason: String?,
val resultId: String?,
val blockCount: Int?,
val meanConfidence: Float?,
val recognisedAtMillis: Long?,
val processingDurationMillis: Long?,
val measuredDurationMillis: Long,
val resultStableString: String?,
) {
/* Canonical per-input evidence block (no trailing newline). */
fun toStableString(): String {
val lines = ArrayList<String>()
lines += "input=$inputIndex"
lines += "image.sha256=$imageSha256"
lines += "image.bytes=$imageBytes"
lines += "image.size=" + imageWidth + "x" + imageHeight
lines += "image.format=$imageFormat"
lines += "image.rotation=$imageRotationDegrees"
lines += "outcome=$outcome"
failureReason?.let { lines += "reason=$it" }
resultId?.let { lines += "resultId=$it" }
blockCount?.let { lines += "blockCount=$it" }
meanConfidence?.let { lines += "meanConfidence=" + stableFloat(it) }
recognisedAtMillis?.let { lines += "recognisedAtMillis=$it" }
processingDurationMillis?.let { lines += "processingDurationMillis=$it" }
resultStableString?.let { result ->
lines += "result.begin"
lines += result.lines()
lines += "result.end"
}
return lines.joinToString("\n")
}


/**

Harness run manifest: identity, settings echo, and one record per input.
[toStableString] is byte-identical across runs whenever the engine and the
injected TimeSource are deterministic — that byte-identity IS the
acceptance signal of this harness.
/
data class OcrHarnessReport(
val engineId: String,
val settingsEcho: OcrSettings,
val inputCount: Int,
val determinismVerified: Boolean,
val records: List<OcrHarnessRecord>,
) {
/* Canonical whole-run evidence string (no trailing newline). */
fun toStableString(): String {
val lines = ArrayList<String>()
lines += "ocrHarnessFormat=1"
lines += "engineId=$engineId"
lines += "settings.mode=" + settingsEcho.mode.name
lines += "settings.languageHints=" +
escapeStableText(settingsEcho.languageHints.joinToString(","))
lines += "inputCount=$inputCount"
lines += "determinismVerified=$determinismVerified"
records.forEach { record -> lines += record.toStableString().lines() }
return lines.joinToString("\n")
}


/**

Deterministic OCR test harness — the acceptance artifact of CAMSCAN-PROD-009.

Given an [OcrEngine], fixed [OcrSettings], and a TimeSource, [run] recognizes

a list of [OcrImage]s TWICE and asserts that the two full runs produce

byte-identical evidence. The comparison masks the two time-flavored lines

("recognisedAtMillis=", "processingDurationMillis=") so the assertion holds

under a real-time clock too; under a fixed/controlled TimeSource the FULL

unmasked stable strings are byte-identical end to end (tested).

Engine-agnostic by construction: works unchanged against the deterministic

stub today, a real on-device engine tomorrow, and reference-evidence

comparison data later. Synthetic inputs come from [SyntheticImages] —

repeating byte patterns generated in code, no binary fixtures in git.

Time is read ONLY through the injected [TimeSource] (frozen contract:

[TimeSource.nowMillis]); the harness never touches System or the platform

clock.

Exceptions from a misbehaving engine are converted to

[OcrFailure.ENGINE_ERROR] records (CancellationException is rethrown);

a genuine non-determinism aborts [run] with IllegalStateException.
*/
class OcrHarness(
private val engine: OcrEngine,
private val settings: OcrSettings = OcrSettings.DEFAULT,
private val timeSource: TimeSource,
) {

/**

Runs recognition over [images] twice, asserts determinism, and returns
the manifest built from the FIRST pass.
*/
suspend fun run(images: List<OcrImage>): OcrHarnessReport {
val firstPass = recognizeAll(images)
val secondPass = recognizeAll(images)
val firstReport = buildReport(images.size, firstPass)
val secondReport = buildReport(images.size, secondPass)
val firstEvidence = maskTimeLines(firstReport.toStableString())
val secondEvidence = maskTimeLines(secondReport.toStableString())
check(firstEvidence == secondEvidence) {
"OCR harness determinism failure: identical runs produced different " +
"evidence (first differing line index: " +
firstDiffLineIndex(firstEvidence, secondEvidence) + ")"
}
return firstReport
}

private suspend fun recognizeAll(images: List<OcrImage>): List<OcrHarnessRecord> =
images.mapIndexed { index, image ->
val startedAt = timeSource.nowMillis()
val outcome = try {
engine.recognize(image, settings)
} catch (cancellation: CancellationException) {
throw cancellation
} catch (failure: Exception) {
OcrOutcome.Failure(
OcrFailure.ENGINE_ERROR(
"engine threw " + failure::class.java.name + ": " +
(failure.message ?: "<no message>"),
),
)
}
val endedAt = timeSource.nowMillis()
buildRecord(index, image, outcome, measuredDurationMillis = endedAt - startedAt)
}

private fun buildRecord(
index: Int,
image: OcrImage,
outcome: OcrOutcome,
measuredDurationMillis: Long,
): OcrHarnessRecord = when (outcome) {
is OcrOutcome.Success -> OcrHarnessRecord(
inputIndex = index,
imageSha256 = Digests.hex(Digests.sha256(image.bytes)),
imageBytes = image.bytes.size,
imageWidth = image.width,
imageHeight = image.height,
imageFormat = image.format.name,
imageRotationDegrees = image.rotationDegrees,
outcome = OUTCOME_SUCCESS,
failureReason = null,
resultId = outcome.result.resultId,
blockCount = outcome.result.blocks.size,
meanConfidence = outcome.result.meanConfidence,
recognisedAtMillis = outcome.result.recognisedAtMillis,
processingDurationMillis = outcome.result.processingDurationMillis,
measuredDurationMillis = measuredDurationMillis,
resultStableString = outcome.result.toStableString(),
)
is OcrOutcome.Failure -> OcrHarnessRecord(
inputIndex = index,
imageSha256 = Digests.hex(Digests.sha256(image.bytes)),
imageBytes = image.bytes.size,
imageWidth = image.width,
imageHeight = image.height,
imageFormat = image.format.name,
imageRotationDegrees = image.rotationDegrees,
outcome = OUTCOME_FAILURE,
failureReason = failureCode(outcome.reason),
resultId = null,
blockCount = null,
meanConfidence = null,
recognisedAtMillis = null,
processingDurationMillis = null,
measuredDurationMillis = measuredDurationMillis,
resultStableString = null,
)
}

private fun buildReport(inputCount: Int, records: List<OcrHarnessRecord>): OcrHarnessReport =
OcrHarnessReport(
engineId = engine.engineId,
settingsEcho = settings,
inputCount = inputCount,
determinismVerified = true,
records = records,
)

private companion object {
const val OUTCOME_SUCCESS = "SUCCESS"
const val OUTCOME_FAILURE = "FAILURE"

/** Stable reason codes for evidence lines. */
fun failureCode(reason: OcrFailure): String = when (reason) {
is OcrFailure.UNSUPPORTED_FORMAT -> "UNSUPPORTED_FORMAT"
is OcrFailure.EMPTY_IMAGE -> "EMPTY_IMAGE"
is OcrFailure.CORRUPT_IMAGE -> "CORRUPT_IMAGE"
is OcrFailure.ENGINE_ERROR -> "ENGINE_ERROR(" + escapeStableText(reason.message) + ")"
is OcrFailure.CLOSED -> "CLOSED"
}

/** Index of the first differing line, or -1 when the strings are equal. */
fun firstDiffLineIndex(a: String, b: String): Int {
val left = a.lines()
val right = b.lines()
val common = minOf(left.size, right.size)
for (i in 0 until common) if (left[i] != right[i]) return i
return if (left.size != right.size) common else -1
}

 }

}

/** Time-flavored stable-string keys whose VALUES are masked for comparison. */
private val MASKED_TIME_KEYS = listOf("recognisedAtMillis", "processingDurationMillis")

/** Masks the VALUE of time-flavored lines (keeps the keys) for determinism comparison. */
internal fun maskTimeLines(evidence: String): String =
evidence.lineSequence().joinToString("\n") { line ->
val key = line.substringBefore('=')
if (line.contains('=') && key in MASKED_TIME_KEYS) key + "=<time>" else line
}
