package com.lumenpearson.lessons.core.data.database

import com.lumenpearson.lessons.core.model.Term
import com.lumenpearson.lessons.core.model.TermKind
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A look at another school year does not take the current year's terms away (#169).
 *
 * The class row is one row for every year, and a bundle carries the terms of
 * the year it was asked for. Written as they came, scrolling the calendar to
 * 2025/26 put 2025/26's quarters in place of 2026/27's; the refresh of 2026/27
 * that followed answered `304`, which writes nothing, and the header of the
 * current week said «21 сентября — 27 сентября» with no «1 четверть» after it
 * for as long as the year's data stayed the same. Seen on an emulator and read
 * out of the phone's own database before this was written.
 *
 * Through [TimetableDao.replaceWindow] itself, on the in-memory DAO, because the
 * merge has to happen inside the transaction that writes the row.
 */
class TermsAcrossYearsTest {

    private val thisYear = listOf(
        quarter(1, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)),
        quarter(2, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)),
    )

    private val lastYear = listOf(
        quarter(1, LocalDate.of(2025, 9, 1), LocalDate.of(2025, 10, 31)),
        quarter(2, LocalDate.of(2025, 11, 1), LocalDate.of(2025, 12, 31)),
    )

    @Test
    fun `syncing last year keeps this year's terms`() = runTest {
        val dao = InMemoryTimetableDao()
        dao.replaceYear(classRow(thisYear), days = emptyList(), nextSchoolDay = null, openingYear = 2026)

        dao.replaceYear(classRow(lastYear), days = emptyList(), nextSchoolDay = null, openingYear = 2025)

        assertEquals(lastYear + thisYear, storedTerms(dao))
    }

    @Test
    fun `a year synced again replaces its own terms and only its own`() = runTest {
        val dao = InMemoryTimetableDao()
        dao.replaceYear(classRow(thisYear), days = emptyList(), nextSchoolDay = null, openingYear = 2026)
        dao.replaceYear(classRow(lastYear), days = emptyList(), nextSchoolDay = null, openingYear = 2025)
        // The admin cut 2026/27 into halves in the meantime.
        val halves = listOf(
            Term(1, TermKind.SEMESTER, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 28)),
            Term(2, TermKind.SEMESTER, LocalDate.of(2027, 1, 9), LocalDate.of(2027, 5, 31)),
        )

        dao.replaceYear(classRow(halves), days = emptyList(), nextSchoolDay = null, openingYear = 2026)

        assertEquals(lastYear + halves, storedTerms(dao))
    }

    @Test
    fun `a first sync with nothing stored is the bundle's terms`() = runTest {
        val dao = InMemoryTimetableDao()

        dao.replaceYear(classRow(thisYear), days = emptyList(), nextSchoolDay = null, openingYear = 2026)

        assertEquals(thisYear, storedTerms(dao))
    }

    private suspend fun storedTerms(dao: InMemoryTimetableDao): List<Term> =
        decodeTerms(checkNotNull(dao.schoolClass(CLASS_ID)).terms)

    private fun classRow(terms: List<Term>) = SchoolClassEntity(
        id = CLASS_ID,
        name = "9А",
        school = null,
        timeZoneId = "Europe/Moscow",
        terms = encodeTerms(terms),
        syncedAtEpochMillis = 0L,
    )

    private fun quarter(index: Int, start: LocalDate, end: LocalDate) =
        Term(index, TermKind.QUARTER, start, end)

    private companion object {
        const val CLASS_ID = 1L
    }
}
