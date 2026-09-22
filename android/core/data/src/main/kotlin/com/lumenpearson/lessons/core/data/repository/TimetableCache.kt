package com.lumenpearson.lessons.core.data.repository

/**
 * The cached timetable, as the code that *drops* it sees it.
 *
 * A second, narrow interface beside [TimetableRepository] rather than two more
 * methods on it, because the caller is `SessionRepositoryImpl` and the one
 * thing it must not be able to do is start a sync of a class the device has
 * just left. It is also why this is not on [TimetableRepository]: that one is
 * implemented by fakes in other modules, and a wipe is not something a screen
 * asks for.
 *
 * What it exists to make impossible: wiping the rows and leaving something
 * behind that describes them. «The cache» has grown twice now — the day rows,
 * then the `synced_window` claims, then the `ETag` of every year held — and
 * each time the code that empties it had to be found again. Leaving a class
 * was wiping the first two through the DAO by hand and leaving the third
 * standing in the preferences for the life of the install.
 */
internal interface TimetableCache {

    /**
     * Empties one class's cache: rows, claims and tags together.
     *
     * For leaving a class and for joining one. Joining is the same event here:
     * the rows that predate the new token must not be shown under it, so they
     * go, and a tag that outlived them would ask the server «changed since?»
     * about rows this phone no longer has.
     */
    suspend fun forgetClass(classId: Long)

    /** The same for every class at once, which is what signing out is. */
    suspend fun forgetEverything()
}
