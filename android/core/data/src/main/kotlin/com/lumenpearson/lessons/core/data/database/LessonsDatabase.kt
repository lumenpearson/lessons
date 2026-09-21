package com.lumenpearson.lessons.core.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The offline cache.
 *
 * `exportSchema = true`: the JSON under `schemas/` is what a later migration
 * will be written and tested against.
 *
 * Version 3 gave `school_day` a `class_id`, because the cache now holds one
 * window per joined class instead of exactly one window. Nothing migrates — see
 * the note on [build] — so the first sync after an update refills the class the
 * user is looking at, and the others refill as they are opened.
 */
@Database(
    entities = [
        SchoolClassEntity::class,
        SchoolDayEntity::class,
        LessonEntity::class,
        EventEntity::class,
        HomeworkEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(LessonsTypeConverters::class)
internal abstract class LessonsDatabase : RoomDatabase() {

    abstract fun timetableDao(): TimetableDao

    companion object {
        /** Also the file name; changing it silently orphans every existing cache. */
        const val NAME: String = "lessons.db"

        /**
         * Destructive fallback is deliberate and safe here: every row is a copy
         * of server state that one sync can rebuild, so dropping the cache on a
         * schema change is cheaper than shipping a migration for data nobody
         * owns. Nothing user-authored is ever stored in this database.
         */
        // fallback: Room 2.9 deprecates the no-argument
        // fallbackToDestructiveMigration(); the boolean overload used here is
        // the current one.
        fun build(context: Context): LessonsDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LessonsDatabase::class.java,
                NAME,
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
