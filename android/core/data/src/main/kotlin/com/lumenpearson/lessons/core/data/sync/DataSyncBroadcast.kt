package com.lumenpearson.lessons.core.data.sync

import android.content.Context
import android.content.Intent

/**
 * The one-way notification that fresh data landed in the cache.
 *
 * It is a broadcast rather than a direct call because the alternative is a
 * dependency edge in the wrong direction: `:core:data` would have to know about
 * `:widget` in order to ask it to redraw, which would make the widget module
 * impossible to build or test on its own. The intent is the contract instead -
 * `:core:data` sends it, `:widget` registers a receiver for it, and neither
 * module compiles against the other.
 *
 * Explicit by package: an implicit broadcast would be blocked by the background
 * restrictions of API 26+ anyway, and would leak the fact that a sync happened
 * to every app on the device.
 *
 * Nothing about this is declared in this module's manifest on purpose; the
 * receiving module owns the `<receiver>` element.
 */
object DataSyncBroadcast {

    /** Must match the `<intent-filter>` the widget module registers. */
    const val ACTION: String = "com.lumenpearson.lessons.action.DATA_SYNCED"

    /** Fire-and-forget: no receiver is not an error, it just means no widget. */
    fun send(context: Context) {
        context.sendBroadcast(
            Intent(ACTION).setPackage(context.packageName),
        )
    }
}
