package com.lumenpearson.lessons.core.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.lumenpearson.lessons.core.data.R
import com.lumenpearson.lessons.core.data.locale.AppLocale
import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.DeepLink
import com.lumenpearson.lessons.core.model.LessonAlertDetail
import com.lumenpearson.lessons.core.model.SchoolAlert
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Turns a planned [SchoolAlert] into a notification on the status bar.
 *
 * The split from `AlertPlanner` is deliberate and it is the reason the planner
 * can be tested by walking a school week in a loop: the planner decides *what*
 * and *when* with no Android on the classpath, and this decides what the
 * sentence says and which channel it lands on.
 *
 * Every post is best-effort. A receiver that throws because a channel was
 * deleted, or because the user revoked the notification permission between the
 * alarm being armed and it ringing, takes the process down with it — and this
 * one runs while the app is not on screen, so nobody would ever see why.
 *
 * Every entry point starts by putting the context into the app's own language
 * through [AppLocale] and then uses nothing else. Below Android 13 the language
 * a user chose is a property of a `Context` and of nothing wider, and the
 * context a notification is built from is the application's — reached from an
 * alarm broadcast, with no activity alive to have wrapped anything. Without
 * this, a pupil reading the app in English was told «Через 10 минут» by it. The
 * wrap is one stored-preference read per notification, not per string: it
 * happens once at the top and the wrapped context is what every `getString`
 * below is handed.
 */
internal object AlertNotifier {

    /**
     * Three channels, not one.
     *
     * A pupil who wants "через 10 минут алгебра" on the lock screen and does not
     * want a 20:00 homework nudge has to be able to say so, and the only place
     * Android lets them say it is per channel. One channel would mean the choice
     * is all or nothing, which in practice means nothing.
     */
    private const val CHANNEL_LESSONS = "alerts.lessons"
    private const val CHANNEL_DAILY = "alerts.daily"
    private const val CHANNEL_CHANGES = "alerts.changes"

    /**
     * Stable per kind, so a second morning summary replaces the first rather
     * than stacking. There is never anything to gain from two of these at once.
     *
     * Internal rather than private because [disabledIds] answers in them and
     * the rule about which of them a switched-off preference clears is worth a
     * test that can name them.
     */
    internal const val ID_LESSON = 0x5A01
    internal const val ID_MORNING = 0x5A02
    internal const val ID_HOMEWORK = 0x5A03
    internal const val ID_CHANGES = 0x5A04

    /** Every notification this object posts; see [tapRequestCode]. */
    internal val NotificationIds: List<Int> =
        listOf(ID_LESSON, ID_MORNING, ID_HOMEWORK, ID_CHANGES)

    /**
     * The request code of the tap target of the notification with [id].
     *
     * One per notification rather than one for the lot, and that is the whole
     * of it. The four intents differ only by the date they carry, in an extra —
     * and `Intent.filterEquals`, which is what decides whether two
     * `PendingIntent`s are the same one, ignores extras. Under a single request
     * code they were therefore one `PendingIntent`, and `FLAG_UPDATE_CURRENT`
     * rewrote its date every time anything was posted: the homework reminder at
     * eight sent that morning's summary to tomorrow, and «расписание
     * изменилось», which carries no date at all, stripped the date out of both
     * — so the notification opened whatever tab the app was last on.
     */
    internal fun tapRequestCode(id: Int): Int = id

    private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

    /**
     * Creates the channels if they are not there yet.
     *
     * Safe to call on every path that might post, because creating a channel
     * that exists is a no-op that does *not* overwrite the user's own choices
     * for it — which is exactly why importance is set here once and never
     * adjusted afterwards.
     *
     * The name and the description are the exception: they are the app's to
     * change, and the system does take the new ones. That is what makes the
     * three channels follow a language change at all — they are strings the
     * system stored once and shows in its own settings, and they are rewritten
     * in the chosen language the next time anything posts.
     */
    fun ensureChannels(context: Context) = ensureChannelsIn(AppLocale.localized(context))

    /** @param context already in the app's language; see [ensureChannels]. */
    private fun ensureChannelsIn(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            channel(
                context,
                CHANNEL_LESSONS,
                R.string.alert_channel_lessons,
                R.string.alert_channel_lessons_description,
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context,
                CHANNEL_DAILY,
                R.string.alert_channel_daily,
                R.string.alert_channel_daily_description,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context,
                CHANNEL_CHANGES,
                R.string.alert_channel_changes,
                R.string.alert_channel_changes_description,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** Posts [alert], or does nothing if the user has not granted the permission. */
    fun post(context: Context, alert: SchoolAlert) = postIn(AppLocale.localized(context), alert)

    /** @param context already in the app's language; see [post]. */
    private fun postIn(context: Context, alert: SchoolAlert) {
        if (!canPost(context)) return
        ensureChannelsIn(context)

        when (alert) {
            is SchoolAlert.LessonSoon -> show(
                context = context,
                id = ID_LESSON,
                channel = CHANNEL_LESSONS,
                title = context.resources.getQuantityString(
                    R.plurals.alert_lesson_title,
                    alert.leadMinutes,
                    alert.leadMinutes,
                ),
                body = lessonLine(context, alert),
                date = alert.date,
            )

            is SchoolAlert.Morning -> {
                val lessons = alert.day.activeLessons
                val first = lessons.firstOrNull() ?: return
                show(
                    context = context,
                    id = ID_MORNING,
                    channel = CHANNEL_DAILY,
                    title = context.resources.getQuantityString(
                        R.plurals.alert_morning_title,
                        lessons.size,
                        lessons.size,
                    ),
                    body = context.getString(
                        R.string.alert_morning_body,
                        first.subject,
                        first.startsAt.format(clockFormat),
                        lessons.last().endsAt.format(clockFormat),
                    ),
                    date = alert.day.date,
                )
            }

            is SchoolAlert.Homework -> {
                // Counted by subject, not by item: "задано по трём предметам" is
                // what a pupil is deciding about when they choose what to pack.
                val subjects = alert.day.homework.map { it.subject }.distinct()
                if (subjects.isEmpty()) return
                show(
                    context = context,
                    id = ID_HOMEWORK,
                    channel = CHANNEL_DAILY,
                    title = context.resources.getQuantityString(
                        R.plurals.alert_homework_title,
                        subjects.size,
                        subjects.size,
                    ),
                    body = subjects.joinToString(", "),
                    date = alert.day.date,
                )
            }
        }
    }

    /**
     * The one alert with no planned time: the schedule moved under the user's
     * feet and they are being told within seconds of the sync that found it.
     */
    fun postScheduleChanged(context: Context) = postScheduleChangedIn(AppLocale.localized(context))

    /** @param context already in the app's language; see [postScheduleChanged]. */
    private fun postScheduleChangedIn(context: Context) {
        if (!canPost(context)) return
        ensureChannelsIn(context)
        show(
            context = context,
            id = ID_CHANGES,
            channel = CHANNEL_CHANGES,
            title = context.getString(R.string.alert_changes_title),
            body = context.getString(R.string.alert_changes_body),
            date = null,
        )
    }

    /**
     * The notifications a user who has just switched something off should not
     * still be looking at.
     *
     * One id per preference, and deliberately not "all of them if the alerts
     * are silent": [AlertPreferences.silent] is about the three that are
     * *planned*, so reading it here would leave «расписание изменилось» on the
     * shade of somebody who turned the other three off, and would clear it for
     * somebody who turned the other three off while still wanting it. Each
     * kind answers for itself.
     *
     * Pure, and internal, so the boundary — what goes and what stays — is a
     * test rather than a reading of four `if`s inside an Android call.
     */
    internal fun disabledIds(preferences: AlertPreferences): List<Int> = buildList {
        if (!preferences.lessonSoon) add(ID_LESSON)
        if (!preferences.morningSummary) add(ID_MORNING)
        if (!preferences.homeworkReminder) add(ID_HOMEWORK)
        if (!preferences.scheduleChanges) add(ID_CHANGES)
    }

    /**
     * Drops whatever [disabledIds] names and nothing else.
     *
     * Switching an alert off cancelled the armed alarm and left what was
     * already posted where it was, so «Через 10 минут: Алгебра» sat on the
     * shade of somebody who had just said they did not want it — still
     * tappable, still opening the app on that day. The alarm and the shade are
     * two different states and only one of them was being answered.
     *
     * Best-effort like every other post: a receiver must not die for failing
     * to tidy up.
     */
    fun cancelDisabled(context: Context, preferences: AlertPreferences) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            disabledIds(preferences).forEach(manager::cancel)
        }
    }

    /** Clears anything still on the shade; used when the user signs out. */
    fun cancelAll(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).apply {
                cancel(ID_LESSON)
                cancel(ID_MORNING)
                cancel(ID_HOMEWORK)
                cancel(ID_CHANGES)
            }
        }
    }

    /**
     * The one line under "Через 10 минут", as much of it as the user asked for.
     *
     * "Замена" is appended whatever the detail level, because it is not a detail
     * of the lesson — it is the reason this notification is worth reading.
     */
    private fun lessonLine(context: Context, alert: SchoolAlert.LessonSoon): String {
        val subject = alert.lesson.subject
        val base = when (alert.detail) {
            LessonAlertDetail.SUBJECT -> subject

            LessonAlertDetail.FULL -> {
                val room = alert.lesson.room?.takeIf { it.isNotBlank() }
                val teacher = alert.lesson.teacher?.takeIf { it.isNotBlank() }
                val withRoom = if (room != null) {
                    context.getString(R.string.alert_lesson_room, subject, room)
                } else {
                    subject
                }
                // Nothing is invented for a lesson the server sent without a
                // room or a teacher: an empty "каб. —" is worse than the short
                // line the other setting would have given.
                if (teacher != null) {
                    context.getString(R.string.alert_lesson_teacher, withRoom, teacher)
                } else {
                    withRoom
                }
            }
        }
        return if (alert.lesson.isReplaced) {
            context.getString(R.string.alert_lesson_replaced, base)
        } else {
            base
        }
    }

    private fun show(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        body: String,
        date: LocalDate?,
    ) {
        // Checked again, here, one line from the call it guards. [canPost] has
        // already refused everything this would post, but a permission check
        // hidden behind a helper is invisible to the tooling that verifies it —
        // and to the next person deciding whether this method is safe to call.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alert_bell)
            .setContentTitle(title)
            .setContentText(body)
            // The body of a homework alert is a list of subjects and will not fit
            // on one line on any phone; expanding is the difference between
            // "задано по трём предметам" and knowing which three.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context, id, date))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /**
     * Opens the app, on the day the alert is about where there is one.
     *
     * Built by component name rather than by class reference: the activity lives
     * in `:app`, which depends on this module and not the other way round. The
     * same deep link the widget's day chips use, so there is one way in and one
     * intent contract to keep working.
     */
    private fun openApp(context: Context, id: Int, date: LocalDate?): PendingIntent? {
        val intent = Intent(DeepLink.ACTION_OPEN_DAY)
            .setComponent(ComponentName(context.packageName, MAIN_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (date != null) putExtra(DeepLink.EXTRA_DATE, date.toString()) }

        return runCatching {
            PendingIntent.getActivity(
                context,
                tapRequestCode(id),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
    }

    /**
     * From Android 13 posting is a runtime permission, and a notification posted
     * without it is dropped silently. Checking first is what lets the caller
     * skip the work rather than discover it went nowhere.
     *
     * The version guard is not decoration. `POST_NOTIFICATIONS` does not exist
     * before Android 13, so asking the package manager about it there returns
     * DENIED for every device — which would have turned notifications off on
     * every phone the app was actually written for. Below 13 the only question
     * worth asking is whether the user has switched them off themselves.
     */
    internal fun canPost(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun channel(
        context: Context,
        id: String,
        nameRes: Int,
        descriptionRes: Int,
        importance: Int,
    ): NotificationChannel = NotificationChannel(id, context.getString(nameRes), importance).apply {
        description = context.getString(descriptionRes)
    }

    /** Must match the activity `:app` declares; see [openApp]. */
    private const val MAIN_ACTIVITY = "com.lumenpearson.lessons.MainActivity"
}
