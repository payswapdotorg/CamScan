package org.payswap.camscan.ocr

import org.payswap.camscan.core.time.TimeSource

/**

Deterministic TimeSource fake for JVM tests: fixed (step 0) or stepping.

Implements the frozen contract surface [TimeSource.nowMillis]; never touches

the real clock or System time.
*/
class FakeTimeSource(
startMillis: Long = DEFAULT_START_MILLIS,
private val stepMillis: Long = 0L,
) : TimeSource {

private var currentMillis: Long = startMillis

override fun nowMillis(): Long {
val now = currentMillis
currentMillis += stepMillis
return now
}

private companion object {
const val DEFAULT_START_MILLIS = 1_000_000L
}

}
