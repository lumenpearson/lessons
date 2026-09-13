package com.lumenpearson.lessons.core.data.repository

/**
 * Running the class: everything the bot's management commands do, as calls.
 *
 * Every method answers a [Result] whose failure is always a [ManageFailure],
 * exactly as the diary's does, because the eight screens on this surface have
 * to tell four refusals apart and reading a message to do it is how an app ends
 * up showing «попробуйте позже» to somebody who was demoted five minutes ago.
 *
 * There is no cache and no flow here. The class card, the subjects and the log
 * are read by two people in a class, on a page they open on purpose, and a
 * stale copy of «сколько устройств» kept across sessions would be a number
 * nobody asked for and nobody could trust. Every screen reads on open.
 *
 * Nothing in this interface takes a role. The server looks one up per request
 * from the linked Telegram account, so the only thing the app can do about
 * permissions is show the refusal it gets.
 */
interface ManageRepository {

    /** The class card: name, school, city, zone, join code and the counts. */
    suspend fun classCard(): Result<ManagedClass>

    /**
     * Renames the class, re-homes it, or moves it to another time zone.
     *
     * @param before the card as it currently stands. It is needed because the
     *   server writes one audit line per field it is given, so only the fields
     *   that actually differ are sent — «📜 Журнал» exists to say what changed,
     *   and four lines for a change of city is four lines of noise.
     */
    suspend fun updateClass(before: ManagedClass, edit: ClassEdit): Result<ManagedClass>

    /**
     * Deletes the class and everything in it. Owner only.
     *
     * [confirmName] must be the class's name exactly. The sheet in the app is
     * not the check — the endpoint is reachable without it — so a mistyped name
     * comes back as [ManageFailure.Invalid] rather than as a deleted class.
     *
     * Every device token goes with the class, including this phone's, so the
     * next call after a successful one is a [ManageFailure.SignedOut].
     */
    suspend fun deleteClass(confirmName: String): Result<Unit>

    /** The dictionary, sorted by name. Readable by an editor. */
    suspend fun subjects(): Result<List<ManagedSubject>>

    /** Adds one. A name the class already uses is [ManageFailure.Refused]. */
    suspend fun createSubject(form: SubjectForm): Result<SubjectSaved>

    /**
     * Renames, or sets the short name, teacher or colour.
     *
     * A blank short name, teacher or colour clears that field; a field equal to
     * what [before] already holds is not sent at all, for the reason
     * [updateClass] takes a `before` too.
     *
     * [SubjectSaved.moved] says how many timetable, homework and замена rows a
     * rename carried with it — the number that makes a rename believable, since
     * none of those rows are on this screen.
     */
    suspend fun updateSubject(before: ManagedSubject, form: SubjectForm): Result<SubjectSaved>

    /** Removes the dictionary entry and leaves the lessons alone. */
    suspend fun deleteSubject(id: Long): Result<Unit>

    /** Every schedule the class keeps, with the default marked. */
    suspend fun bells(): Result<List<BellSchedule>>

    /** A new named schedule. It never becomes the default by being created. */
    suspend fun createBellSchedule(name: String, periods: List<BellPeriod>): Result<BellSchedule>

    suspend fun renameBellSchedule(id: Long, name: String): Result<BellSchedule>

    /**
     * Makes this the schedule the class runs on by default.
     *
     * There is no way to say the opposite: a class with no default has no times
     * for an ordinary day, so the way to stop using one is to make another one
     * the default.
     */
    suspend fun makeBellScheduleDefault(id: Long): Result<BellSchedule>

    /**
     * Replaces a schedule's rows wholesale, because that is what editing bells
     * is: move one lesson and every lesson after it shifts.
     */
    suspend fun writeBellPeriods(id: Long, periods: List<BellPeriod>): Result<BellSchedule>

    /**
     * Removes a schedule nothing uses. The class default, and any schedule a
     * special day still points at, come back as [ManageFailure.Refused].
     */
    suspend fun deleteBellSchedule(id: Long): Result<Unit>

    /** The weekly template as the text «📤 Экспорт» sends. */
    suspend fun timetable(): Result<TimetableExport>

    /**
     * Imports the same text back.
     *
     * With [replace] false, a paste that would overwrite a weekday that already
     * has lessons writes nothing and answers with the conflicts — the preview
     * the bot draws before «Применить», as data. Sending the same text with
     * [replace] true is the second tap.
     */
    suspend fun importTimetable(text: String, replace: Boolean): Result<TimetableImport>

    /** The phones on the class's list, oldest first. */
    suspend fun devices(includeRevoked: Boolean = false): Result<List<ManagedDevice>>

    /**
     * Switches a phone off, permanently. Idempotent on the server.
     *
     * This may be the phone in the caller's hand, and then the next call from
     * it is a [ManageFailure.SignedOut] — which is the point: it is how a lost
     * phone is dealt with from the one still in a pocket.
     */
    suspend fun revokeDevice(id: Long): Result<ManagedDevice>

    /**
     * Puts a phone back to read-only without taking it off the class. A device
     * that is not linked has nothing to unlink: [ManageFailure.Refused].
     */
    suspend fun unlinkDevice(id: Long): Result<ManagedDevice>

    /** One page of the audit log, newest first. */
    suspend fun log(limit: Int = LOG_PAGE, offset: Int = 0): Result<AuditPage>

    /** The numbers «📊 Статистика» shows. Readable by an editor. */
    suspend fun stats(): Result<ClassStats>

    /** Everybody waiting for a role, oldest first. */
    suspend fun requests(): Result<List<AccessRequest>>

    /**
     * Grants the role. [role] `null` grants the one that was asked for, which is
     * what pressing «Выдать» in the bot does.
     *
     * Two refusals are the bot's own guards and neither means the page is gone:
     * nobody may grant at or above their own level, and nobody may change a
     * peer's role. Both arrive as [ManageFailure.NotAllowed].
     */
    suspend fun approveRequest(id: Long, role: ClassRole? = null): Result<RequestDecision>

    /** Says no. The person keeps whatever role they had, and is told in Telegram. */
    suspend fun declineRequest(id: Long): Result<RequestDecision>

    companion object {
        /** `AUDIT_PAGE` in `server/app/api/manage.py`, so both pages turn alike. */
        const val LOG_PAGE: Int = 30

        /** The server's own ceiling on `limit`; asking for more is a `422`. */
        const val LOG_PAGE_MAX: Int = 100
    }
}
