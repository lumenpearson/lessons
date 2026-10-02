package com.lumenpearson.lessons.ui.developer

/**
 * Seven taps on the version, the way Android's own «Номер сборки» opens its
 * developer options (#237).
 *
 * The taps have to come close together: a pause longer than [windowMillis]
 * starts the count again, so seven taps spread over a week of reading the
 * about page reveal nothing. Once revealed it counts from zero again, so the
 * next seven say it once more rather than staying silent.
 */
internal class RevealTaps(
    private val required: Int = RequiredTaps,
    private val windowMillis: Long = TapWindowMillis,
) {
    private var count = 0
    private var lastMillis = Long.MIN_VALUE

    /** One tap at [nowMillis]; `true` on the one that completes the run. */
    fun tap(nowMillis: Long): Boolean {
        val continues = lastMillis != Long.MIN_VALUE && nowMillis - lastMillis in 0..windowMillis
        count = if (continues) count + 1 else 1
        lastMillis = nowMillis
        if (count < required) return false
        count = 0
        lastMillis = Long.MIN_VALUE
        return true
    }

    private companion object {
        const val RequiredTaps = 7
        const val TapWindowMillis = 3_000L
    }
}
