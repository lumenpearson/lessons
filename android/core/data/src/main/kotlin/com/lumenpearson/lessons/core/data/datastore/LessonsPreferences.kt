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
import com.lumenpearson.lessons.core.model.AppLanguage
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
    internal val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
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
    internal suspend fun write(transform: suspend (MutablePreferences) -> Unit) {
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
            prefs.putSettings(updated)
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
     * The tags and the fingerprint, which only the sync reads. Over this instance
     * rather than over the file, so that their writes go through [write] like every
     * other; declared after every other property, because it is handed `this`.
     */
    private val bundleTags = BundleTags(this)

    /** @see BundleTags.scheduleFingerprint */
    suspend fun scheduleFingerprint(): String? = bundleTags.scheduleFingerprint()

    /** @see BundleTags.scheduleFingerprint */
    suspend fun writeScheduleFingerprint(value: String) = bundleTags.writeScheduleFingerprint(value)

    /** @see BundleTags.bundleTag */
    suspend fun bundleTag(signature: String): String? = bundleTags.bundleTag(signature)

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

    /** @see BundleTags.bundleTag */
    suspend fun writeBundleTag(signature: String, etag: String) = bundleTags.writeBundleTag(signature, etag)

    /** @see BundleTags.bundleTag */
    suspend fun forgetBundleTag(signature: String) = bundleTags.forgetBundleTag(signature)

    /** @see BundleTags.forgetBundleTagsOf */
    suspend fun forgetBundleTagsOf(classId: Long) = bundleTags.forgetBundleTagsOf(classId)

    /** @see BundleTags.forgetBundleTagsOutside */
    suspend fun forgetBundleTagsOutside(keep: Collection<Long>) = bundleTags.forgetBundleTagsOutside(keep)

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
}

/**
 * The one place that deletes preference keys it does not name.
 *
 * The copy is required rather than cautious: `remove` writes to the very
 * map being iterated, and a `ConcurrentModificationException` inside `edit`
 * abandons the whole transaction — which for a sweep whose failure mode is
 * unbounded growth means it never happens at all.
 */
internal fun MutablePreferences.removeKeysStartingWith(matches: (String) -> Boolean) {
    asMap().keys.toList()
        .filter { key -> matches(key.name) }
        .forEach { key -> minusAssign(key) }
}
