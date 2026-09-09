package com.lumenpearson.lessons.widget.tick

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.model.ScheduleEngine
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
     * How wide a window an inexact alarm may drift.
     *
     * Only used for countdown refreshes, never for a bell: being a minute late
     * on "осталось 12 мин" costs nothing, being a minute late on "Перемена"
     * makes the widget wrong.
     */
    private const val INEXACT_WINDOW_MILLIS = 60_000L

    /**
     * Recomputes the next tick from the cached timetable and arms for it.
     *
     * Safe to call from anywhere and as often as you like — each call replaces
     * the previous alarm rather than stacking one on top of it.
     */
    fun reschedule(context: Context) {
        val appContext = context.applicationContext
        val now = LocalDateTime.now()

        // A blocking read is acceptable here: this runs on a broadcast worker
        // thread inside goAsync, and it is one indexed Room query.
        val timetable = runCatching {
            kotlinx.coroutines.runBlocking {
                Graph.container.timetableRepository.snapshot()
            }
        }.getOrNull()

        val state = timetable?.let { ScheduleEngine.stateAt(it, now) }
        val transition = timetable?.let { ScheduleEngine.nextTransition(it, now) }
        val tick = TickCadence.nextWakeUp(state = state, transition = transition, now = now)

        arm(appContext, tick)
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

    private fun arm(context: Context, tick: WidgetTick) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val triggerAt = tick.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val operation = pendingIntent(context, mutable = false, create = true) ?: return

        // setExactAndAllowWhileIdle is reserved for bells, and only when the OS
        // is willing. Everything else is a window, which Doze can batch.
        val wantsExact = tick.isBoundary && canScheduleExact(alarmManager)
        try {
            if (wantsExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operation,
                )
            } else {
                alarmManager.setWindow(
                    AlarmManager.RTC,
                    triggerAt,
                    INEXACT_WINDOW_MILLIS,
                    operation,
                )
            }
        } catch (error: SecurityException) {
            // The exact-alarm permission can be revoked between the check above
            // and this call. Degrading is always better than crashing a receiver.
            android.util.Log.w(TAG, "Exact alarm refused, falling back to a window", error)
            alarmManager.setWindow(AlarmManager.RTC, triggerAt, INEXACT_WINDOW_MILLIS, operation)
        }
    }

    /**
     * API 31 made exact alarms a user-grantable permission, and this app
     * deliberately does not request it — a school diary is not a clock app, and
     * Play restricts the permission accordingly. A one-minute window at a bell
     * is invisible in practice.
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
