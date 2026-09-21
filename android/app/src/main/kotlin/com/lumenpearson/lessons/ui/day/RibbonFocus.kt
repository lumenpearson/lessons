package com.lumenpearson.lessons.ui.day

import com.lumenpearson.lessons.core.model.Ribbon
import java.time.LocalTime

/**
 * Which row «вернуться к текущему» scrolls to, as an index into the ribbon.
 *
 * Pure, and separate from the screen, because it is the one piece of this view
 * that can be wrong in a way nobody would notice: a button that lands one row
 * off looks like a scroll that did not quite finish. [Ribbon.focusAt] already
 * decides *what* is current — this turns that into a position, and returns
 * `null` for the two cases where there is nothing to return to: a day that is
 * over, and a date that is not today at all.
 *
 * A day that is not today has no "now" on it. Scrolling such a day to the row
 * that would be running if it were today is the kind of helpfulness that makes
 * somebody check the date twice.
 */
internal fun ribbonFocusIndex(ribbon: Ribbon, at: LocalTime?): Int? {
    val entry = at?.let { ribbon.focusAt(it) } ?: return null
    return ribbon.entries.indexOf(entry).takeIf { it >= 0 }
}
