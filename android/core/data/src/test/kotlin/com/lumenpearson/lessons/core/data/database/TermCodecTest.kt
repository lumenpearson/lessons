package com.lumenpearson.lessons.core.data.database

import com.lumenpearson.lessons.core.model.Term
import com.lumenpearson.lessons.core.model.TermKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terms survive a trip through the cache.
 *
 * They are one column rather than a table: two to four rows that only ever
 * travel with the class and are replaced wholesale every sync. That makes the
 * encoding the join, so it is what gets the test.
 */
class TermCodecTest {

    private val quarters = listOf(
        Term(1, TermKind.QUARTER, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)),
        Term(2, TermKind.QUARTER, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)),
    )

    @Test
    fun `a round trip changes nothing`() {
        assertEquals(quarters, decodeTerms(encodeTerms(quarters)))
    }

    @Test
    fun `halves survive the trip as halves`() {
        val halves = listOf(
            Term(1, TermKind.SEMESTER, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31)),
        )
        assertEquals(halves, decodeTerms(encodeTerms(halves)))
    }

    @Test
    fun `an empty column is no terms rather than one broken one`() {
        assertEquals(emptyList<Term>(), decodeTerms(""))
    }

    @Test
    fun `a line that does not parse is dropped, not invented`() {
        // A term with a made-up date shades the wrong weeks of the calendar and
        // names the wrong term. The cache is rebuilt from the server on the
        // next sync, so dropping costs a redraw and guessing costs correctness.
        val corrupt = """
            1|quarter|2026-09-01|2026-10-31
            2|quarter|not-a-date|2026-12-31
            3|quarter|2027-01-01
            rubbish
        """.trimIndent()

        val decoded = decodeTerms(corrupt)

        assertEquals(listOf(1), decoded.map { it.index })
    }

    @Test
    fun `terms come back in order whatever order they went in`() {
        val shuffled = quarters.reversed()
        assertEquals(listOf(1, 2), decodeTerms(encodeTerms(shuffled)).map { it.index })
    }

    @Test
    fun `an unknown scheme reads as quarters rather than throwing`() {
        // A server that learns trimesters must not stop an older client from
        // drawing a timetable.
        val decoded = decodeTerms("1|trimester|2026-09-01|2026-10-31")

        assertEquals(TermKind.QUARTER, decoded.single().kind)
    }

    @Test
    fun `a date inside a term is in it and one outside is not`() {
        val second = quarters[1]

        assertTrue(LocalDate.of(2026, 12, 1) in second)
        assertTrue(LocalDate.of(2026, 10, 15) !in second)
    }
}
