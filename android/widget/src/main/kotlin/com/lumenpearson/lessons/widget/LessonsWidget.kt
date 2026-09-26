package com.lumenpearson.lessons.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.locale.AppLocale
import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.data.repository.ShellModeSource
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.DeepLink
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.widget.ui.DayLoad
import com.lumenpearson.lessons.widget.ui.LessonsWidgetBody
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first

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
 *
 * The same holds for the language. Below Android 13 the app's chosen language
 * lives in a `Context` and in nothing wider, and the context a widget is
 * rendered from is the application's — which nothing has wrapped, because there
 * may be no activity alive and the render often happens in another process's
 * wake-up of ours. So the choice is read once, with the rest of the snapshot
 * that one render is built from, the context is wrapped once, and it is
 * published as Glance's own [LocalContext] — which is where every `getString`
 * in the tree below already reads its context from, so nothing else in the
 * widget had to learn about languages at all.
 */
class LessonsWidget : GlanceAppWidget() {

    /**
     * Responsive rather than Exact: Glance renders every breakpoint once and the
     * launcher picks between them locally, so resizing does not cost a process
     * wake-up and a database read.
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(WidgetSizeClass.breakpoints)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A failed read must not become "Problem loading widget" on somebody's
        // home screen. Anything thrown out of provideGlance — an uninitialised
        // graph, a corrupt Room file, a DataStore IO error — makes Glance draw
        // its error layout, permanently, where the honest empty state would
        // have told the user what to do and offered them a tap to do it.
        val snapshot = runCatching { loadSnapshot(context) }.getOrElse { unreadableSnapshot() }

        // Once per render, not once per string: the stored choice came back with
        // the snapshot above, and localized() hands the context straight back on
        // API 33+ and on "системный", where there is nothing to override.
        val localized = AppLocale.localized(context, snapshot.language)

        provideContent {
            CompositionLocalProvider(LocalContext provides localized) {
                GlanceTheme {
                    LessonsWidgetBody(
                        state = snapshot.state,
                        mode = snapshot.mode,
                        today = snapshot.today,
                        homeworkDay = snapshot.homeworkDay,
                        now = snapshot.now,
                        // The localized context, not the one handed in: the
                        // font scale is a property of the configuration the
                        // widget is actually drawn against.
                        size = WidgetSizeClass.of(
                            size = LocalSize.current,
                            fontScale = localized.resources.configuration.fontScale,
                        ),
                        week = snapshot.week,
                        options = snapshot.options,
                        onClick = openApp(context),
                        onDayClick = { date -> openDay(context, date) },
                    )
                }
            }
        }
    }

    /**
     * What to draw when the read itself failed.
     *
     * [ShellMode.CLASS] on purpose, although nothing was read and nothing is
     * known. The mode only chooses a sentence, and the three are not equally
     * wrong: «откройте приложение и потяните вниз» is harmless advice for
     * anybody, while the other two tell somebody who has joined a class either
     * to take a way in they have already taken or that the widget does not
     * draw their timetable — which is the exact failure [Snapshot.mode] exists
     * to prevent, reintroduced here on the path where it is least visible.
     */
    internal fun unreadableSnapshot(): Snapshot = Snapshot(
        // device clock: the read failed, so there is no timetable to take a zone
        // from. The empty state this builds names no lesson and no time.
        now = LocalDateTime.now(),
        state = null,
        mode = ShellMode.CLASS,
        today = null,
        homeworkDay = null,
        week = emptyList(),
        options = WidgetOptions(),
        language = AppLanguage.SYSTEM,
    )

    /**
     * Everything one render needs, gathered off the composition.
     *
     * @property state null whenever there is no cached timetable.
     * @property mode which way this phone came in, if any. Carried separately
     *   because a null [state] has three very different causes — nobody has
     *   come in yet, a class was joined but nothing has synced, or the phone
     *   reads its own school's diary and has no class timetable at all — and
     *   one sentence for all of them told the families of the second and the
     *   third to take a way in they had already taken.
     * @property language the stored language choice, carried here rather than
     *   read again at render time so that one snapshot means one preference
     *   read. It is not a `WidgetOption`: the options decide *what* is drawn and
     *   this decides which resources the drawing resolves through.
     */
    data class Snapshot(
        val now: LocalDateTime,
        val state: DayState?,
        val mode: ShellMode,
        val today: SchoolDay?,
        val homeworkDay: SchoolDay?,
        val week: List<DayLoad>,
        val options: WidgetOptions,
        val language: AppLanguage,
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
     * Opens the app on one particular day.
     *
     * The class comment above has always said the widget deep-links into a
     * screen; until the week strip existed it did not — every tap landed on
     * whatever tab the app was last on. A day chip is only worth tapping if it
     * takes you to that day.
     */
    private fun openDay(context: Context, date: LocalDate): Action = actionStartActivity(
        Intent(DeepLink.ACTION_OPEN_DAY)
            .setComponent(ComponentName(context.packageName, MAIN_ACTIVITY))
            .putExtra(DeepLink.EXTRA_DATE, date.toString()),
    )

    /**
     * Reads the cache and the user's widget settings, and derives the state.
     *
     * Both reads go to local storage — Room and DataStore — so this never blocks
     * on the network. That is the whole reason the widget keeps counting down in
     * a school basement.
     */
    private suspend fun loadSnapshot(context: Context): Snapshot {
        val container = Graph.container
        // The fortnight around today, not the school year. Every date this
        // render asks about is inside that bound — `snapshotOf` lists them —
        // and the one that is not, «what is the next school day», is carried
        // across it as the timetable's `nextSchoolDay`. The difference is some
        // two hundred days of lessons, events and homework read off Room on
        // every redraw, and a redraw happens on every tick of a countdown.
        //
        // The mode first, and the timetable only in a class. The widget draws
        // nothing from the diary on purpose (#142): a background read would
        // count as the family's activity and keep a session alive with nobody
        // behind it. Outside a class there is no class timetable to read, so
        // Room is not asked at all.
        val (mode, timetable) = readModeAndTimetable(container.shellMode) {
            container.timetableRepository.snapshotAroundToday()
        }
        val settings = container.settingsRepository.settings.first()

        // The school's wall clock, not the phone's. These differ whenever the
        // device has travelled, and permanently for anyone following a school
        // in another of Russia's eleven zones.
        // device clock: only when there is no timetable at all.
        val now = timetable?.nowAtSchool() ?: LocalDateTime.now()

        return snapshotOf(
            timetable = timetable,
            now = now,
            mode = mode,
            options = WidgetOptions(
                showProgress = settings.widgetShowProgress,
                showTeacher = settings.showTeacher,
                // Only one trailing detail fits a phone-width row, and the room
                // outranks the teacher — so with both on, the teacher never
                // appeared and the setting did nothing at all. They are the
                // same choice, so they are wired as one.
                showRoom = !settings.showTeacher,
            ),
            language = settings.language,
        )
    }
}

/**
 * The mode the widget draws for, and the class timetable only in a class.
 *
 * Out of [LessonsWidget.loadSnapshot], which reads `Graph` and cannot be handed
 * anything by a test, because this is the line the diary-only phone depends
 * on: read from the class session instead, as it once was, a phone signed in
 * to a diary and in no class drew the sentence sending it to join one.
 */
internal suspend fun readModeAndTimetable(
    shell: ShellModeSource,
    timetable: suspend () -> Timetable?,
): Pair<ShellMode, Timetable?> {
    val mode = shell.current().mode
    return mode to if (mode == ShellMode.CLASS) timetable() else null
}
