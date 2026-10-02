package com.lumenpearson.lessons.core.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What an [ActivityEntry] is about; the developer page labels and colours by it. */
enum class ActivityKind {
    /** An activity of this app starting or stopping: the app coming to the front or leaving it. */
    LIFECYCLE,

    /** A screen or a page of the settings opened. */
    SCREEN,

    /** One step of a diary sign-in, with how long it took and how it ended. */
    SIGN_IN,

    /** A background sync run. */
    SYNC,

    /** The widget asked to redraw. */
    WIDGET,
}

/**
 * One thing the app did. [text] is technical and English — a step, a class
 * name, a duration — written for the developer reading it, like a crash
 * report's body, and never shown to anybody else.
 */
data class ActivityEntry(
    val id: Long,
    val atMillis: Long,
    val kind: ActivityKind,
    val text: String,
)

/**
 * The developer mode's record of what the app did, in order (#237).
 *
 * Process-wide and off by default, as [NetworkLog] is and for the same reasons:
 * the callers are in every module and in the background, and off it keeps
 * nothing. A caller that would build an expensive [ActivityEntry.text] asks
 * [isRecording] first; the ones that exist are a few words each.
 */
object ActivityLog {

    private const val MaxEntries = 300

    @Volatile
    private var recording = false

    private val lock = Any()
    private val buffer = ArrayDeque<ActivityEntry>()
    private var nextId = 0L

    private val mutable = MutableStateFlow<List<ActivityEntry>>(emptyList())

    /** Oldest first. */
    val entries: StateFlow<List<ActivityEntry>> = mutable.asStateFlow()

    val isRecording: Boolean get() = recording

    /** Turns recording on or off; off also forgets what was recorded. */
    fun setRecording(on: Boolean) {
        recording = on
        if (!on) clear()
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            mutable.value = emptyList()
        }
    }

    fun record(kind: ActivityKind, text: String) {
        if (!recording) return
        synchronized(lock) {
            buffer.addLast(ActivityEntry(nextId++, System.currentTimeMillis(), kind, text))
            while (buffer.size > MaxEntries) buffer.removeFirst()
            mutable.value = buffer.toList()
        }
    }

    /**
     * `register: ok in 312 ms`, or `register: Timeout in 25004 ms` — one step
     * and how it ended, the shape every sign-in step is recorded in.
     */
    fun outcome(step: String, failure: Throwable?, startedNanos: Long): String {
        val took = (System.nanoTime() - startedNanos) / NanosPerMilli
        val result = failure?.let { it::class.java.simpleName.ifEmpty { "failure" } } ?: "ok"
        return "$step: $result in $took ms"
    }

    private const val NanosPerMilli = 1_000_000L
}
