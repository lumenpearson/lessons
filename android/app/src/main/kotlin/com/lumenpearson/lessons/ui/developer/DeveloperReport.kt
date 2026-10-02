package com.lumenpearson.lessons.ui.developer

import com.lumenpearson.lessons.core.data.developer.CheckResult
import com.lumenpearson.lessons.core.data.developer.DeveloperAccess
import com.lumenpearson.lessons.core.data.diagnostics.ActivityEntry
import com.lumenpearson.lessons.core.data.diagnostics.NetworkEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The zone the developer page and its report print times in.
 *
 * The phone's, not the class's, and that is the exception `DeviceClockTest`
 * asks to be argued for: these are the phone's own records of its own
 * requests, read beside its system log and its owner's memory of what they
 * did — a crash report's reasoning, and `CrashReporter` makes the same call.
 */
internal fun deviceZone(): ZoneId =
    // device clock: the records are the phone's, and are read against its own logs.
    ZoneId.systemDefault()

/**
 * The developer page as one plain text (#237): what the access is, what the
 * checks said, and the two records, oldest first.
 *
 * English and technical, like a crash report's body — it is written for the
 * developer it is sent to, and it says nothing the records did not already
 * keep, which is the redaction `NetworkRedaction` already did.
 */
internal object DeveloperReport {

    private val Time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val Stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private const val MillisPerSecond = 1_000L

    fun text(
        state: DeveloperUiState,
        network: List<NetworkEntry>,
        activity: List<ActivityEntry>,
        nowMillis: Long,
        zone: ZoneId,
    ): String = buildString {
        appendLine("lessons — developer report, ${format(Stamp, nowMillis, zone)} (${zone.id})")
        appendLine("access: ${access(state.mode.access)}")
        appendLine("tools: ${state.mode.tools.joinToString { it.name.lowercase() }.ifEmpty { "none" }}")
        appendLine()
        appendLine("== checks")
        when (val checks = state.checks) {
            is ChecksState.Done -> checks.results.forEach { appendLine(checkLine(it)) }
            ChecksState.Running -> appendLine("running")
            ChecksState.Idle -> appendLine("not run")
        }
        appendLine()
        appendLine("== network, ${network.size} request(s)")
        network.forEach { appendLine(networkLine(it, nowMillis, zone)) }
        appendLine()
        appendLine("== activity, ${activity.size} event(s)")
        activity.forEach { entry ->
            appendLine("${format(Time, entry.atMillis, zone)}  ${entry.kind.name.lowercase()}  ${entry.text}")
        }
    }

    fun access(access: DeveloperAccess): String = when (access) {
        DeveloperAccess.SignedOut -> "signed out of GitHub"
        is DeveloperAccess.Checking -> "${access.login}, being checked"
        is DeveloperAccess.Granted -> "${access.login}, ${access.role.name.lowercase()}"
        is DeveloperAccess.Denied -> "${access.login}, no write access"
        is DeveloperAccess.Unknown -> "${access.login}, not known${access.reason?.let { " ($it)" }.orEmpty()}"
    }

    fun checkLine(result: CheckResult): String {
        val what = listOfNotNull(result.kind.name.lowercase(), result.subject).joinToString(" ")
        val took = result.tookMillis?.let { " ($it ms)" }.orEmpty()
        return "[${result.outcome}] $what — ${result.detail}$took"
    }

    fun networkLine(entry: NetworkEntry, nowMillis: Long, zone: ZoneId): String {
        val outcome = when {
            entry.inFlight -> "in flight for ${(nowMillis - entry.startedAtMillis) / MillisPerSecond} s"
            entry.failure != null -> "${entry.failure} after ${entry.tookMillis} ms"
            else -> "${entry.status} in ${entry.tookMillis} ms"
        }
        val headers = entry.headers.joinToString("") { (name, value) -> "  $name: $value" }
        return "${format(Time, entry.startedAtMillis, zone)}  ${entry.source.name.lowercase()}  " +
            "${entry.method} ${entry.host}${entry.path} → $outcome$headers"
    }

    private fun format(pattern: DateTimeFormatter, millis: Long, zone: ZoneId): String =
        pattern.format(Instant.ofEpochMilli(millis).atZone(zone))
}
