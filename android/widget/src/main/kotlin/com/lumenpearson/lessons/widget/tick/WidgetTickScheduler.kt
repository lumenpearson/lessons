package com.lumenpearson.lessons.widget.tick

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.model.ScheduleEngine
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.widget.LessonsWidgetReceiver
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Arms exactly one alarm at a time, at the moment [TickCadence] says the widget
 * would next show something different.
 *
 * The alternative designs are both bad: `updatePeriodMillis` has a thirty-minute
 * floor, which cannot drive a countdown, and a one-minute repeating alarm is
 * roughly four hundred wake-ups a day for a surface the user glances at a dozen
 * times. This wakes on bells, and otherwise only as often as the displayed
 * number actually changes.
 */
object WidgetTickScheduler {

    private const val TAG = "WidgetTick"
    private const val REQUEST_CODE = 0x1E55

    /**
     * When to wake, as an absolute instant.
     *
     * @property tick the moment in the school's wall time, and whether it is a
     *   bell rather than a countdown refresh.
     * @property triggerAtMillis the same moment as epoch millis, which is what
     *   `AlarmManager` takes.
     */
    internal data class ArmedTick(val tick: WidgetTick, val triggerAtMillis: Long)

    /**
     * Recomputes the next tick from the cached timetable and arms for it.
     *
     * Safe to call from anywhere and as often as you like — each call replaces
     * the previous alarm rather than stacking one on top of it.
     */
    fun reschedule(context: Context) {
        val appContext = context.applicationContext

        // Nothing placed, nothing to wake up for. Every path into here is a
        // broadcast that arrives whether or not a widget exists — a sync, a
        // reboot, a timezone change — so without this check the cancel done by
        // `onDisabled` was undone by the next one of them, and a phone with no
        // widget on it kept waking every fifteen minutes for a countdown that
        // had nowhere to be drawn.
        if (!hasWidgets(appContext)) {
            cancel(appContext)
            return
        }

        // A blocking read is acceptable here: this runs on a broadcast worker
        // thread inside goAsync, and it is one indexed Room query.
        //
        // Bounded, because [plan] asks about exactly two dates — today, and the
        // next day with lessons — and the second of those comes across the
        // bound as `nextSchoolDay`. This runs on every tick of the chain it is
        // itself arming, so it was reading a school year to decide whether the
        // next wake-up is in one minute or in fifteen.
        val timetable = runCatching {
            kotlinx.coroutines.runBlocking {
                Graph.container.timetableRepository.snapshotAroundToday()
            }
        }.getOrNull()

        arm(appContext, plan(timetable))
    }

    /** Cancels the pending alarm, if any. */
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService<AlarmManager>() ?: return
        pendingIntent(appContext, mutable = false)?.let { intent ->
            alarmManager.cancel(intent)
            intent.cancel()
        }
    }

    /**
     * The pure half: which moment to wake at, and what instant that moment is.
     *
     * It asks the timetable two things and no more: `day(today)`, through
     * [ScheduleEngine.stateAt] and [ScheduleEngine.nextTransition], and
     * `schoolDayAfter(today)` for the homework a finished day points at. Both
     * are answerable from a bounded reading — the first because today is in
     * every bound, the second because it is carried over as `nextSchoolDay` —
     * which is why [reschedule] above reads a fortnight instead of a year.
     * `BoundedSnapshotParityTest` walks a school year past both readings of
     * this function and fails if one of them ever answers differently.
     *
     * Both halves have to agree about the zone. The widget renders from
     * [Timetable.nowAtSchool] — the schedule is stored as the school's wall
     * time, and Russia is eleven zones wide — so the state and the next bell are
     * derived in that zone, and the alarm is converted back out of it. Reading
     * `LocalDateTime.now()` here and arming through `ZoneId.systemDefault()`
     * instead lined a Moscow phone up against a Vladivostok school by seven
     * hours: every tick was computed for the wrong instant and armed for another
     * wrong one, so the widget redrew in the middle of lessons and stood still
     * through the bells.
     */
    internal fun plan(timetable: Timetable?, clock: Clock = Clock.systemUTC()): ArmedTick {
        // With no cache there is no school and no zone to be wrong about, so the
        // device's own is the only answer available.
        // device clock: the fallback, and the paragraph above is about exactly it.
        val zone = timetable?.schoolClass?.zone ?: ZoneId.systemDefault()
        val now = timetable?.atSchool(clock.instant()) ?: LocalDateTime.now(clock.withZone(zone))

        val state = timetable?.let { ScheduleEngine.stateAt(it, now) }
        val transition = timetable?.let { ScheduleEngine.nextTransition(it, now) }
        val tick = TickCadence.nextWakeUp(state = state, transition = transition, now = now)

        return ArmedTick(tick = tick, triggerAtMillis = tick.at.atZone(zone).toInstant().toEpochMilli())
    }

    /** Whether the launcher still holds at least one instance of the widget. */
    private fun hasWidgets(context: Context): Boolean = runCatching {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, LessonsWidgetReceiver::class.java))
            .isNotEmpty()
    }.getOrDefault(true)

    private fun arm(context: Context, armed: ArmedTick) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val triggerAt = armed.triggerAtMillis
        val operation = pendingIntent(context, mutable = false, create = true) ?: return

        // setExactAndAllowWhileIdle is reserved for bells, and only when the OS
        // is willing. Everything else is inexact — but not deferrable.
        //
        // Not setWindow, which is what this used to be: Doze holds a plain
        // window until the next maintenance pass, and only one alarm exists at
        // a time. The exact bell alarm is armed *by the tick before it*, so a
        // countdown tick held by Doze means the 08:30 boundary is never armed
        // at all and the chain stops advancing until something else wakes the
        // device. At 07:00 on a phone lying face down, that is the whole school
        // day. `SchoolAlerts.arm` refuses setWindow for the same reason.
        val wantsExact = armed.tick.isBoundary && canScheduleExact(alarmManager)
        try {
            if (wantsExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operation,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (error: SecurityException) {
            // The exact-alarm permission can be revoked between the check above
            // and this call. Degrading is always better than crashing a receiver.
            android.util.Log.w(TAG, "Exact alarm refused, falling back to an inexact one", error)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    /**
     * API 31 made exact alarms a user-grantable permission.
     *
     * This app does request it — `:app`'s manifest declares `SCHEDULE_EXACT_ALARM`
     * and `USE_EXACT_ALARM`, because it is distributed as an APK inside a school
     * rather than through Play, where the policy reserving the second of those
     * for clock and calendar apps would apply. An earlier version of this
     * comment claimed the opposite and was wrong; the manifest is the authority
     * and the widget's own manifest already says so.
     *
     * The check stays because the grant can be absent on a sideloaded build or
     * revoked by the user, and a bell degraded to a one-minute window is barely
     * visible while a crashed receiver stops the chain for good.
     */
    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(
        context: Context,
        mutable: Boolean,
        create: Boolean = false,
    ): PendingIntent? {
        val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, WidgetTickReceiver::class.java).setAction(WidgetTickReceiver.ACTION_TICK),
            flags,
        )
    }
}
