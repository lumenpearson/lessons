package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up

/**
 * The gestures the toolbar's tests perform, with Compose's clock held still.
 *
 * Shared by the two test classes rather than copied into both, because every
 * one of them is here for a trap that took a while to find and a second copy
 * would be a second place to get it wrong.
 */

/**
 * Frames by hand, because the clock is held.
 *
 * `sendApplyNotifications` first: a value written from the test thread lands in
 * the global snapshot and nothing wakes the recomposer on its own, so advancing
 * frames alone reads the value that was there before the gesture. See
 * `CLAUDE.md`, which records what that cost to find.
 */
internal fun ComposeContentTestRule.settle(frames: Int = 6) {
    Snapshot.sendApplyNotifications()
    repeat(frames) { mainClock.advanceTimeByFrame() }
}

/**
 * A press held long enough to be a long press, with the clock stopped.
 *
 * Not `performTouchInput { longClick() }`, which is what this was first: that
 * helper puts the wait inside the *gesture's* timeline, and the timeout it has
 * to outlast is a `withTimeout` running on the composition's clock — which is
 * held here, so the press was released before the coroutine had aged a
 * millisecond and the long press simply never happened. The two clocks have to
 * be advanced separately, and this is the down, the wait and the up spelled out
 * so that the wait lands on the right one.
 */
internal fun ComposeContentTestRule.longPress(label: String) {
    onNodeWithContentDescription(label).performTouchInput { down(center) }
    settle()
    mainClock.advanceTimeBy(LongPressMillis)
    settle()
    onNodeWithContentDescription(label).performTouchInput { up() }
    settle()
}

/**
 * Drags a tab clean off the end of the bar, which parks it in the last slot.
 *
 * The distance is absurd on purpose: `dropIndex` coerces a finger that has left
 * the row to the end, so the outcome does not depend on the density Robolectric
 * reports or on how wide it decided the labels were. A drag of «one slot» would
 * be measuring this environment.
 *
 * The move is split in two because the first part of a drag pays for the touch
 * slop, and a single `moveBy` would have the whole journey start from wherever
 * the slop happened to be crossed.
 */
internal fun ComposeContentTestRule.dragFarRight(label: String) {
    onNodeWithContentDescription(label).performTouchInput {
        down(center)
        moveBy(Offset(SlopPx, 0f))
        moveBy(Offset(FarPx, 0f))
    }
    settle()
    onNodeWithContentDescription(label).performTouchInput { up() }
    // Long, because what is read afterwards is where the tabs *are*. A held tab
    // is drawn under the finger by a `graphicsLayer`, and `positionInRoot` —
    // which is what a bounds assertion goes through — counts that layer in. So
    // a row read six frames after the finger lifts is a row still mid-spring,
    // and a tab that had not moved at all would be sorted into the place the
    // drag had merely carried it. Which is to say: the assertion passed against
    // the defect this file exists for, until the springs were given time.
    settle(frames = 120)
}

/** The tabs left to right, by the slot each was laid out in. */
internal fun SemanticsNodeInteractionsProvider.drawnOrder(labels: List<String>): List<String> =
    labels.sortedBy { onNodeWithContentDescription(it).getUnclippedBoundsInRoot().left.value }

/** Comfortably past any platform's long-press timeout, which is 400–500 ms. */
private const val LongPressMillis = 1_000L

/** Comfortably past any platform's touch slop, which is about 8 dp. */
private const val SlopPx = 200f

/** And far enough past the end of the bar that nothing but the end is reachable. */
private const val FarPx = 10_000f
