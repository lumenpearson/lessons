package com.lumenpearson.lessons.core.designsystem.text

import androidx.compose.runtime.Immutable

/**
 * A line in two parts: the app's own words and the data they introduce.
 *
 * «Домашнее задание на понедельник, 5 октября» is [lead] «Домашнее задание на »
 * and [data] «понедельник, 5 октября». A one-line component that cannot fit it
 * scrolls the data and leaves the lead where it is (#251): the words are the
 * part a reader already knows, and the day is the part that changes. Before,
 * the whole sentence slid away together.
 *
 * A line with no lead is just its data, and draws exactly as a plain string.
 */
@Immutable
data class DataLine(val lead: String, val data: String) {

    /** The sentence as written. */
    val whole: String get() = lead + data

    companion object {

        /** A line that is data from its first character — a subject, a name, a day. */
        fun of(text: String): DataLine = DataLine(lead = "", data = text)
    }
}

/**
 * [whole] split at the length of [pattern]'s words before its first argument.
 *
 * No split at all when those words are blank — a line that starts with its data
 * scrolls as one piece, which is what it is — or when [whole] does not start
 * with them, which is a correction that no longer formats and fell back to the
 * shipped pattern: drawn whole rather than cut in the wrong place.
 */
internal fun splitLine(pattern: String, whole: String): DataLine {
    val lead = leadOf(pattern)
    return if (lead.isNotBlank() && whole.length > lead.length && whole.startsWith(lead)) {
        DataLine(lead = lead, data = whole.substring(lead.length))
    } else {
        DataLine.of(whole)
    }
}

/**
 * The words of a format [pattern] before its first argument, as they are drawn:
 * `%%` is a percent sign and not an argument, and `%n` is a line break the
 * one-line components never draw, so both are left in the lead.
 */
internal fun leadOf(pattern: String): String {
    val lead = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        val next = pattern.getOrNull(i + 1)
        when {
            c != '%' || next == null -> {
                lead.append(c)
                i++
            }
            next == '%' -> {
                lead.append('%')
                i += 2
            }
            next == 'n' -> {
                lead.append('\n')
                i += 2
            }
            else -> return lead.toString()
        }
    }
    return lead.toString()
}
