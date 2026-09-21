package com.lumenpearson.lessons.core.data.repository

/**
 * What the server this phone is bound to answered when it was last asked.
 *
 * The about page draws this as a badge, and the reason it is worth a badge is
 * that every other screen reports the same three situations as one: «не удалось
 * обновить». A reader who cannot see their timetable has no way to tell a
 * mistyped address from a deployment that is mid-migration from a phone with no
 * signal, and the three have completely different answers.
 *
 * [Degraded] is the one that could not be guessed from outside. The server
 * answers it when its database is at a different Alembic revision from the code
 * in front of it — the window between a merge deploying itself and the
 * migration being applied by hand. In it the API is up, `/health` is green, and
 * reads of whatever gained a column fail. It is the deployment failure this
 * project has actually had, and naming it is most of the value of asking.
 */
sealed interface ServerStatus {

    /** Nobody has asked yet, or the answer is on its way. */
    data object Checking : ServerStatus

    /** No address configured, so there is nothing to ask. */
    data object NotConfigured : ServerStatus

    /** Up, and its database is the one its code expects. */
    data class Ok(val apiVersion: Int, val schema: String?) : ServerStatus

    /**
     * Up, but the schema is not the one the code was written against.
     *
     * @param detail the server's own sentence about which way they disagree; it
     *   already distinguishes «база отстала» from «база впереди кода», which are
     *   opposite mistakes with opposite fixes.
     */
    data class Degraded(val schema: String?, val expected: String?, val detail: String?) :
        ServerStatus

    /** Asked and did not answer: wrong address, no network, or nothing there. */
    data object Unreachable : ServerStatus
}
