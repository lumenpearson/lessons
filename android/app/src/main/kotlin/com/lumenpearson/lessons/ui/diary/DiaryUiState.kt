package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the diary section is, as one state object.
 *
 * @property ready false only until the stored session has been read once. The
 *   screen shows placeholders rather than the sign-in form in that moment: a
 *   login page that flashes up for somebody who is already signed in is the
 *   worst frame this section can draw.
 * @property reauth the upstream session died. The login is still known, so the
 *   screen asks for the password alone — the case the server sends
 *   `X-Diary-Reauth: required` for, and the whole reason it is a header rather
 *   than one 401 for both meanings.
 * @property student the pupil everything below is about; `null` only while the
 *   list is still loading or empty.
 * @property zone the zone the diary cuts its days at — the session's, which the
 *   server named at registration. Moscow until a session says otherwise, which
 *   is what every session before the second diary was.
 * @property signInTarget which diary the form would sign in to: one picked on
 *   this screen, else the live session's, else the one a bare `401` left, else
 *   the class's binding, else Petersburg — the one diary this form ever offered
 *   before it could be asked for another. Its login is a placeholder; the typed
 *   one replaces it on submit.
 * @property place what the catalog says about [signInTarget] — its names and
 *   the host the password goes to — or `null` until the catalog has been read.
 * @property picking the diary picker is open in place of the form.
 * @property savedAt the rows on screen are what was saved at this moment,
 *   shown because the diary could not be reached just now; `null` when they
 *   are the diary's answer of this visit.
 */
data class DiaryUiState(
    val ready: Boolean = false,
    val session: DiarySession? = null,
    val reauth: Boolean = false,
    val signingIn: Boolean = false,
    /**
     * Why the last sign-in failed, for the form to paint itself by — cleared by
     * the next keystroke. The sentence is [signInOutcome]'s to say.
     */
    val signInError: DiarySignInProblem? = null,
    /**
     * What the last sign-in attempt came to, waiting to be shown once.
     *
     * Separate from [signInError] because the two answer different questions
     * and live for different lengths of time. [signInError] is the state of the
     * form — it paints the fields red and is cleared by the next keystroke.
     * This is an event: it is shown in a pop-up, acknowledged, and gone, and it
     * carries the success case too, which the form has nothing to say about
     * because the form is no longer on screen by then.
     */
    val signInOutcome: DiarySignInOutcome? = null,
    val signInTarget: DiaryTarget = DiaryTarget.petersburg(""),
    val place: DiaryPlace? = null,
    val picking: Boolean = false,
    val signingOut: Boolean = false,
    val students: List<DiaryStudent> = emptyList(),
    val studentsLoading: Boolean = false,
    val studentsError: DiaryFailure? = null,
    val selectedStudentId: Long? = null,
    val tab: DiaryTab = DiaryTab.SCHEDULE,
    val zone: ZoneId = DefaultDiaryZone,
    val weekStart: LocalDate = diaryWeekStart(diaryToday(DefaultDiaryZone)),
    val scheduleLoading: Boolean = false,
    val scheduleError: DiaryFailure? = null,
    val days: List<DiaryDayUi> = emptyList(),
    val gradesLoading: Boolean = false,
    val gradesError: DiaryFailure? = null,
    val subjects: List<DiarySubjectMarks> = emptyList(),
    val gradeRange: DiaryRange? = null,
    val savedAt: Instant? = null,
    /** The row whose corrections are open in a sheet, or `null` for none. */
    val editing: DiaryCorrections? = null,
    val savingEdit: Boolean = false,
    val editError: DiaryFailure? = null,
) {
    /**
     * The date it is where the diary is.
     *
     * Read on every access rather than frozen into the state, because the
     * state object is built once when the screen opens and carried forward by
     * `copy` from then on: a value stored here was whatever the date was when
     * the pupil first opened the diary, and a phone left on the screen
     * overnight kept offering «на этой неделе» for the week that had ended.
     */
    val today: LocalDate get() = diaryToday(zone)

    val student: DiaryStudent? get() = students.firstOrNull { it.id == selectedStudentId }

    /** Signed in and not being asked for the password again. */
    val signedIn: Boolean get() = session != null && !reauth

    /** A picker is only worth its row when there is something to pick. */
    val showStudentPicker: Boolean get() = students.size > 1

    /** Whether stepping back to this week would move anything. */
    val canReturnToThisWeek: Boolean get() = weekStart != diaryWeekStart(today)

    /** The login the form starts with: the account's, when one is known. */
    val knownLogin: String get() = session?.login ?: signInTarget.login
}

/** Petersburg's zone, which every diary session had before the second diary. */
internal val DefaultDiaryZone: ZoneId = ZoneId.of(DiaryTarget.PETERSBURG_ZONE)

/**
 * The result of one sign-in attempt, for the pop-up that reports it.
 *
 * Both cases are worth saying out loud. «Не удалось войти» because the reason
 * matters — a wrong password and a diary that is down need different things
 * from the person reading; «Вход выполнен» because a form that answers a
 * correct password with silence is a form you cannot tell you have finished
 * with.
 */
sealed interface DiarySignInOutcome {

    /** @param login the account, echoed back so it can be checked for typos. */
    data class Succeeded(val login: String) : DiarySignInOutcome

    /** @param problem said by `DiaryProblemText`, the one mapping there is. */
    data class Failed(val problem: DiarySignInProblem) : DiarySignInOutcome
}
