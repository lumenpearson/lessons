package com.lumenpearson.lessons.core.data.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.model.AlertPlanner
import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.Timetable
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking

/**
 * Keeps exactly one alarm armed, at the moment the next notification is due.
 *
 * The same shape as the widget's tick scheduler, and for the same reasons: a
 * repeating alarm every minute is four hundred wake-ups a day for something that
 * happens six times, and `WorkManager` cannot be asked to run at 08:20 with any
 * precision. So the chain is self-propelling — the alarm fires, posts whatever
 * is due, computes the next moment and arms for it.
 *
 * Nothing here remembers what it has already posted. It does not need to: the
 * planner is asked for a window around *now* and then for the next moment
 * strictly after that window, so an alert can only be posted twice if the clock
 * goes backwards, and a clock change re-arms the chain from scratch anyway.
 */
object SchoolAlerts {

    private const val TAG = "SchoolAlerts"
    private const val REQUEST_CODE = 0x5A10

    /**
     * How far either side of the armed moment an alert still counts as due.
     *
     * The alarm is inexact unless the OS grants exactness, so it can arrive up
     * to a minute late; and it can arrive a hair early. Ninety seconds swallows
     * both without ever reaching the next lesson, because no two lessons in a
     * real timetable start ninety seconds apart.
     */
    private val Tolerance: Duration = Duration.ofSeconds(90)

    /**
     * How long before trying again when there was nothing to plan from.
     *
     * Long enough that a device wedged on a failing read is not woken every
     * minute for it, short enough that a pupil does not lose a school day of
     * alerts to one bad moment.
     */
    private val ReadRetry: Duration = Duration.ofMinutes(20)

    /**
     * Recomputes everything from the cache and arms the next alarm.
     *
     * Safe to call from anywhere and as often as you like: each call replaces
     * the previous alarm rather than stacking one on it. Called after a sync,
     * after a settings change, at boot, and by the alarm itself.
     *
     * Blocking on purpose — every caller is already on a background thread (a
     * receiver inside `goAsync`, or the repository's IO dispatcher) and the read
     * is one indexed Room query plus an in-memory preferences lookup.
     */
    fun reschedule(context: Context) {
        val appContext = context.applicationContext
        val (timetable, preferences) = read(appContext) ?: return
        // Plain "now", not now + Tolerance. The offset belongs to the one caller
        // that has just published a window and must not publish it twice; see
        // [armNext]. Applied here it deleted alerts instead: this runs after
        // every sync, every settings change, every app start and every boot,
        // and arming replaces the standing alarm — so a sync landing within a
        // minute and a half of a bell overwrote that bell's alarm with the one
        // after it, and the notification was never posted.
        armNext(appContext, timetable, preferences, after = timetable.nowAtSchool())
    }

    /**
     * Posts whatever is due now, then arms for the next one.
     *
     * The re-arm happens even when nothing was due, which is what makes the
     * chain self-healing: a spurious wake-up, or one whose alert was filtered
     * out by a preference changed since it was armed, still leaves a correct
     * alarm behind it instead of ending the chain silently.
     */
    internal fun fire(context: Context) {
        val appContext = context.applicationContext
        val read = read(appContext)
        if (read == null) {
            // The chain is one alarm long, so returning here used to end it: a
            // locked database or a corrupt preference file at the moment an
            // alarm fired left nothing armed behind it, and the whole feature
            // stayed dead until some later sync happened to succeed. Nothing
            // said so, because a read failure is also the normal state before
            // the first sync. Leave a retry instead of a silence.
            retryLater(appContext)
            return
        }
        val (timetable, preferences) = read

        val now = timetable.nowAtSchool()
        AlertPlanner.due(timetable, preferences, now, Tolerance).forEach { alert ->
            AlertNotifier.post(appContext, alert)
        }
        // Strictly after the window just published, so the next alarm cannot
        // announce something this one already has. The same clock reading is
        // reused rather than taken again, which also closes the gap the second
        // read used to open.
        armNext(appContext, timetable, preferences, after = now.plus(Tolerance))
    }

    /**
     * Arms for the first alert strictly after [after], or cancels if there is
     * none.
     *
     * The one place that decides what "next" means, so that the offset which
     * only [fire] needs cannot leak into the callers that must not have it.
     */
    private fun armNext(
        context: Context,
        timetable: Timetable,
        preferences: AlertPreferences,
        after: LocalDateTime,
    ) {
        if (preferences.silent) {
            cancel(context)
            return
        }

        val next = AlertPlanner.next(timetable, preferences, after)
        if (next == null) {
            cancel(context)
            return
        }
        arm(context, next.at, timetable.schoolClass.zone)
    }

    /**
     * Comes back to try again, when there was nothing to plan from.
     *
     * The device's own zone rather than the school's: the reason we are here is
     * that the school is precisely what could not be read.
     */
    private fun retryLater(context: Context) {
        val zone = ZoneId.systemDefault()
        arm(context, LocalDateTime.now(zone).plus(ReadRetry), zone)
    }

    /**
     * Compares the cached schedule against the shape it had at the previous
     * sync, and says so if it moved.
     *
     * This is the one alert with no clock behind it, and the one a pupil
     * actually needs: a replacement posted at nine at night is worth knowing
     * about before the morning, and nothing else in the app would ever say.
     *
     * The first sync after installing sets the baseline and stays quiet — there
     * is nothing to have changed from, and "расписание изменилось" as the first
     * thing the app ever says is just noise.
     */
    fun onDataChanged(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            Graph.init(appContext)
            val preferences = LessonsPreferences(appContext)
            runBlocking {
                val settings = preferences.currentSettings()
                if (settings.alerts.scheduleChanges) {
                    val timetable = Graph.container.timetableRepository.snapshot()
                    val current = timetable?.let { ScheduleFingerprint.of(it) }
                    if (current != null) {
                        val previous = preferences.scheduleFingerprint()
                        preferences.writeScheduleFingerprint(current)
                        if (previous != null && previous != current) {
                            AlertNotifier.postScheduleChanged(appContext)
                        }
                    }
                }
            }
        }.onFailure { error -> Log.w(TAG, "Could not check the schedule for changes", error) }

        reschedule(appContext)
    }

    /**
     * Creates the channels and makes sure an alarm is armed.
     *
     * Called once per cold start. The chain re-arms itself and survives a
     * reboot through the receiver, but a broadcast can be missed — a phone that
     * was off during an update, a vendor that drops `MY_PACKAGE_REPLACED` —
     * and one cheap read at startup is what makes those cases recoverable by
     * opening the app rather than by reinstalling it.
     *
     * The channels are created even when everything is switched off, so that a
     * user who goes looking for them in the system settings finds them and can
     * decide about each one before the first notification arrives.
     */
    fun onAppStart(context: Context) {
        val appContext = context.applicationContext
        runCatching { AlertNotifier.ensureChannels(appContext) }
            .onFailure { error -> Log.w(TAG, "Could not create the channels", error) }
        reschedule(appContext)
    }

    /** Drops the armed alarm and clears anything still on the shade. */
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService<AlarmManager>()
        pendingIntent(appContext, create = false)?.let { operation ->
            alarmManager?.cancel(operation)
            operation.cancel()
        }
    }

    /** Everything the user signed out of; called when the session is cleared. */
    fun clear(context: Context) {
        cancel(context)
        AlertNotifier.cancelAll(context.applicationContext)
    }

    /**
     * Cache plus preferences, or null when there is nothing to plan from.
     *
     * A missing timetable is the normal state before the first sync and after a
     * sign-out, so it is not logged as a problem.
     */
    private fun read(context: Context): Pair<Timetable, AlertPreferences>? = runCatching {
        // The alarm can be the first thing to run after a process restart, so
        // the graph may not exist yet. Same reason SyncWorker opens with this.
        Graph.init(context)
        runBlocking {
            val timetable = Graph.container.timetableRepository.snapshot() ?: return@runBlocking null
            timetable to LessonsPreferences(context).currentSettings().alerts
        }
    }.onFailure { error -> Log.w(TAG, "Could not read the schedule", error) }.getOrNull()

    private fun arm(context: Context, at: LocalDateTime, zone: ZoneId) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val triggerAt = at.atZone(zone).toInstant().toEpochMilli()
        val operation = pendingIntent(context, create = true) ?: return

        try {
            if (canScheduleExact(alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                // Not setWindow: Doze holds a plain window until the next
                // maintenance pass, and because a firing alarm only publishes
                // what is due within 90 seconds of itself, one deferred that
                // far posts nothing at all and the alert is simply lost. This
                // is still inexact, but it is not deferred.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (error: SecurityException) {
            // The exact-alarm permission can be revoked between the check and
            // the call. A late notification beats a dead receiver.
            Log.w(TAG, "Exact alarm refused, falling back to an inexact one", error)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    /** @see com.lumenpearson.lessons.core.data.notifications.SchoolAlerts.arm */
    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(context: Context, create: Boolean): PendingIntent? {
        val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE

        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, SchoolAlertReceiver::class.java).setAction(SchoolAlertReceiver.ACTION_ALERT),
            flags,
        )
    }
}

/**
 * A short, stable description of the part of the schedule a change alert is
 * about.
 *
 * Only today and the next six days, and only the fields a pupil would call a
 * change: a re-sync that returns identical lessons with a new `syncedAt` must
 * not buzz anybody, and a lesson two weeks out being tidied up must not either.
 */
internal object ScheduleFingerprint {

    private const val Days = 7L

    fun of(timetable: Timetable): String {
        val today = timetable.nowAtSchool().toLocalDate()
        return buildString {
            for (offset in 0 until Days) {
                val day = timetable.day(today.plusDays(offset)) ?: continue
                append(day.date)
                append(':')
                append(day.kind)
                day.lessons.forEach { lesson ->
                    append('|')
                    append(lesson.index)
                    append(lesson.subject)
                    append(lesson.startsAt)
                    append(lesson.room.orEmpty())
                    if (lesson.isCancelled) append('X')
                    if (lesson.isReplaced) append('R')
                }
                append('\n')
            }
        }.hashCode().toString()
    }
}
