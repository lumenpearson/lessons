package com.lumenpearson.lessons.core.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a notification takes you when it is tapped.
 *
 * Each of the four carries the date it is about, and the date rides in an extra.
 * `Intent.filterEquals` — which is what decides whether two `PendingIntent`s are
 * the same one — ignores extras, and all four intents share an action and a
 * component, so the request code is the only thing that can tell them apart.
 * Share one, and `FLAG_UPDATE_CURRENT` rewrites the date of whatever is already
 * on the shade.
 */
class AlertTapTargetTest {

    @Test
    fun `no two notifications share a tap target`() {
        val codes = AlertNotifier.NotificationIds.map { AlertNotifier.tapRequestCode(it) }

        assertEquals(
            "These notifications share a PendingIntent, so the last one posted decides " +
                "which day every one of them opens: $codes",
            AlertNotifier.NotificationIds.size,
            codes.distinct().size,
        )
    }

    /** Four kinds, four ids; a fifth added without one would be caught above. */
    @Test
    fun `every kind is listed`() {
        assertEquals(4, AlertNotifier.NotificationIds.size)
        assertEquals(AlertNotifier.NotificationIds.size, AlertNotifier.NotificationIds.distinct().size)
    }
}
