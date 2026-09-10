package com.lumenpearson.lessons.widget

import android.content.ComponentName
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.ScheduleEngine
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.widget.ui.LessonsWidgetBody
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

/**
 * The home-screen widget.
 *
 * Two decisions define this class:
 *
 * **All data is resolved before [provideContent].** The composable below is a
 * pure function of the snapshot it is handed, which means it can be previewed,
 * reasoned about, and unit-tested through [com.lumenpearson.lessons.widget.ui]
 * helpers. Fetching inside the composition — the pattern the reference app uses
 * — recomposes against a moving target and makes the tree impossible to test.
 *
 * **The clock is read exactly once per render.** Reading `LocalDateTime.now()`
 * in several places within one draw can straddle a bell and produce a widget
 * that says "Урок" above a countdown to the same lesson's start.
 */
class LessonsWidget : GlanceAppWidget() {

    /**
     * Responsive rather than Exact: Glance renders every breakpoint once and the
     * launcher picks between them locally, so resizing does not cost a process
     * wake-up and a database read.
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(WidgetSizeClass.breakpoints)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = loadSnapshot(context)

        provideContent {
            GlanceTheme {
                LessonsWidgetBody(
                    state = snapshot.state,
                    today = snapshot.today,
                    homeworkDay = snapshot.homeworkDay,
                    now = snapshot.now,
                    size = WidgetSizeClass.of(LocalSize.current),
                    options = snapshot.options,
                    onClick = openApp(context),
                )
            }
        }
    }

    /**
     * Everything one render needs, gathered off the composition.
     *
     * @property state null only when there is no cached timetable at all, which
     *   the body renders as the "enter a class code" prompt.
     */
    data class Snapshot(
        val now: LocalDateTime,
        val state: DayState?,
        val today: SchoolDay?,
        val homeworkDay: SchoolDay?,
        val options: WidgetOptions,
    )

    private companion object {

        /**
         * The activity a tap opens, addressed by name.
         *
         * `:widget` must not depend on `:app` — the dependency runs the other way
         * — so the class is named as a string. It is resolved by the launcher at
         * tap time; if `:app` ever renames MainActivity this is the one place
         * that has to follow, and the tap simply does nothing until it does.
         */
        const val MAIN_ACTIVITY = "com.lumenpearson.lessons.MainActivity"
    }

    // androidx.glance.action.actionStartActivity takes a ComponentName, not an
    // Intent; the Intent-accepting overload lives in the appwidget.action
    // package. ComponentName is the better fit anyway - Glance supplies the
    // launch flags a widget tap needs, so there is nothing left to configure.
    private fun openApp(context: Context): Action =
        actionStartActivity(ComponentName(context.packageName, MAIN_ACTIVITY))

    /**
     * Reads the cache and the user's widget settings, and derives the state.
     *
     * Both reads go to local storage — Room and DataStore — so this never blocks
     * on the network. That is the whole reason the widget keeps counting down in
     * a school basement.
     */
    private suspend fun loadSnapshot(context: Context): Snapshot {
        val container = Graph.container
        val timetable: Timetable? = container.timetableRepository.snapshot()
        val settings = container.settingsRepository.settings.first()

        // The school's wall clock, not the phone's. These differ whenever the
        // device has travelled, and permanently for anyone following a school
        // in another of Russia's eleven zones.
        val now = timetable?.nowAtSchool() ?: LocalDateTime.now()

        val state = timetable?.let { ScheduleEngine.stateAt(it, now) }
        return Snapshot(
            now = now,
            state = state,
            today = timetable?.day(now.toLocalDate()),
            homeworkDay = homeworkDayFor(state, timetable, now.toLocalDate()),
            options = WidgetOptions(
                showProgress = settings.widgetShowProgress,
                showTeacher = settings.showTeacher,
            ),
        )
    }
}
