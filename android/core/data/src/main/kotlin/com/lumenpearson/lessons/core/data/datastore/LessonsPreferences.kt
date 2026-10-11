package com.lumenpearson.lessons.core.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiarySessionStore
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.data.repository.SettingsStore
import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.data.repository.ShellModeSource
import com.lumenpearson.lessons.core.data.repository.ShellState
import com.lumenpearson.lessons.core.data.repository.SyncArming
import com.lumenpearson.lessons.core.data.repository.syncArmingFor
import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.AppFont
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DayFilter
import com.lumenpearson.lessons.core.model.DayOrder
import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.core.model.LessonAlertDetail
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.core.model.DayMode
import com.lumenpearson.lessons.core.model.RibbonFlow
import com.lumenpearson.lessons.core.model.ThemeMode
import com.lumenpearson.lessons.core.model.TodayLayout
import com.lumenpearson.lessons.core.model.WeekStart
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * One preferences file for the whole app. DataStore forbids opening the same
 * file twice in a process, so the delegate lives here, at top level, and
 * [LessonsPreferences] is the only thing that touches it.
 */
private val Context.lessonsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lessons",
    // The read side degrades a corrupt file to defaults, but every `edit` reads
    // the file first and rethrows, so without this a single truncated write —
    // the phone losing power mid-fsync — left a store that could never be
    // written to again: no sign-in, no settings, no sign-out, for the life of
    // the install. Replacing the file loses what was in it, which is what a
    // corrupt file has already done.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    // Seals whatever bearer an older install left bare, before the first read
    // of the process is served; see [TokenSealing].
    produceMigrations = { listOf(TokenSealing(TokenVault.platform)) },
)

/**
 * Typed access to the key-value store behind [Session] and [AppSettings].
 *
 * Session and settings share a file on purpose: they are written from the same
 * screens, they are both tiny, and a single file means a single fsync and a
 * single flow to observe.
 *
 * The two bearers in it are sealed ([TokenVault], #201): every write seals the
 * one it stores, every read that hands out a [Session] or a [DiarySession]
 * opens them, and nothing else here ever sees one.
 *
 * @param dataStore the app's one file on a phone. A test hands in a store of
 *   its own, which is the only reason this is not built from the context here.
 * @param vault what seals and opens the bearers — the phone's Keystore, or a
 *   key held in memory in a test.
 * @param io where the Keystore is asked; see [opened].
 */
internal class LessonsPreferences(
    private val dataStore: DataStore<Preferences>,
    private val vault: TokenVault,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DiarySessionStore, ShellModeSource, SettingsStore {

    constructor(context: Context) : this(context.applicationContext.lessonsDataStore, TokenVault.platform)

    /**
     * A corrupt or unreadable file must not take the app down: it degrades to
     * "no session, default settings", which lands the user on the join screen -
     * recoverable, unlike a crash loop at startup.
     */
    private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    /**
     * The same emissions with every bearer in them already opened, on [io].
     *
     * Everything that reads a token reads through this, and only that: opening
     * one is a Keystore call, a binder transaction that would otherwise run on
     * whatever thread collects — the main one, for a screen. Here it runs once
     * per sealed value per process (the vault remembers), before anything maps
     * the emission, and the mappings downstream find it remembered. The settings
     * and the rest of the file hold no token and stay on [preferences].
     */
    private val opened: Flow<Preferences> = preferences
        .onEach { it.openEveryToken(vault) }
        .flowOn(io)

    /**
     * What the OkHttp interceptors read — the address and both bearers, held in
     * memory; see [CredentialsSnapshot]. Every [write] brings it up to date
     * before returning, and [followCredentials] keeps it there for writes made
     * through another instance. Its bearers are opened here, when it is
     * refreshed, and never per request.
     */
    val credentials = CredentialsSnapshot { opened.first().requestCredentials() }

    /** Runs for as long as the file can emit; the container launches it once. */
    suspend fun followCredentials() = credentials.follow(preferences)

    /**
     * The only way anything here is written: DataStore's `edit`, then the copy
     * the interceptors read, before the caller moves on.
     *
     * Every write rather than the eight that move a credential today, so that a
     * ninth cannot be added without it. The refresh is not cancellable once the
     * edit has landed: a caller cancelled between the two would leave the next
     * request signed for the class it had just left, for as long as the
     * follower took to hear about it.
     *
     * On [io], because DataStore runs the transform in the caller's context and
     * two of them ask the Keystore: sealing the bearer a join or a sign-in
     * stores, and the mode [settleColdStart] reads.
     */
    private suspend fun write(transform: suspend (MutablePreferences) -> Unit) {
        withContext(io) {
            dataStore.edit(transform)
            withContext(NonCancellable) { credentials.refresh() }
        }
    }

    /** The class being shown, or `null` when this device is in none. */
    val session: Flow<Session?> = opened.map { it.openActiveMembership(vault) }.distinctUntilChanged()

    /** Every class this device has joined, in the order they were joined. */
    val sessions: Flow<List<Session>> = opened.map { it.openMemberships(vault) }.distinctUntilChanged()

    override val settings: Flow<AppSettings> = preferences.map { it.toSettings() }.distinctUntilChanged()

    suspend fun currentSession(): Session? = opened.first().openActiveMembership(vault)

    suspend fun currentSessions(): List<Session> = opened.first().openMemberships(vault)

    override suspend fun currentSettings(): AppSettings = preferences.first().toSettings()

    /**
     * Stores a membership and makes it the one being shown.
     *
     * Re-joining a class already on this phone replaces its token **in place**
     * rather than appending: the join code is how a pupil recovers from a
     * revoked device, and doing that must not leave the same class listed
     * twice, nor move it to the bottom of a list the user has got used to.
     *
     * Only the new token is sealed. The others are carried over as they are
     * stored, so a join never re-seals — or asks the Keystore about — a class
     * it is not about.
     */
    suspend fun addSession(value: Session) {
        write { prefs ->
            prefs.writeMemberships(prefs.memberships().withMembership(value.copy(token = vault.seal(value.token))))
            prefs.activate(value.classId)
        }
    }

    /**
     * Shows a different class. A class this device is not in is ignored rather
     * than stored: the active id is read back by matching it against the list,
     * and one that matches nothing would land the app on the join screen with
     * memberships still on disk.
     */
    suspend fun selectSession(classId: Long) {
        write { prefs ->
            if (prefs.memberships().none { it.classId == classId }) return@write
            prefs.activate(classId)
        }
    }

    /**
     * Drops one membership, keeping the rest.
     *
     * If it was the one being shown, the first of the remaining classes takes
     * over — there is no "no class selected" state to fall into while the phone
     * still belongs somewhere. Leaving the last one is a full sign-out, and
     * lands on the same keys [clearSession] would leave behind.
     */
    suspend fun removeSession(classId: Long) {
        write { prefs ->
            val remaining = prefs.memberships().filterNot { it.classId == classId }
            prefs.writeMemberships(remaining)
            prefs.remove(PreferenceKeys.roleKey(classId))
            val was = prefs[MembershipKeys.ACTIVE_CLASS_ID]
            val next = remaining.firstOrNull { it.classId == was } ?: remaining.firstOrNull()
            when {
                next == null -> {
                    prefs[PreferenceKeys.KEY_ONBOARDING_DONE] = true
                    prefs.remove(MembershipKeys.ACTIVE_CLASS_ID)
                    prefs.remove(PreferenceKeys.KEY_SCHEDULE_FINGERPRINT)
                }
                // Leaving a class that was not the one on screen changes
                // nothing about the one that is, so the fingerprint stays.
                // Clearing it would silently swallow the next «расписание
                // изменилось» for a class the user never touched: the baseline
                // would be re-set by the following sync, and a substitution that
                // arrived with it would be the thing that set it.
                next.classId == was -> Unit
                else -> prefs.activate(next.classId)
            }
        }
    }

    /**
     * Clears identity only; the server address stays so re-joining is one field.
     *
     * The diary keys are not in here on purpose. Leaving a class is not leaving
     * the diary: they are two accounts, and the one being signed out of is the
     * one the user pressed a button about. [forgetDiary] is the other
     * half, and it is just as narrow.
     */
    suspend fun clearSession() {
        write { prefs ->
            // Written on the way out, because the token this is about to remove
            // is what the read side uses to recognise somebody who predates the
            // introduction flag. To be here at all you were in a class, and to
            // have been in a class you got past the join screen.
            prefs[PreferenceKeys.KEY_ONBOARDING_DONE] = true
            // Both the list and the four keys it replaced: the old ones are
            // what an install from before this version is read from, and
            // leaving them would sign the user back in to that one class on the
            // next launch.
            prefs.clearMemberships()
            prefs.removeKeysStartingWith { it.startsWith(PreferenceKeys.ROLE_PREFIX) }
            // The fingerprint describes the shape of *that* class's schedule, so
            // keeping it means the first sync after joining a different one
            // compares two unrelated timetables, finds them different, and
            // announces that the schedule changed seconds after joining —
            // exactly the noise the baseline rule exists to prevent.
            prefs.remove(PreferenceKeys.KEY_SCHEDULE_FINGERPRINT)
        }
    }

    // ---------------------------------------------------------------------
    // The diary's own session
    // ---------------------------------------------------------------------
    //
    // Separate keys, separate reads, separate writes, and nothing below touches
    // the pair above. The two accounts are independent on the server — see
    // `current_diary` in `server/app/api/diary.py` — so leaving a class must
    // not sign a parent out of the diary, and signing out of the diary must not
    // unjoin the class. Writing them into one [Session] would have made that a
    // matter of remembering; two sets of keys makes it a matter of which method
    // is called.

    override val diarySession: Flow<DiarySession?> =
        opened.map { it.openDiarySession(vault) }.distinctUntilChanged()

    override suspend fun currentDiarySession(): DiarySession? =
        opened.first().openDiarySession(vault)

    override suspend fun writeDiarySession(value: DiarySession) {
        write { it.putDiarySession(value.copy(token = vault.seal(value.token))) }
    }

    /** A bare `401`; see [dropDiaryToken]. */
    override suspend fun clearDiaryToken() {
        write { it.dropDiaryToken() }
    }

    /** Signing out: the diary and only the diary; [clearSession] is its counterpart. */
    override suspend fun forgetDiary() {
        write { it.dropDiary() }
    }

    override val diaryTarget: Flow<DiaryTarget?> =
        preferences.map { it.toDiaryTarget() }.distinctUntilChanged()

    override suspend fun currentDiaryTarget(): DiaryTarget? = preferences.first().toDiaryTarget()

    override val selectedStudentId: Flow<Long?> =
        preferences.map { it[DiaryKeys.STUDENT_ID] }.distinctUntilChanged()

    override suspend fun selectStudent(id: Long?) {
        write { prefs ->
            if (id == null) prefs.remove(DiaryKeys.STUDENT_ID) else prefs[DiaryKeys.STUDENT_ID] = id
        }
    }

    // ---------------------------------------------------------------------
    // Which home the app opens on
    // ---------------------------------------------------------------------
    //
    // Every value below is read from one emission of the file: the class and
    // the diary are both in it, so the mode never has to be assembled from two
    // flows that can be caught between a write and its echo.

    override val state: Flow<ShellState> = opened.map { it.shellState(vault) }.distinctUntilChanged()

    override suspend fun current(): ShellState = opened.first().shellState(vault)

    override val syncArming: Flow<SyncArming> = opened
        .map { prefs -> syncArmingFor(prefs.shellState(vault).mode, prefs.toSettings().syncIntervalMinutes) }
        .distinctUntilChanged()

    override suspend fun hold() {
        write { it[ShellKeys.ONBOARDING_HELD] = true }
    }

    override suspend fun release() {
        write { it.remove(ShellKeys.ONBOARDING_HELD) }
    }

    override suspend fun settleColdStart(): ShellState {
        // Read and written in the one transaction, so a join landing between
        // a separate read and write cannot have its hold taken away.
        write { prefs ->
            if (prefs.shellState(vault).mode == ShellMode.NONE) prefs.remove(ShellKeys.ONBOARDING_HELD)
        }
        return current()
    }

    /** Read-modify-write inside DataStore's transaction, so concurrent edits merge. */
    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        write { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[PreferenceKeys.KEY_BASE_URL] = updated.baseUrl.trim()
            prefs[PreferenceKeys.KEY_THEME_MODE] = updated.themeMode.name
            prefs[PreferenceKeys.KEY_DYNAMIC_COLOR] = updated.dynamicColor
            prefs[PreferenceKeys.KEY_PITCH_BLACK] = updated.pitchBlack
            prefs[PreferenceKeys.KEY_HAPTICS] = updated.hapticsEnabled
            prefs[PreferenceKeys.KEY_HAPTIC_STRENGTH] = updated.hapticStrength.name
            prefs[PreferenceKeys.KEY_APP_FONT] = updated.appFont.name
            prefs[PreferenceKeys.KEY_LANGUAGE] = updated.language.name
            prefs[PreferenceKeys.KEY_TEXT_SCALE] = updated.textScale.coerceIn(AppSettings.TEXT_SCALE_RANGE)
            prefs[PreferenceKeys.KEY_ANIMATIONS] = updated.animations
            prefs[PreferenceKeys.KEY_MOTION_SPEED] = updated.motionSpeed.coerceIn(AppSettings.MOTION_SPEED_RANGE)
            prefs[PreferenceKeys.KEY_SWIPE_TABS] = updated.swipeTabs
            prefs[PreferenceKeys.KEY_DEFAULT_TAB] = updated.defaultTab.name
            prefs[PreferenceKeys.KEY_TAB_ORDER] = HomeTab.storedOrder(updated.tabOrder)
            prefs[PreferenceKeys.KEY_MOTION_BLUR] = updated.motionBlur
            prefs[PreferenceKeys.KEY_MOTION_BLUR_SCALE] = updated.motionBlurScale
                .coerceIn(AppSettings.MOTION_BLUR_SCALE_RANGE)
            prefs[PreferenceKeys.KEY_EDGE_BLUR] = updated.edgeBlur
            prefs[PreferenceKeys.KEY_SHOW_TEACHER] = updated.showTeacher
            prefs[PreferenceKeys.KEY_WIDGET_SHOW_PROGRESS] = updated.widgetShowProgress
            prefs[PreferenceKeys.KEY_TODAY_SHOW_HERO] = updated.todayShowHero
            prefs[PreferenceKeys.KEY_TODAY_LAYOUT] = updated.todayLayout.name
            prefs[PreferenceKeys.KEY_TODAY_WHOLE_DAY] = updated.todayWholeDay
            prefs[PreferenceKeys.KEY_TODAY_HOMEWORK_PREVIEW] =
                AppSettings.nearestHomeworkPreview(updated.todayHomeworkPreview)
            prefs[PreferenceKeys.KEY_TODAY_SHOW_EVENTS] = updated.todayShowEvents
            prefs[PreferenceKeys.KEY_WEEK_START] = updated.weekStart.name
            prefs[PreferenceKeys.KEY_WEEK_WEEKENDS] = updated.weekShowWeekends
            prefs[PreferenceKeys.KEY_WEEK_LOAD] = updated.weekShowLoad
            prefs[PreferenceKeys.KEY_WEEK_EVENTS] = updated.weekShowEvents
            prefs[PreferenceKeys.KEY_WEEK_HOMEWORK] = updated.weekShowHomework
            // Names rather than ordinals: an ordinal is a number that silently
            // means something else the day somebody reorders the enum, and a
            // stored filter that turned into a different one would be a
            // calendar quietly narrowed to the wrong thing.
            prefs[PreferenceKeys.KEY_CALENDAR_FILTERS] = updated.calendarFilters.map { it.name }.toSet()
            prefs[PreferenceKeys.KEY_CALENDAR_ORDER] = updated.calendarOrder.name
            prefs[PreferenceKeys.KEY_DAY_RIBBON_FLOW] = updated.dayRibbonFlow.name
            prefs[PreferenceKeys.KEY_DAY_MODE] = updated.dayMode.name
            prefs[PreferenceKeys.KEY_DAY_RIBBON_SNAP] = updated.dayRibbonSnap
            prefs[PreferenceKeys.KEY_DAY_RIBBON_DEPTH] = updated.dayRibbonDepth
            prefs[PreferenceKeys.KEY_DEBUG_MODE] = updated.debugMode
            prefs[PreferenceKeys.KEY_ONBOARDING_DONE] = updated.onboardingDone
            prefs[PreferenceKeys.KEY_ALERT_LESSON] = updated.alerts.lessonSoon
            prefs[PreferenceKeys.KEY_ALERT_LEAD] = updated.alerts.lessonLeadMinutes
            prefs[PreferenceKeys.KEY_ALERT_MORNING] = updated.alerts.morningSummary
            prefs[PreferenceKeys.KEY_ALERT_MORNING_AT] = updated.alerts.morningAtMinutes
            prefs[PreferenceKeys.KEY_ALERT_HOMEWORK] = updated.alerts.homeworkReminder
            prefs[PreferenceKeys.KEY_ALERT_HOMEWORK_AT] = updated.alerts.homeworkAtMinutes
            prefs[PreferenceKeys.KEY_ALERT_CHANGES] = updated.alerts.scheduleChanges
            prefs[PreferenceKeys.KEY_ALERT_LESSON_DETAIL] = updated.alerts.lessonDetail.name
            // Stored as a set of decimal weekday numbers. An empty set is a
            // real answer — "не показывать ни в один день" — and DataStore
            // keeps an empty set as a present key, so it survives the read
            // below rather than falling back to all seven.
            prefs[PreferenceKeys.KEY_ALERT_MORNING_DAYS] = updated.alerts.morningWeekdays.map(Int::toString).toSet()
            prefs[PreferenceKeys.KEY_ALERT_QUIET] = updated.alerts.quietHours
            prefs[PreferenceKeys.KEY_ALERT_QUIET_FROM] = updated.alerts.quietFromMinutes
            prefs[PreferenceKeys.KEY_ALERT_QUIET_TO] = updated.alerts.quietToMinutes
            prefs[PreferenceKeys.KEY_ALERT_SKIP_HOLIDAYS] = updated.alerts.skipHolidays
            prefs[PreferenceKeys.KEY_SYNC_INTERVAL] = updated.syncIntervalMinutes
                .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES)
            prefs[PreferenceKeys.KEY_RIPPLE_EFFECTS] = updated.rippleEffects
            prefs[PreferenceKeys.KEY_THEME_REVEAL] = updated.themeReveal
            prefs[PreferenceKeys.KEY_AUTO_CHECK_UPDATES] = updated.autoCheckUpdates
            prefs[PreferenceKeys.KEY_INCLUDE_PRERELEASE] = updated.includePrerelease
            prefs[PreferenceKeys.KEY_NOTIFY_UPDATES] = updated.notifyNewUpdates
        }
    }

    /**
     * The stored language, read the only way the caller can read it.
     *
     * `Activity.attachBaseContext` is where a per-app locale has to be applied
     * below API 33, and it cannot suspend and cannot wait for a flow: the base
     * context is already needed by the time the activity exists. The file is a
     * few hundred bytes and DataStore serves every read after the first from
     * memory, so this costs one disk read per process.
     */
    override fun languageBlocking(): AppLanguage = runBlocking { currentSettings().language }

    /**
     * The shape of the cached schedule as of the previous sync.
     *
     * Kept here rather than in [AppSettings] because it is not a preference and
     * nothing outside the notification code has any business reading it: it
     * exists only so a sync can tell "the timetable changed" from "the timetable
     * was fetched again".
     */
    suspend fun scheduleFingerprint(): String? = preferences.first()[PreferenceKeys.KEY_SCHEDULE_FINGERPRINT]

    /** @see scheduleFingerprint */
    suspend fun writeScheduleFingerprint(value: String) {
        write { prefs -> prefs[PreferenceKeys.KEY_SCHEDULE_FINGERPRINT] = value }
    }

    /**
     * The ETag of one `/bundle` window this phone holds, if it holds that one.
     *
     * A key per signature rather than one signature and one tag, because the
     * cache holds a school year per window now and a store with room for one
     * would make every step between two years a full download in both
     * directions. The signature is `classId|openingYear`, so a class switch or
     * another year is simply a different key and answers `null` — which asks
     * for the whole window, the direction that heals itself.
     *
     * Nothing here bounds the number of them; the three `forget…` calls below
     * do, one per shape of wipe the cache has — a year evicted, a class left,
     * a sign-out. The alternative is a preferences file that only ever grows,
     * one entry per class-year anybody ever synced, which is what this was
     * until leaving a class started reaching it.
     */
    suspend fun bundleTag(signature: String): String? =
        preferences.first()[PreferenceKeys.bundleTagKey(signature)]

    /**
     * The role the server last gave this phone in [classId], kept across
     * launches.
     *
     * The server is still the only judge — `/me` is asked again and its answer
     * replaces this — but a role known only in memory started every launch as
     * «unknown», and the settings root drew its bar without the debug button
     * until the answer came, or for good when it did not (#228). A stale role
     * can only show a manager's button to somebody who has just lost the role,
     * and every write behind that button is checked by the server per request.
     */
    fun lastRole(classId: Long): Flow<ClassRole?> =
        preferences.map { ClassRole.fromWire(it[PreferenceKeys.roleKey(classId)]) }.distinctUntilChanged()

    /** @see lastRole; `null` forgets it. */
    suspend fun rememberRole(classId: Long, role: ClassRole?) {
        write { prefs ->
            val key = PreferenceKeys.roleKey(classId)
            if (role == null) prefs.remove(key) else prefs[key] = role.name
        }
    }

    /** @see bundleTag */
    suspend fun writeBundleTag(signature: String, etag: String) {
        write { prefs -> prefs[PreferenceKeys.bundleTagKey(signature)] = etag }
    }

    /** @see bundleTag */
    suspend fun forgetBundleTag(signature: String) {
        write { prefs -> prefs.remove(PreferenceKeys.bundleTagKey(signature)) }
    }

    /**
     * Every year's tag for one class, for a class being left or re-joined.
     *
     * By key prefix rather than by asking which years are cached, because the
     * two can disagree in exactly the state this has to clean up: the tags
     * outlive a destructive Room migration and a `dao.clear`, and a tag whose
     * window row is already gone is precisely the one nothing else would find.
     *
     * @see bundleTag
     */
    suspend fun forgetBundleTagsOf(classId: Long) {
        val prefix = PreferenceKeys.bundleTagPrefix(classId)
        write { prefs -> prefs.removeKeysStartingWith { it.startsWith(prefix) } }
    }

    /**
     * The tags of every class outside [keep]; an empty [keep] is all of them.
     *
     * @see bundleTag
     */
    suspend fun forgetBundleTagsOutside(keep: Collection<Long>) {
        val kept = keep.map { PreferenceKeys.bundleTagPrefix(it) }
        write { prefs ->
            prefs.removeKeysStartingWith { name ->
                name.startsWith(PreferenceKeys.BUNDLE_TAG_PREFIX) && kept.none { name.startsWith(it) }
            }
        }
    }

    /**
     * The one place that deletes preference keys it does not name.
     *
     * The copy is required rather than cautious: `remove` writes to the very
     * map being iterated, and a `ConcurrentModificationException` inside `edit`
     * abandons the whole transaction — which for a sweep whose failure mode is
     * unbounded growth means it never happens at all.
     */
    private fun MutablePreferences.removeKeysStartingWith(matches: (String) -> Boolean) {
        asMap().keys.toList()
            .filter { key -> matches(key.name) }
            .forEach { key -> minusAssign(key) }
    }

    /**
     * Points the app at one of the stored classes.
     *
     * The fingerprint goes with it, for the reason [clearSession] spells out:
     * it describes the shape of the schedule that was on screen a moment ago,
     * and comparing the next class's timetable against it would announce that
     * the schedule changed the instant somebody switched classes.
     */
    private fun MutablePreferences.activate(classId: Long) {
        this[MembershipKeys.ACTIVE_CLASS_ID] = classId
        remove(PreferenceKeys.KEY_SCHEDULE_FINGERPRINT)
    }

    /**
     * The same three answers the screens get from [currentSettings],
     * [currentSession] and [currentDiarySession], from one emission.
     *
     * The two bearers stay two fields: one can be present while the other is
     * absent, and an interceptor that took the wrong one would send a class
     * token to a family's diary.
     *
     * Both opened: what the file holds is sealed, and a sealed value sent as a
     * bearer is a `401` that signs the phone out of a class it is still in.
     */
    private fun Preferences.requestCredentials(): RequestCredentials = RequestCredentials(
        baseUrl = toSettings().baseUrl,
        classToken = openActiveMembership(vault)?.token,
        diaryToken = openDiarySession(vault)?.token,
    )

    /**
     * Enums are stored by name rather than by ordinal, and unknown names fall
     * back to the default instead of throwing: reordering an enum must not be
     * able to silently change what a user already chose, and a value written by
     * a newer build must not crash an older one.
     */
    private fun Preferences.toSettings(): AppSettings = AppSettings(
        baseUrl = this[PreferenceKeys.KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_BASE_URL,
        themeMode = ThemeMode.fromName(this[PreferenceKeys.KEY_THEME_MODE]),
        dynamicColor = this[PreferenceKeys.KEY_DYNAMIC_COLOR] ?: true,
        pitchBlack = this[PreferenceKeys.KEY_PITCH_BLACK] ?: false,
        hapticsEnabled = this[PreferenceKeys.KEY_HAPTICS] ?: true,
        hapticStrength = HapticStrength.fromName(this[PreferenceKeys.KEY_HAPTIC_STRENGTH]),
        appFont = AppFont.fromName(this[PreferenceKeys.KEY_APP_FONT]),
        language = AppLanguage.fromName(this[PreferenceKeys.KEY_LANGUAGE]),
        textScale = (this[PreferenceKeys.KEY_TEXT_SCALE] ?: AppSettings.DEFAULT_TEXT_SCALE)
            .coerceIn(AppSettings.TEXT_SCALE_RANGE),
        animations = this[PreferenceKeys.KEY_ANIMATIONS] ?: true,
        motionSpeed = (this[PreferenceKeys.KEY_MOTION_SPEED] ?: AppSettings.DEFAULT_MOTION_SPEED)
            .coerceIn(AppSettings.MOTION_SPEED_RANGE),
        swipeTabs = this[PreferenceKeys.KEY_SWIPE_TABS] ?: true,
        defaultTab = HomeTab.fromName(this[PreferenceKeys.KEY_DEFAULT_TAB]),
        tabOrder = HomeTab.order(this[PreferenceKeys.KEY_TAB_ORDER]),
        motionBlur = this[PreferenceKeys.KEY_MOTION_BLUR] ?: false,
        motionBlurScale = (this[PreferenceKeys.KEY_MOTION_BLUR_SCALE] ?: AppSettings.DEFAULT_MOTION_BLUR_SCALE)
            .coerceIn(AppSettings.MOTION_BLUR_SCALE_RANGE),
        edgeBlur = this[PreferenceKeys.KEY_EDGE_BLUR] ?: true,
        showTeacher = this[PreferenceKeys.KEY_SHOW_TEACHER] ?: true,
        widgetShowProgress = this[PreferenceKeys.KEY_WIDGET_SHOW_PROGRESS] ?: true,
        todayShowHero = this[PreferenceKeys.KEY_TODAY_SHOW_HERO] ?: true,
        todayLayout = TodayLayout.fromName(this[PreferenceKeys.KEY_TODAY_LAYOUT]),
        todayWholeDay = this[PreferenceKeys.KEY_TODAY_WHOLE_DAY] ?: false,
        todayHomeworkPreview = AppSettings.nearestHomeworkPreview(
            this[PreferenceKeys.KEY_TODAY_HOMEWORK_PREVIEW] ?: AppSettings.DEFAULT_HOMEWORK_PREVIEW,
        ),
        todayShowEvents = this[PreferenceKeys.KEY_TODAY_SHOW_EVENTS] ?: true,
        weekStart = WeekStart.fromName(this[PreferenceKeys.KEY_WEEK_START]),
        weekShowWeekends = this[PreferenceKeys.KEY_WEEK_WEEKENDS] ?: true,
        weekShowLoad = this[PreferenceKeys.KEY_WEEK_LOAD] ?: true,
        weekShowEvents = this[PreferenceKeys.KEY_WEEK_EVENTS] ?: true,
        weekShowHomework = this[PreferenceKeys.KEY_WEEK_HOMEWORK] ?: true,
        // A name this build does not know is dropped rather than throwing: a
        // downgrade after a filter was added must narrow to less, not crash on
        // every read of the settings.
        calendarFilters = this[PreferenceKeys.KEY_CALENDAR_FILTERS]
            .orEmpty()
            .mapNotNull { name -> DayFilter.entries.firstOrNull { it.name == name } }
            .toSet(),
        calendarOrder = DayOrder.entries
            .firstOrNull { it.name == this[PreferenceKeys.KEY_CALENDAR_ORDER] }
            ?: DayOrder.DATE_ASC,
        dayRibbonFlow = RibbonFlow.fromName(this[PreferenceKeys.KEY_DAY_RIBBON_FLOW]),
        dayMode = DayMode.fromName(this[PreferenceKeys.KEY_DAY_MODE]),
        dayRibbonSnap = this[PreferenceKeys.KEY_DAY_RIBBON_SNAP] ?: true,
        dayRibbonDepth = this[PreferenceKeys.KEY_DAY_RIBBON_DEPTH] ?: true,
        debugMode = this[PreferenceKeys.KEY_DEBUG_MODE] ?: false,
        // False only for a genuinely fresh install. The key arrived with the
        // introduction, so on every phone that had the app before it there is
        // no value here — and a plain `?: false` therefore promised four
        // screens of introduction to everyone who had been using the app all
        // term, the first time they signed out.
        //
        // A stored class token is the sentinel because it cannot be there by
        // accident: it is only written after a successful join, which is the
        // screen the introduction ends on. Someone holding one has been past
        // it, whatever else they have or have not touched.
        //
        // Both keys, because the membership list replaced the flat token and
        // the phones this sentinel exists for are exactly the ones that predate
        // it: reading only `KEY_TOKEN` would promise four screens of
        // introduction to every long-standing user the first time their
        // memberships were rewritten as a list.
        onboardingDone = this[PreferenceKeys.KEY_ONBOARDING_DONE]
            ?: (this[MembershipKeys.TOKEN] != null || this[MembershipKeys.SESSIONS] != null),
        alerts = AlertPreferences(
            lessonSoon = this[PreferenceKeys.KEY_ALERT_LESSON] ?: false,
            lessonLeadMinutes = this[PreferenceKeys.KEY_ALERT_LEAD] ?: AlertPreferences.DefaultLeadMinutes,
            morningSummary = this[PreferenceKeys.KEY_ALERT_MORNING] ?: false,
            morningAtMinutes = this[PreferenceKeys.KEY_ALERT_MORNING_AT] ?: AlertPreferences.DefaultMorningMinutes,
            homeworkReminder = this[PreferenceKeys.KEY_ALERT_HOMEWORK] ?: false,
            homeworkAtMinutes = this[PreferenceKeys.KEY_ALERT_HOMEWORK_AT] ?: AlertPreferences.DefaultHomeworkMinutes,
            scheduleChanges = this[PreferenceKeys.KEY_ALERT_CHANGES] ?: false,
            lessonDetail = LessonAlertDetail.fromName(this[PreferenceKeys.KEY_ALERT_LESSON_DETAIL]),
            // Anything unparseable is dropped rather than defaulted: a set that
            // half survived an older build should lose the bad entries, not the
            // days the user actually picked.
            morningWeekdays = this[PreferenceKeys.KEY_ALERT_MORNING_DAYS]
                ?.mapNotNull { it.toIntOrNull()?.takeIf { day -> day in 1..7 } }
                ?.toSet()
                ?: AlertPreferences.AllWeekdays,
            quietHours = this[PreferenceKeys.KEY_ALERT_QUIET] ?: false,
            quietFromMinutes = this[PreferenceKeys.KEY_ALERT_QUIET_FROM] ?: AlertPreferences.DefaultQuietFromMinutes,
            quietToMinutes = this[PreferenceKeys.KEY_ALERT_QUIET_TO] ?: AlertPreferences.DefaultQuietToMinutes,
            skipHolidays = this[PreferenceKeys.KEY_ALERT_SKIP_HOLIDAYS] ?: true,
        ),
        syncIntervalMinutes = (this[PreferenceKeys.KEY_SYNC_INTERVAL] ?: AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES)
            .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES),
        rippleEffects = this[PreferenceKeys.KEY_RIPPLE_EFFECTS] ?: true,
        themeReveal = this[PreferenceKeys.KEY_THEME_REVEAL] ?: true,
        autoCheckUpdates = this[PreferenceKeys.KEY_AUTO_CHECK_UPDATES] ?: true,
        includePrerelease = this[PreferenceKeys.KEY_INCLUDE_PRERELEASE] ?: false,
        notifyNewUpdates = this[PreferenceKeys.KEY_NOTIFY_UPDATES] ?: true,
    )
}
