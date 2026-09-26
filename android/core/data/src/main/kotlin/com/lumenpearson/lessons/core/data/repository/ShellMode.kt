package com.lumenpearson.lessons.core.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Which home the app opens on — decided from what is stored, by one rule.
 *
 *  * [CLASS]: the phone is in a class. Today, Week, the widget and the alerts
 *    are the class's, and the diary lives in Settings → «Дневник».
 *  * [DIARY]: no class, but a diary this phone signs in to. The diary is the
 *    home. Nothing else is fed: no timetable, no widget content, no alerts,
 *    and no background sync (see [syncArmingFor]).
 *  * [NONE]: neither. The way in.
 */
enum class ShellMode { NONE, CLASS, DIARY }

/**
 * The mode, and whether onboarding holds the screen.
 *
 * [held] exists because a way in writes its credential **before** it is
 * finished: the class-code step joins, the sign-in step registers, and either
 * write flips [mode] away from [ShellMode.NONE] while the flow still has an
 * import or a summary to show. The flag is persisted rather than remembered
 * by the screen, so a process death between the write and the summary brings
 * the flow back rather than dropping the family into a half-filled home.
 */
data class ShellState(val mode: ShellMode, val held: Boolean)

/**
 * A class outranks a diary: a phone in a class is in the app the class runs,
 * and its diary stays in settings. A diary *target* — not a live session — is
 * what makes [ShellMode.DIARY], because a bare `401` drops the bearer and
 * keeps the target, and a family whose session lapsed belongs on their diary's
 * sign-in form, not back at the start.
 *
 * This is also the answer to leaving the last class while signed in to a
 * diary: the phone lands on the diary home, where the diary and its sign-out
 * are, rather than on a join screen that reaches neither (#151).
 */
fun shellModeOf(classSession: Session?, diaryTarget: DiaryTarget?): ShellMode = when {
    classSession != null -> ShellMode.CLASS
    diaryTarget != null -> ShellMode.DIARY
    else -> ShellMode.NONE
}

/** Whether the periodic class sync should be installed, and at what interval. */
sealed interface SyncArming {
    data class Armed(val intervalMinutes: Int) : SyncArming
    data object Disarmed : SyncArming
}

/**
 * The periodic `SyncWorker` refreshes a class timetable, so it runs in a class
 * and nowhere else.
 *
 * This was a cold-start bug before it was a rule: the application armed the
 * worker on every launch whatever was stored, so a phone that had left its
 * last class — whose sign-out had just cancelled the worker — was woken every
 * fifteen minutes again from its next launch on, for a run that found no class
 * and returned (#152). A diary-only phone would have been woken the same way
 * for ever, and the diary is never read in the background anyway.
 */
fun syncArmingFor(mode: ShellMode, intervalMinutes: Int): SyncArming = when (mode) {
    ShellMode.CLASS -> SyncArming.Armed(intervalMinutes)
    ShellMode.DIARY, ShellMode.NONE -> SyncArming.Disarmed
}

/**
 * The stored mode, for the app shell, the widget and the application.
 *
 * Every value here is computed from **one** read of the preferences file, never
 * by combining a class flow with a diary flow: two flows can disagree for an
 * emission in between — a join half-seen — and a gate that flickered to the
 * wrong home for a frame would reset the state of the one it left.
 */
interface ShellModeSource {

    val state: Flow<ShellState>

    suspend fun current(): ShellState

    /** [syncArmingFor] of the stored mode and interval, one emission per change. */
    val syncArming: Flow<SyncArming>

    /** Onboarding keeps the screen from here on; set on entering the class-code or sign-in step. */
    suspend fun hold()

    /** Onboarding is finished with the screen. */
    suspend fun release()

    /**
     * For a cold start: a hold left over in [ShellMode.NONE] is dropped — the
     * credential it was waiting for never landed, so there is nothing for the
     * flow to finish and it starts from its beginning. Returns the state after.
     */
    suspend fun settleColdStart(): ShellState
}

/**
 * The diary store, announcing the two writes that change the mode — a
 * registration landing and a sign-out — so the widget, which draws a different
 * sentence per mode and redraws only when told, does not keep saying «введите
 * код класса» to a phone that just signed in to its diary.
 *
 * Only those two. A bare `401` keeps the target and so the mode, and the
 * diary's own rows are drawn by nothing outside the app.
 */
internal class ModeAnnouncingDiaryStore(
    private val delegate: DiarySessionStore,
    private val announce: () -> Unit,
) : DiarySessionStore by delegate {

    override suspend fun writeDiarySession(value: DiarySession) {
        delegate.writeDiarySession(value)
        announce()
    }

    override suspend fun forgetDiary() {
        delegate.forgetDiary()
        announce()
    }
}
