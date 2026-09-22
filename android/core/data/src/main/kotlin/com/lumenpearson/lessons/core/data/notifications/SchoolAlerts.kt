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
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import java.time.Duration
import java.time.LocalDate
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
 * planner is asked for the window from the moment this alarm was armed for up to
 * now, and then for the next moment strictly after that window, so the windows
 * of consecutive firings abut rather than overlap. An alert can only be posted
 * twice if the clock goes backwards, and a clock change re-arms the chain from
 * scratch anyway.
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
     * How long before looking again when the planner sees nothing ahead.
     *
     * `AlertPlanner.next` looks a fixed distance ahead — a week and a day —
     * and cancelling on its `null` made the chain exactly as long as that
     * horizon. A gap wider than it ends the chain with nothing standing
     * behind it: the last alert before the winter holidays fires, the planner
     * finds nothing within eight days of it, the alarm is cancelled, and the
     * first morning back is silent. Nothing noticed, because a cancelled
     * chain and a chain with nothing due look identical from here.
     *
     * Twelve hours, so a fortnight of holidays costs about twenty-six
     * wake-ups and the chain is armed again well before the horizon reaches
     * the first lesson back. It is not a poll for data — the sync does that —
     * it is the chain refusing to end while the user still wants alerts.
     */
    private val HorizonRetry: Duration = Duration.ofHours(12)

    /**
     * How far back a late alarm may still publish what it was armed for.
     *
     * Without the exact-alarm permission the chain falls back to
     * `setAndAllowWhileIdle`, which is explicitly allowed to arrive late — and
     * [Tolerance] is ninety seconds, so a delivery any later than that used to
     * find nothing due, arm for the alert after it, and lose the one it was
     * woken for along with everything in between.
     *
     * Half an hour is the line between a reminder and a lie: "через 10 минут:
     * Алгебра" is worth posting four minutes late and is not worth posting once
     * the lesson has finished.
     */
    private val MaxCatchUp: Duration = Duration.ofMinutes(30)

    /**
     * Wall-clock moment the firing alarm was armed for, in the school's zone.
     *
     * Carried on the alarm rather than stored, because it is worth exactly one
     * delivery: a `PendingIntent` rebuilt with `FLAG_UPDATE_CURRENT` replaces
     * it, and nothing has to be cleaned up when the chain is cancelled.
     */
    private const val EXTRA_ARMED_FOR = "com.lumenpearson.lessons.extra.ARMED_FOR"

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
     * Makes sure an alarm is standing, without reading the schedule to find out.
     *
     * For the sync that got a `304`. [reschedule] would answer this question
     * too, but it answers it by reading the cache and re-deriving the whole
     * chain — a fortnight of days with their lessons, events and homework
     * since [read] was bounded, and every one of some two hundred days before
     * that — at the default hour-long interval twenty-four times a day, and
     * ninety-six at the fifteen-minute floor, on the one path that by
     * definition found nothing to be different. The question is not «what
     * should be armed», it is «is anything armed», and `FLAG_NO_CREATE`
     * answers that for a binder call and no I/O at all.
     *
     * What it gives up: this trusts the `PendingIntent`'s existence as proof
     * that an alarm stands. The two come apart if the system drops the alarm
     * and keeps the intent — some vendor battery managers are said to — and
     * there this will now sit quiet where a full re-plan would have healed it.
     * The cases it does still catch are the ones it was kept for: a force-stop
     * and a package replace take the pending intents with them, so the first
     * `304` after either finds nothing armed and re-plans. Everything else is
     * already carried by [NextAlarm.LookAgain], which is what keeps the chain
     * alive across a holiday without anybody asking.
     *
     * A phone with every alert switched off is armed for nothing on purpose,
     * so the preferences are asked before the cache is: without that, the one
     * user who has said they want no alerts would pay the full read every
     * poll, for ever, to be told what they had already said.
     */
    fun ensureArmed(context: Context) {
        val appContext = context.applicationContext
        val standing = pendingIntent(appContext, create = false) != null
        val replan = needsReplan(standing) { alertPreferences(appContext)?.silent }
        if (replan) reschedule(appContext)
    }

    /**
     * Whether a `304` has to fall back to the full re-plan.
     *
     * Pure, and split out for the same reason [nextAlarm] is: what it decides
     * is invisible from outside — a chain that is quiet because it is armed
     * and a chain that is quiet because it is gone look alike from every
     * screen — so the only place the difference can be held is a test.
     *
     * @param silent asked only when nothing is armed, which is what keeps the
     *   common answer free of I/O. `null` is an unreadable preferences file:
     *   re-plan, because the alternative is a chain that ends on a bad read.
     */
    internal fun needsReplan(alarmStanding: Boolean, silent: () -> Boolean?): Boolean =
        !alarmStanding && silent() != true

    /**
     * The user has just changed what they want to be told about.
     *
     * [reschedule] plus the shade, and the shade is the half that was missing:
     * switching an alert off cancelled the armed alarm and left what had
     * already been posted exactly where it was, so «Через 10 минут: Алгебра»
     * sat there — still tappable, still opening the app on that day — on the
     * shade of somebody who had at that moment said they did not want it. The
     * alarm and the shade are two states and only one of them was answered.
     *
     * Its own entry point rather than a line inside [reschedule], because
     * [reschedule] also runs after every sync, every boot and every app start,
     * and on those paths nothing the user did is being answered. The
     * difference is [AlertPreview], which posts under the same id and
     * deliberately ignores the preferences — «this is somebody asking» — so a
     * sample of a switched-off alert has every right to stay on the shade
     * until it is dismissed. Clearing it fifteen minutes later, from a
     * background sync, would be the app taking back what it was asked for.
     */
    fun onAlertsChanged(context: Context) {
        val appContext = context.applicationContext
        alertPreferences(appContext)?.let { preferences ->
            AlertNotifier.cancelDisabled(appContext, preferences)
        }
        reschedule(appContext)
    }

    /**
     * Posts whatever is due now, then arms for the next one.
     *
     * The re-arm happens even when nothing was due, which is what makes the
     * chain self-healing: a spurious wake-up, or one whose alert was filtered
     * out by a preference changed since it was armed, still leaves a correct
     * alarm behind it instead of ending the chain silently.
     */
    internal fun fire(context: Context, intent: Intent?) {
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
        val from = windowStart(now, armedFor(intent))
        AlertPlanner.due(timetable, preferences, from = from, to = now.plus(Tolerance)).forEach { alert ->
            // Each post guarded on its own, because the arming below is what
            // keeps the chain alive and posting runs first. `AlertNotifier.post`
            // is best-effort only past the `notify` call: the channel it creates
            // on the way, the locale it resolves and the plurals it formats all
            // throw freely. One bad alert therefore skipped `armNext` entirely,
            // and since the receiver only logs, no alarm was left armed — every
            // notification after it was lost until a sync, a reboot or somebody
            // opening the app. One lost notification is the cheap failure; the
            // chain going quiet for a week is not.
            runCatching { AlertNotifier.post(appContext, alert) }
                .onFailure { Log.w(TAG, "Could not post ${alert::class.java.simpleName}", it) }
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
        when (val next = nextAlarm(timetable, preferences, after)) {
            NextAlarm.None -> cancel(context)
            is NextAlarm.Alert -> arm(context, next.at, timetable.schoolClass.zone, armedFor = next.at)
            // No armed moment, for the reason [retryLater] carries none: this
            // alarm stands for nothing in the timetable, and a moment read back
            // as one would let a late delivery reach back over a window it was
            // never meant to speak for.
            is NextAlarm.LookAgain ->
                arm(context, next.at, timetable.schoolClass.zone, armedFor = null)
        }
    }

    /**
     * What the chain should be armed for next.
     *
     * Split out of [armNext] and pure, because the decision it makes is the
     * one that can end the chain silently and there is no way to see that
     * happen from outside: a cancelled alarm and an alarm that is simply not
     * due yet look the same from every screen in the app.
     */
    internal fun nextAlarm(
        timetable: Timetable,
        preferences: AlertPreferences,
        after: LocalDateTime,
    ): NextAlarm {
        // The one honest cancel. Every alert is switched off, so there is
        // nothing to come back for and waking twice a day to confirm it would
        // be the app disagreeing with what the user just said.
        if (preferences.silent) return NextAlarm.None
        val next = AlertPlanner.next(timetable, preferences, after)
        // Not a cancel. `next` answers within a fixed horizon, so `null` means
        // «nothing in the next week and a day», not «nothing ever» — the
        // difference between a holiday and a user who wants no alerts, and the
        // chain used to treat them alike. See [HorizonRetry].
        return if (next == null) {
            NextAlarm.LookAgain(after.plus(HorizonRetry))
        } else {
            NextAlarm.Alert(next.at)
        }
    }

    /**
     * Comes back to try again, when there was nothing to plan from.
     *
     * The device's own zone rather than the school's: the reason we are here is
     * that the school is precisely what could not be read.
     */
    private fun retryLater(context: Context) {
        // device clock: on purpose — the KDoc above this function is about it.
        val zone = ZoneId.systemDefault()
        // No armed moment: this alarm stands for nothing in the timetable, and a
        // device-zone moment read back as a school-zone one would widen the
        // catch-up window by the difference between the two.
        arm(context, LocalDateTime.now(zone).plus(ReadRetry), zone, armedFor = null)
    }

    /**
     * Where the window a firing alarm publishes begins.
     *
     * [armedFor] is what the alarm was set for; `null` when it carries no such
     * claim. The result never reaches closer than [Tolerance] to [now] (so an
     * alarm that arrives early still covers its own moment) and never further
     * back than [MaxCatchUp].
     */
    internal fun windowStart(now: LocalDateTime, armedFor: LocalDateTime?): LocalDateTime {
        val latest = now.minus(Tolerance)
        val earliest = now.minus(MaxCatchUp)
        val wanted = armedFor?.minus(Tolerance) ?: latest
        return when {
            wanted.isBefore(earliest) -> earliest
            wanted.isAfter(latest) -> latest
            else -> wanted
        }
    }

    /** The moment the delivering alarm was armed for, if it said. */
    private fun armedFor(intent: Intent?): LocalDateTime? = intent
        ?.getStringExtra(EXTRA_ARMED_FOR)
        ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

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
                    // The fingerprint is today and the six days after it, so
                    // the bounded read holds every date it will look at and
                    // hashes the same bytes the whole-year read hashed. It had
                    // better: a fingerprint that moved because the *reading*
                    // narrowed would announce «Расписание изменилось» to
                    // everybody, once, on the sync after the update.
                    val timetable = Graph.container.timetableRepository.snapshotAroundToday()
                    val current = timetable?.let { ScheduleFingerprint.of(it) }
                    if (current != null) {
                        val previous = preferences.scheduleFingerprint()
                        preferences.writeScheduleFingerprint(current)
                        if (ScheduleFingerprint.changed(previous, current)) {
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
        // This `runBlocking` re-enters the repository's own `ioDispatcher`,
        // because `snapshot` opens with `withContext(ioDispatcher)`. It is safe
        // on `Dispatchers.IO`, which is a pool of sixty-four and hands the work
        // to a thread other than the blocked one; it would deadlock on a
        // single-threaded dispatcher, so a test that swaps one in through the
        // graph has to keep at least two threads. Left as it is rather than
        // made suspend: every caller here is a receiver or an alarm, and none
        // of them has a scope to suspend in.
        runBlocking {
            // Bounded, not the whole cached year. Everything below plans a week
            // and a day ahead of a moment that may be half an hour in the past,
            // and `snapshotAroundToday` covers more than that on both sides —
            // including the far side of a holiday, which it carries as the
            // timetable's `nextSchoolDay`. That answer is searched for in the
            // cached year (`TimetableDao.firstTeachingDayAfter`) rather than
            // taken from the stored lookahead row, which is the fallback and
            // which, for the year-wide window this app fetches, the server
            // never sends. So the first morning back is reachable from inside
            // any holiday the cached year contains, and not from the last days
            // of May, where the year runs out and the next one has not been
            // fetched. What is not covered is reported as «no data», so nothing
            // here may ask about a date further out than the planner's horizon.
            val timetable = Graph.container.timetableRepository.snapshotAroundToday()
                ?: return@runBlocking null
            timetable to LessonsPreferences(context).currentSettings().alerts
        }
    }.onFailure { error -> Log.w(TAG, "Could not read the schedule", error) }.getOrNull()

    /**
     * What the user asked for, without the cache.
     *
     * Its own read because [read] answers `null` for the ordinary state of
     * having nothing cached, and one caller — tidying the shade — has a
     * correct answer in that state. DataStore serves this from memory after
     * the first read, so the second caller costs a thread hop.
     */
    private fun alertPreferences(context: Context): AlertPreferences? = runCatching {
        runBlocking { LessonsPreferences(context).currentSettings().alerts }
    }.onFailure { error -> Log.w(TAG, "Could not read the alert preferences", error) }.getOrNull()

    private fun arm(context: Context, at: LocalDateTime, zone: ZoneId, armedFor: LocalDateTime?) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val triggerAt = at.atZone(zone).toInstant().toEpochMilli()
        val operation = pendingIntent(context, create = true, armedFor = armedFor) ?: return

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

    private fun pendingIntent(
        context: Context,
        create: Boolean,
        armedFor: LocalDateTime? = null,
    ): PendingIntent? {
        val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE

        // The extra is deliberately not part of what identifies this alarm:
        // `Intent.filterEquals` ignores extras, so the cancel path still finds
        // the standing alarm however it was armed.
        val intent = Intent(context, SchoolAlertReceiver::class.java)
            .setAction(SchoolAlertReceiver.ACTION_ALERT)
        if (armedFor != null) intent.putExtra(EXTRA_ARMED_FOR, armedFor.toString())

        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}

/**
 * What the alarm chain should do next, as a value.
 *
 * Three answers and not two, which is the whole point of the type: «nothing
 * to wake for» and «nothing within the horizon» used to be one `null` out of
 * [AlertPlanner.next], and the chain answered both by cancelling itself.
 *
 * @see SchoolAlerts.nextAlarm
 */
internal sealed interface NextAlarm {

    /** Every alert is switched off. Nothing is armed, and that is correct. */
    data object None : NextAlarm

    /** An alert, at the moment it is due, in the school's wall time. */
    data class Alert(val at: LocalDateTime) : NextAlarm

    /**
     * Nothing the planner can see from here, so come back and ask again.
     *
     * The alarm stands for no alert: a delivery of it publishes nothing and
     * exists only to keep the chain alive across a gap wider than the
     * planner's horizon.
     */
    data class LookAgain(val at: LocalDateTime) : NextAlarm
}

/**
 * A short, stable description of the part of the schedule a change alert is
 * about.
 *
 * Only today and the next six days, and only the fields a pupil would call a
 * change: a re-sync that returns identical lessons with a new `syncedAt` must
 * not buzz anybody, and a lesson two weeks out being tidied up must not either.
 *
 * One hash **per day** rather than one over the whole window, and that is the
 * whole point of the shape. The window is anchored on today, so it slides at
 * every midnight: a single hash over it changed every night with nothing in the
 * schedule having moved, and the first sync of the morning announced
 * «Расписание изменилось» to everybody. Per day, [changed] can compare the days
 * the two readings have in common and ignore the one that rolled off the back.
 */
internal object ScheduleFingerprint {

    private const val Days = 7L

    /** Separates a date from its day hash; neither can contain it. */
    private const val Assign = '='

    fun of(timetable: Timetable): String = of(timetable, timetable.nowAtSchool().toLocalDate())

    /** @param today the anchor of the window, as the school reckons the date. */
    fun of(timetable: Timetable, today: LocalDate): String = buildString {
        for (offset in 0 until Days) {
            val date = today.plusDays(offset)
            val day = timetable.day(date) ?: continue
            append(date)
            append(Assign)
            append(hashOf(day))
            append('\n')
        }
    }

    /**
     * Whether anything a pupil would call a change happened between the two
     * readings.
     *
     * Only dates present in both count. A date in exactly one of them is either
     * the window having slid or the cache having grown, and neither is news; a
     * previous reading that cannot be parsed at all — the single-hash format
     * this replaced — has no dates in common and so says nothing, which is the
     * right first answer after an update.
     */
    fun changed(previous: String?, current: String): Boolean {
        val before = parse(previous ?: return false)
        val after = parse(current)
        return before.any { (date, hash) -> after[date]?.let { it != hash } == true }
    }

    private fun parse(value: String): Map<String, String> = value
        .lineSequence()
        .mapNotNull { line ->
            val split = line.indexOf(Assign)
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
        }
        .toMap()

    private fun hashOf(day: SchoolDay): Int = buildString {
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
    }.hashCode()
}
