package com.lumenpearson.lessons.core.data.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * The family's account in the Petersburg diary.
 *
 * Deliberately not part of [SessionRepository]. The two sessions are
 * independent in both directions — a phone can be in a class without a diary
 * and in a diary without a class — and the server models them the same way, so
 * joining a class does not sign anybody into a diary and signing out of one
 * does not touch the other. Two repositories is how that stays true when
 * somebody later adds a sign-out to either.
 *
 * Every call returns [Result] like [SessionRepository.join] does, but the
 * failure inside it is always a [DiaryFailure]: the screens have a different
 * thing to do about each answer, and
 * [ReauthRequired][DiaryFailure.ReauthRequired] in particular has to be
 * recognisable without reading a message.
 */
interface DiaryRepository {

    /** `null` means "not signed in to the diary"; the section switches on it. */
    val session: Flow<DiarySession?>

    /** One-shot read, for a screen that needs the login before its first frame. */
    suspend fun current(): DiarySession?

    /**
     * Exchanges credentials for a session and stores it.
     *
     * The password is passed straight through to the one call that needs it and
     * is never written anywhere — not by this app, and not by the server, which
     * is why an expired upstream session ends in
     * [DiaryFailure.ReauthRequired] rather than in a silent refresh.
     *
     * A refused password comes back as [DiaryFailure.SignInRequired]: on this
     * call, and only on this call, that means "wrong login or password".
     */
    suspend fun signIn(login: String, password: String): Result<DiarySession>

    /**
     * Forgets the diary session, here and on the server.
     *
     * The local half happens even when the server cannot be reached: a user who
     * asked to be signed out must not stay signed in because the network was
     * down. The class session is untouched.
     */
    suspend fun signOut(): Result<Unit>

    /** Every pupil this account may see; one for most families. */
    suspend fun students(): Result<List<DiaryStudent>>

    /**
     * Lessons between two dates, inclusive.
     *
     * The server refuses a range wider than [MAX_RANGE_DAYS], so an over-wide
     * request fails here with [DiaryFailure.BadRange] rather than travelling
     * to the server to be refused there.
     */
    suspend fun schedule(studentId: Long, from: LocalDate, to: LocalDate): Result<List<DiaryLesson>>

    /** Homework due between two dates. @see schedule */
    suspend fun homework(studentId: Long, from: LocalDate, to: LocalDate): Result<List<DiaryHomework>>

    /** Every register entry between two dates — marks, absences and the rest. */
    suspend fun grades(studentId: Long, from: LocalDate, to: LocalDate): Result<List<DiaryMark>>

    /** The school's quarters or trimesters, so the marks can be asked for by term. */
    suspend fun periods(studentId: Long): Result<List<DiaryPeriod>>

    /**
     * Every correction this family has stored for this child.
     *
     * Not needed to *draw* a corrected lesson — [schedule] already comes back
     * corrected, with [DiaryLesson.edits] saying which fields were — but needed
     * by a screen that lists them, because a correction on a lesson three
     * months ago is otherwise only reachable by scrolling to it.
     *
     * A row whose field this build does not know is left out; see
     * `DiaryOverrideDto.toDomain`.
     */
    suspend fun overrides(studentId: Long): Result<List<DiaryOverrideRecord>>

    /**
     * Lays a correction over one field, or replaces the one already there.
     *
     * Nothing is sent to the school: the diary is read-only upstream and stays
     * that way, so this writes a row on our own server that is laid over the
     * answer on the way out.
     *
     * @param target the key [DiaryLesson.target] or [DiaryHomework.target]
     *   handed down, echoed back byte for byte. Building one here would be a
     *   second implementation of a string that has to match exactly, and it
     *   would agree with the server's until the first lesson without a number.
     * @param value what to show instead. Empty is a real answer and is stored
     *   as one — the diary often carries a placeholder where a family would
     *   rather see nothing — so it is not a reset; [reset] is.
     * @param original what the diary said when the person decided to replace
     *   it, taken from what they were looking at rather than re-read, because
     *   the question it answers later is whether the diary has moved *since
     *   then*.
     */
    suspend fun correct(
        studentId: Long,
        target: String,
        field: DiaryField,
        value: String,
        original: String?,
    ): Result<Unit>

    /**
     * Puts one field back to whatever the diary says.
     *
     * Succeeds whether or not there was a correction there: "nothing is
     * corrected here" is the state the caller asked for, so a second tap on
     * «сбросить» is not an error to show.
     */
    suspend fun reset(studentId: Long, target: String, field: DiaryField): Result<Unit>

    /** Drops every correction for this child. The diary answers for itself again. */
    suspend fun resetAll(studentId: Long): Result<Unit>

    companion object {
        /** `MAX_RANGE_DAYS` in `server/app/api/diary.py`. */
        const val MAX_RANGE_DAYS: Long = 62
    }
}
