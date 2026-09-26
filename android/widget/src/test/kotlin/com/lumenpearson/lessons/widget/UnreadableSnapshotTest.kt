package com.lumenpearson.lessons.widget

import com.lumenpearson.lessons.core.data.repository.ShellMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the widget draws when its own read threw.
 *
 * Anything out of `provideGlance` makes Glance draw «Problem loading widget»
 * permanently, so the read is wrapped and an empty state is drawn instead. The
 * question this pins is which empty state: the mode chooses between three
 * sentences, and under a failed read nothing is known about the phone, so the
 * choice is about which way to be wrong.
 */
class UnreadableSnapshotTest {

    @Test
    fun `a failed read does not send a joined user back to a way in`() {
        val snapshot = LessonsWidget().unreadableSnapshot()

        assertNull("nothing was read, so there is no state to draw", snapshot.state)
        assertEquals(
            "the way-in sentence sends somebody who has already joined to a screen " +
                "that cannot help them, and the diary sentence tells a class family " +
                "the widget is not theirs; «потяните вниз» is harmless either way",
            ShellMode.CLASS,
            snapshot.mode,
        )
    }
}
