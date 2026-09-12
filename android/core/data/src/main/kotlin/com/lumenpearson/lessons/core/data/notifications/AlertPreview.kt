package com.lumenpearson.lessons.core.data.notifications

import android.content.Context
import com.lumenpearson.lessons.core.data.R
import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolAlert
import java.time.LocalDate
import java.time.LocalTime

/**
 * Posts one made-up lesson reminder, so that somebody can see what they have
 * just configured.
 *
 * The settings page can describe the lead time and the level of detail in
 * words, and the words are not the thing: what a person is actually deciding is
 * whether a line fits on their lock screen, whether the sound their phone makes
 * for this channel is the right one, and whether the reminder says enough. All
 * three are answered by one notification and by nothing else — and the
 * alternative, waiting until tomorrow morning to find out, is how a setting
 * gets configured wrongly for a term.
 *
 * It goes through [AlertNotifier] on the real channel with the real layout
 * rather than building a look-alike: a preview that is not the thing is a
 * preview of nothing. The cost is that it replaces a genuine lesson reminder
 * standing on the shade, which is the same thing the next real one would do.
 *
 * Quiet hours, the holiday rule and the weekday switches are deliberately *not*
 * applied. They decide whether the app may interrupt somebody who did not ask;
 * this is somebody asking.
 */
object AlertPreview {

    /**
     * @return false when the system would have dropped it — notifications are
     *   switched off for the app, or the runtime permission is not granted — so
     *   the caller can say so instead of leaving a button that appears to do
     *   nothing.
     */
    fun post(context: Context, preferences: AlertPreferences): Boolean {
        val appContext = context.applicationContext
        if (!AlertNotifier.canPost(appContext)) return false

        // Sat in the middle of a plausible school morning rather than at "now":
        // the notification shows no time of its own, and a sample lesson that
        // claims to start at 23:47 reads as a bug in the timetable.
        val lesson = Lesson(
            index = 1,
            subject = appContext.getString(R.string.alert_sample_subject),
            startsAt = SampleStart,
            endsAt = SampleEnd,
            room = appContext.getString(R.string.alert_sample_room),
            teacher = appContext.getString(R.string.alert_sample_teacher),
        )

        AlertNotifier.post(
            context = appContext,
            alert = SchoolAlert.LessonSoon(
                // Today, so that tapping the notification opens today rather
                // than a date the user has no reason to be looking at.
                at = LocalDate.now().atTime(SampleStart).minusMinutes(preferences.lessonLeadMinutes.toLong()),
                date = LocalDate.now(),
                lesson = lesson,
                leadMinutes = preferences.lessonLeadMinutes,
                detail = preferences.lessonDetail,
            ),
        )
        return true
    }

    private val SampleStart: LocalTime = LocalTime.of(8, 30)

    private val SampleEnd: LocalTime = LocalTime.of(9, 15)
}
