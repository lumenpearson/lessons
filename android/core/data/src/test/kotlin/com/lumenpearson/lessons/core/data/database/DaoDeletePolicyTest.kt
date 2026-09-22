package com.lumenpearson.lessons.core.data.database

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No delete in the DAO may depend on `PRAGMA foreign_keys` being on.
 *
 * `clear` says so in its own comment and every wipe in that file follows it —
 * except one. `deleteLookaheadOf` deleted the `school_day` row alone and left
 * its lessons, events and homework to the cascade, and the lookahead row is the
 * one row no ranged delete reaches: rows orphaned there are unreachable by
 * every read *and* by every later wipe, so nothing would ever find them again.
 *
 * No unit test could see it. Room does not run on the JVM, and the in-memory
 * fake — which is what every test in this module drives — deleted the children
 * itself, so it was more thorough than the query it stands in for. A fake that
 * does more than the real thing turns a defect into a passing suite.
 *
 * So this reads the shipped SQL out of the source tree instead, the way
 * `StabilityPromiseTest` reads `:core:model` and `ResourceTranslationTest`
 * reads `values/`. What it cannot see: whether the statements are correct SQL,
 * whether Room accepts them, and a child table added to the schema without
 * being added to [CHILD_TABLES] here.
 */
class DaoDeletePolicyTest {

    private companion object {
        /** The tables that hang off `school_day` and have no class of their own. */
        val CHILD_TABLES = listOf("homework", "event", "lesson")
    }

    private val source: File =
        File("src/main/kotlin/com/lumenpearson/lessons/core/data/database/TimetableDao.kt")

    /**
     * Every `@Query`, as one line of SQL.
     *
     * The annotation's argument is written as adjacent string literals joined
     * by `+`, so the literals are concatenated back together and the
     * indentation collapsed. Each block ends at `abstract`, which is what
     * follows every one of them.
     */
    private val statements: List<String> = Regex("""@Query\(([\s\S]*?)abstract""")
        .findAll(source.readText())
        .map { match ->
            Regex("\"([^\"]*)\"")
                .findAll(match.groupValues[1])
                .joinToString("") { it.groupValues[1] }
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        .toList()

    @Test
    fun `the DAO source is where this test thinks it is`() {
        assertTrue("${source.absolutePath} is not there", source.isFile)
        assertTrue(
            "No @Query was read out of it, so every assertion below passes in " +
                "silence. The reader stopped reading Kotlin, not the DAO.",
            statements.size > 20,
        )
        assertTrue(
            "No day delete was found, same problem: ${statements.size} statements read",
            statements.any { it.startsWith("DELETE FROM school_day") },
        )
    }

    @Test
    fun `every delete of a day deletes that day's children too`() {
        val missing = statements
            .filter { it.startsWith("DELETE FROM school_day") }
            .flatMap { dayDelete ->
                val filter = dayDelete.removePrefix("DELETE FROM school_day").trim()
                CHILD_TABLES.map { table -> childDelete(table, filter) }
            }
            .filterNot { it in statements }

        assertEquals(
            "These statements are missing from TimetableDao, so the rows they " +
                "would delete are left to the foreign keys — which is a " +
                "promise about a PRAGMA rather than about this file:\n" +
                missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
    }

    /**
     * The same filter, one level down: the children are reached through their
     * day, so whatever narrows the days has to narrow them identically. A
     * subquery that drifted from the delete above it would take a neighbouring
     * window's lessons with it — or leave this one's behind.
     */
    private fun childDelete(table: String, dayFilter: String): String =
        if (dayFilter.isEmpty()) {
            "DELETE FROM $table"
        } else {
            "DELETE FROM $table WHERE day_id IN (SELECT id FROM school_day $dayFilter)"
        }
}
