package com.lumenpearson.lessons.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update

/**
 * How many redraws this process has been asked for, so that a widget already on
 * screen can tell its snapshot is out of date (#168).
 *
 * [LessonsWidget] reads everything before `provideContent`, which is what keeps
 * its composable a pure function of one snapshot. But Glance keeps a session
 * alive for the better part of a minute after it starts, and `updateAll` on a
 * live session only recomposes — `provideGlance` does not run again — so a
 * redraw asked for inside that minute drew the snapshot the session started
 * with. On a phone that switched from one class to another and back within
 * seconds, the widget went on showing the class it had just left, for as long
 * as nothing else asked; the sync of the class on screen answered `304` and a
 * `304` asks nothing.
 *
 * The counter is the missing edge: every redraw bumps it, and a composition
 * that is still alive re-reads when it moves. A redraw that starts a session
 * instead reads the counter together with its first snapshot, so it does not
 * read twice.
 */
internal object WidgetRedraws {

    private val generation = MutableStateFlow(0L)

    /** The number of redraws asked for so far; only ever grows. */
    val current: StateFlow<Long> = generation.asStateFlow()

    /** Called before every `updateAll`, whoever asked for it. */
    fun ask() {
        generation.update { it + 1 }
    }
}

/**
 * A fresh [read] for every redraw asked for after [readAt], and nothing for the
 * one the snapshot on screen already answers.
 *
 * [readAt] is the value of [generation] taken *before* that snapshot was read,
 * so a redraw that lands between the read and the collection is still seen:
 * the state flow hands its latest value to a new collector, and that value is
 * no longer [readAt]. A newer redraw cancels a read still in progress, because
 * only the last answer is worth drawing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> rereadsAfter(
    generation: StateFlow<Long>,
    readAt: Long,
    read: suspend () -> T,
): Flow<T> = generation
    .filter { it != readAt }
    .mapLatest { read() }
