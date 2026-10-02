package com.lumenpearson.lessons.ui.developer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.diagnostics.ActivityEntry
import com.lumenpearson.lessons.core.data.diagnostics.ActivityKind
import com.lumenpearson.lessons.core.data.diagnostics.NetworkEntry
import com.lumenpearson.lessons.core.data.diagnostics.NetworkSource
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.neutralTone
import kotlinx.coroutines.delay

/**
 * How many rows of a record the page draws. The record keeps more, and the
 * report carries all of it: a page of two hundred rows is one nobody reads.
 */
private const val ShownEntries = 50

private const val TickMillis = 1_000L
private const val MillisPerSecond = 1_000L

/** The first status that is not a success; a 3xx is an answer, not a failure. */
private const val FirstErrorStatus = 400

/**
 * The network record, newest first. A request still in flight shows how long
 * it has been out, ticking — the row a sign-in that shows nothing is waiting
 * on (#236).
 */
@Composable
internal fun NetworkGroup(entries: List<NetworkEntry>, recording: Boolean, onClear: () -> Unit) {
    val inFlight = entries.any { it.inFlight }
    // Ticks only while something is out, so an idle page costs no frames.
    val now by produceState(System.currentTimeMillis(), inFlight) {
        while (inFlight) {
            value = System.currentTimeMillis()
            delay(TickMillis)
        }
    }
    RecordGroup(
        title = correctedString(R.string.developer_network_group),
        total = entries.size,
        recording = recording,
        onClear = onClear,
    ) {
        entries.asReversed().take(ShownEntries).forEach { entry -> NetworkRow(entry, now) }
    }
}

@Composable
private fun NetworkRow(entry: NetworkEntry, nowMillis: Long) {
    val outcome = when {
        entry.inFlight -> correctedString(
            R.string.developer_in_flight,
            ((nowMillis - entry.startedAtMillis) / MillisPerSecond).coerceAtLeast(0L),
        )
        else -> correctedString(
            R.string.developer_took_ms,
            entry.failure ?: "HTTP ${entry.status}",
            entry.tookMillis ?: 0L,
        )
    }
    val failed = entry.failure != null || (entry.status ?: 0) >= FirstErrorStatus
    GroupItem(
        title = "${entry.method} ${entry.path}",
        subtitle = "${sourceLabel(entry.source)} · ${entry.host} · ${clockTime(entry.startedAtMillis)} · $outcome",
        tone = when {
            failed -> errorTone()
            entry.inFlight -> accentTone(1)
            else -> neutralTone()
        },
    )
}

@Composable
private fun sourceLabel(source: NetworkSource): String = correctedString(
    when (source) {
        NetworkSource.SERVER -> R.string.developer_source_server
        NetworkSource.DIARY -> R.string.developer_source_diary
        NetworkSource.GITHUB -> R.string.developer_source_github
    },
)

/** The activity record, newest first. */
@Composable
internal fun ActivityGroup(entries: List<ActivityEntry>, recording: Boolean, onClear: () -> Unit) {
    RecordGroup(
        title = correctedString(R.string.developer_activity_group),
        total = entries.size,
        recording = recording,
        onClear = onClear,
    ) {
        entries.asReversed().take(ShownEntries).forEach { entry ->
            GroupItem(
                title = entry.text,
                subtitle = "${clockTime(entry.atMillis)} · ${kindLabel(entry.kind)}",
                tone = neutralTone(),
            )
        }
    }
}

@Composable
private fun kindLabel(kind: ActivityKind): String = correctedString(
    when (kind) {
        ActivityKind.LIFECYCLE -> R.string.developer_kind_lifecycle
        ActivityKind.SCREEN -> R.string.developer_kind_screen
        ActivityKind.SIGN_IN -> R.string.developer_kind_sign_in
        ActivityKind.SYNC -> R.string.developer_kind_sync
        ActivityKind.WIDGET -> R.string.developer_kind_widget
    },
)

/**
 * A record's group: its count, «Очистить», and why it is empty — off, or
 * nothing yet — when it is.
 */
@Composable
private fun RecordGroup(
    title: String,
    total: Int,
    recording: Boolean,
    onClear: () -> Unit,
    rows: @Composable () -> Unit,
) {
    DeveloperGroup(
        title = title,
        subtitle = correctedString(R.string.developer_record_count, total, minOf(total, ShownEntries)),
        actionLabel = correctedString(R.string.developer_clear).takeIf { total > 0 },
        onAction = onClear.takeIf { total > 0 },
    ) {
        when {
            total > 0 -> rows()
            recording -> GroupItem(title = correctedString(R.string.developer_record_empty), tone = neutralTone())
            else -> GroupItem(title = correctedString(R.string.developer_record_off), tone = neutralTone())
        }
    }
}
