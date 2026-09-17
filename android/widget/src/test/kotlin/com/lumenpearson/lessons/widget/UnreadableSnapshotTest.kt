package com.lumenpearson.lessons.widget

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the widget draws when its own read threw.
 *
 * Anything out of `provideGlance` makes Glance draw «Problem loading widget»
 * permanently, so the read is wrapped and an empty state is drawn instead. The
 * question this pins is which empty state: the flag chooses between two
 * sentences, and under a failed read nothing is known about the class, so the
 * choice is about which way to be wrong.
 */
class UnreadableSnapshotTest {

    @Test
    fun `a failed read does not tell a joined user to enter a class code`() {
        val snapshot = LessonsWidget().unreadableSnapshot()

        assertNull("nothing was read, so there is no state to draw", snapshot.state)
        assertTrue(
            "«введите код класса» sends somebody who has already joined to the one " +
                "screen that cannot help them; «потяните вниз» is harmless either way",
            snapshot.signedIn,
        )
    }
}
