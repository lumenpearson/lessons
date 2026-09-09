package com.lumenpearson.lessons.core.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The offline cache.
 *
 * Version 1 and `exportSchema = true`: the JSON under `schemas/` is what a later
 * migration will be written and tested against.
 */
@Database(
    entities = [
        SchoolClassEntity::class,
        SchoolDayEntity::class,
        LessonEntity::class,
        EventEntity::class,
        HomeworkEntity::class,
    ],
    version = 1,
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
