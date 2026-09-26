package com.lumenpearson.lessons.core.data.diary

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules `DiaryDao` promises, read out of the shipped SQL.
 *
 * Room does not run on the JVM and the in-memory DAO the other tests drive
 * implements only what each statement says, so neither can see a statement
 * that says the wrong thing. This reads `DiaryDao.kt` the way
 * `DaoDeletePolicyTest` reads `TimetableDao.kt`. What it cannot see: whether
 * the SQL is valid (the Room compiler checks that at build time) and a table
 * added to the database but not to [TABLES].
 */
class DiaryDaoPolicyTest {

    private companion object {
        val TABLES = listOf("diary_student", "diary_week", "diary_period", "diary_lesson", "diary_homework", "diary_mark")
    }

    private val source: File =
        File("src/main/kotlin/com/lumenpearson/lessons/core/data/diary/DiaryDao.kt")

    /** Every `@Query`, its adjacent literals joined and its whitespace collapsed. */
    private val statements: List<String> by lazy {
        Regex("""@Query\(([\s\S]*?)abstract""")
            .findAll(source.readText())
            .map { match ->
                Regex("\"([^\"]*)\"")
                    .findAll(match.groupValues[1])
                    .joinToString("") { it.groupValues[1] }
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .toList()
    }

    @Test
    fun `the DAO source is where this test thinks it is`() {
        assertTrue("${source.absolutePath} is not there", source.isFile)
        assertTrue("only ${statements.size} statements were read", statements.size > 20)
        assertTrue(statements.any { it.startsWith("DELETE FROM diary_lesson WHERE") })
    }

    @Test
    fun `every delete is scoped by pupil or empties its whole table, and none relies on foreign keys`() {
        val deletes = statements.filter { it.startsWith("DELETE") }
        val unscoped = deletes.filter { delete ->
            val where = delete.substringAfter(" WHERE ", missingDelimiterValue = "")
            val whole = TABLES.any { delete == "DELETE FROM $it" }
            !whole && !where.startsWith("student_id ") && !where.startsWith("id ")
        }
        assertEquals("deletes that name no pupil: $unscoped", emptyList<String>(), unscoped)
        val entities = File(source.parentFile, "DiaryEntities.kt").readText()
        assertTrue("a foreign key would make a delete depend on a PRAGMA", "ForeignKey" !in entities)
    }

    /** `clear` is sign-out: a table it skipped would keep a child's marks after the screen said they were gone. */
    @Test
    fun `clear empties every table`() {
        val text = source.readText()
        val clear = text.substringAfter("open suspend fun clear()").substringBefore("\n    }")
        for (table in TABLES) {
            val method = statements.indexOf("DELETE FROM $table")
            assertTrue("no whole-table delete for $table", method >= 0)
        }
        val calls = Regex("""deleteAll\w+\(\)""").findAll(clear).map { it.value }.toSet()
        assertEquals(TABLES.size, calls.size)
    }

    /** A pupil dropped from the list takes every row of theirs along, table by table. */
    @Test
    fun `a vanished pupil is deleted from every table`() {
        for (table in TABLES) {
            val column = if (table == "diary_student") "id" else "student_id"
            assertTrue(
                "no delete of vanished pupils in $table",
                "DELETE FROM $table WHERE $column NOT IN (:keep)" in statements,
            )
        }
    }

    /**
     * API 26–29 ship SQLite older than 3.24. The SQL is checked, not the
     * comments — the file's own note names the syntax it avoids — plus Room's
     * `@Upsert`, which generates it.
     */
    @Test
    fun `no upsert syntax newer than API 26's SQLite`() {
        for (statement in statements) {
            for (newer in listOf("ON CONFLICT", "DO UPDATE", "UPSERT", "RETURNING")) {
                assertTrue("$newer in: $statement", newer !in statement.uppercase())
            }
        }
        assertTrue("@Upsert generates ON CONFLICT … DO UPDATE", "@Upsert" !in source.readText())
    }
}
