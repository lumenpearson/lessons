package com.lumenpearson.lessons.core.data.diary

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lumenpearson.lessons.core.data.database.LessonsTypeConverters

/**
 * The diary as this phone last read it — a database of its own, apart from
 * `lessons.db`, for three reasons:
 *
 *  * a timetable schema bump is destructive (`LessonsDatabase.build`), and it
 *    must not wipe the offline diary of a family that has no class at all;
 *  * a diary schema bump must not wipe every class user's timetable either;
 *  * signing out of the diary is then «empty this file», with nothing of the
 *    class's standing next to it.
 *
 * Backups: the app's rules exclude the whole `database` domain
 * (`backup_rules.xml`, `data_extraction_rules.xml`), so the marks and homework
 * in here never leave the phone that way — which matters, because they are
 * a child's.
 *
 * `exportSchema = true`, into the same `schemas/` folder as `lessons.db`, so a
 * later version has a real v1 to be diffed against.
 */
@Database(
    entities = [
        DiaryStudentEntity::class,
        DiaryWeekEntity::class,
        DiaryPeriodEntity::class,
        DiaryLessonEntity::class,
        DiaryHomeworkEntity::class,
        DiaryMarkEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(LessonsTypeConverters::class)
internal abstract class DiaryDatabase : RoomDatabase() {

    abstract fun diaryDao(): DiaryDao

    companion object {
        /** Also the file name; changing it silently orphans every existing cache. */
        const val NAME: String = "diary.db"

        /**
         * Destructive on a version change, like `lessons.db` and for the same
         * reason: every row is a copy of what our server answered, one import
         * rebuilds it, and nothing typed on the phone lives here.
         */
        fun build(context: Context): DiaryDatabase =
            Room.databaseBuilder(context.applicationContext, DiaryDatabase::class.java, NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
