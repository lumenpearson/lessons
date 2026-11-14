package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.v2.dto.AccessRequestDto
import com.lumenpearson.lessons.core.data.network.v2.dto.AuditEntryDto
import com.lumenpearson.lessons.core.data.network.v2.dto.BellPeriodDto
import com.lumenpearson.lessons.core.data.network.v2.dto.BellScheduleDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SchoolClassDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ClassDeviceDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SubjectDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ApproveAccessRequestResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DeclineAccessRequestResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.RoleCountDto
import com.lumenpearson.lessons.core.data.network.v2.dto.UpdateBellScheduleResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SchoolDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ListSchoolsResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ClassStatsDto
import com.lumenpearson.lessons.core.data.network.v2.dto.SubjectHoursDto
import com.lumenpearson.lessons.core.data.network.v2.toManagedClass
import com.lumenpearson.lessons.core.data.network.v2.toManagedSubject
import com.lumenpearson.lessons.core.data.network.v2.toManagedBells
import com.lumenpearson.lessons.core.data.network.v2.toManagedDevice
import com.lumenpearson.lessons.core.data.network.v2.toManagedAudit
import com.lumenpearson.lessons.core.data.network.v2.toManagedStats
import com.lumenpearson.lessons.core.data.network.v2.toManagedRequest
import com.lumenpearson.lessons.core.data.network.v2.toManagedDecision
import com.lumenpearson.lessons.core.data.network.v2.toManagedSchool
import com.lumenpearson.lessons.core.data.network.v2.toManagedSchools
import com.lumenpearson.lessons.core.data.network.v2.toManagedBellWrite
import com.lumenpearson.lessons.core.data.network.v2.ManagementChange
import com.lumenpearson.lessons.core.data.network.v2.changedManagementValue as changedValue
import com.lumenpearson.lessons.core.data.network.v2.optionalManagementChange as changedOrCleared
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the management screens are allowed to receive.
 *
 * The mappers are the only place a wire field becomes a value type, so they are
 * also the only place where a decision about bad data can be made once. Three
 * of those decisions are worth pinning down: a timestamp is class wall time and
 * must not be dragged through the device's zone, a bell period without two
 * readable times is not a period, and an empty string is the same nothing as a
 * missing field.
 */
class ManageMappersTest {

    // -- the class card -----------------------------------------------------

    @Test
    fun `a class card keeps its counts and loses its blanks`() {
        val card = SchoolClassDto(
            id = 1,
            name = "  9А ",
            school = "   ",
            city = "Санкт-Петербург",
            timezone = "Europe/Moscow",
            timezoneLabel = "МСК (UTC+3) · Москва, Санкт-Петербург",
            joinCode = "DEMO24",
            members = 12,
            devices = 9,
            pendingRequests = 1,
            bellScheduleId = 3,
            calendarReady = true,
        ).toManagedClass()

        assertEquals("9А", card.name)
        // A school typed as spaces is a school nobody entered.
        assertNull(card.school)
        assertEquals("Санкт-Петербург", card.city)
        assertEquals(12, card.members)
        assertEquals(3L, card.bellScheduleId)
        assertTrue(card.calendarReady)
    }

    /** An unlabelled zone still says more as its IANA name than as an empty row. */
    @Test
    fun `a missing zone label falls back to the zone itself`() {
        val card = SchoolClassDto(id = 1, timezone = "Asia/Omsk", timezoneLabel = "").toManagedClass()
        assertEquals("Asia/Omsk", card.timezoneLabel)
    }

    // -- subjects -----------------------------------------------------------

    @Test
    fun `a subject keeps its id and drops its empty fields`() {
        val subject = SubjectDto(
            id = 4,
            name = "Алгебра",
            shortName = "",
            teacher = " Иванова А. П. ",
            color = "#5B6ABF",
        ).toManagedSubject()

        assertEquals(4L, subject.id)
        assertNull(subject.shortName)
        assertEquals("Иванова А. П.", subject.teacher)
        assertEquals("#5B6ABF", subject.color)
    }

    // -- bells --------------------------------------------------------------

    @Test
    fun `bell periods are sorted by lesson number`() {
        val schedule = BellScheduleDto(
            id = 3,
            name = "Обычное",
            isDefault = true,
            periods = listOf(
                BellPeriodDto(index = 2, startsAt = "09:25:00", endsAt = "10:10:00"),
                BellPeriodDto(index = 1, startsAt = "08:30:00", endsAt = "09:15:00"),
            ),
        ).toManagedBells()

        assertEquals(listOf(1, 2), schedule.periods.map { it.index })
        assertEquals(LocalTime.of(8, 30), schedule.periods.first().startsAt)
        assertTrue(schedule.isDefault)
    }

    /**
     * A period is the pair of times, not one of them. One unreadable row costs
     * that row; the rest of the schedule is still the school's real bells.
     */
    @Test
    fun `a period missing a time is dropped and the schedule survives`() {
        val schedule = BellScheduleDto(
            id = 3,
            periods = listOf(
                BellPeriodDto(index = 1, startsAt = "08:30:00", endsAt = "09:15:00"),
                BellPeriodDto(index = 2, startsAt = "nonsense", endsAt = "10:10:00"),
            ),
        ).toManagedBells()

        assertEquals(listOf(1), schedule.periods.map { it.index })
    }

    // -- devices, and the clock they are read on ----------------------------

    /**
     * The stamps are class wall time and are kept as such.
     *
     * The v2 wire carries UTC instants. Only the explicitly fetched class's
     * zone converts them into these naive domain stamps; the device zone
     * must never move them for an administrator travelling.
     */
    @Test
    fun `a device's timestamps are read as class wall time`() {
        val device = ClassDeviceDto(
            id = 7,
            deviceName = "Pixel 8",
            linked = true,
            owner = "@anna",
            role = "editor",
            createdAt = "2026-09-01T15:22:04Z",
            lastSeenAt = "2026-09-12T04:55:10Z",
            linkedAt = null,
        ).toManagedDevice(ZoneId.of("Europe/Moscow"))

        assertEquals(LocalDateTime.of(2026, 9, 1, 18, 22, 4), device.createdAt)
        assertEquals(LocalDateTime.of(2026, 9, 12, 7, 55, 10), device.lastSeenAt)
        assertNull(device.linkedAt)
        assertEquals(ClassRole.EDITOR, device.role)
    }

    @Test
    fun `a device with no role is one that is linked to nobody`() {
        assertNull(ClassDeviceDto(id = 8, role = "ROLE_UNSPECIFIED").toManagedDevice(ZoneId.of("Europe/Moscow")).role)
        assertNull(ClassDeviceDto(id = 8, role = "headmaster").toManagedDevice(ZoneId.of("Europe/Moscow")).role)
    }

    // -- the log ------------------------------------------------------------

    /** A line whose stamp will not parse is still a change somebody made. */
    @Test
    fun `a log line with an unreadable time is kept without one`() {
        val entry = AuditEntryDto(
            id = 412,
            action = "subject.rename",
            summary = "предмет «Алгебра» → «Алгебра и начала анализа», строк обновлено: 12",
            who = null,
            at = "not a date",
        ).toManagedAudit(ZoneId.of("Europe/Moscow"))

        assertEquals("subject.rename", entry.action)
        assertNull(entry.at)
        assertNull(entry.who)
    }

    // -- stats and requests -------------------------------------------------

    @Test
    fun `the role breakdown is read as roles`() {
        val stats = ClassStatsDto(
            today = "2026-09-12",
            lessonsPerWeek = 32.5,
            subjectsCount = 2,
            subjects = listOf(SubjectHoursDto(name = " Алгебра ", hours = 4.0)),
            membersByRole = listOf(RoleCountDto("ROLE_OWNER", 1), RoleCountDto("ROLE_EDITOR", 4),
                RoleCountDto("ROLE_FUTURE", 9)),
        ).toManagedStats()

        assertEquals(LocalDate.of(2026, 9, 12), stats.today)
        assertEquals(32.5, stats.lessonsPerWeek, 0.0)
        assertEquals("Алгебра", stats.subjects.single().name)
        // A role this build has never heard of is left out rather than drawn
        // as a bar with no name on it.
        assertEquals(mapOf(ClassRole.OWNER to 1, ClassRole.EDITOR to 4), stats.membersByRole)
    }

    @Test
    fun `a request carries the role that was asked for`() {
        val request = AccessRequestDto(
            id = 5,
            who = "@petya",
            requestedRole = "editor",
            message = "  ",
            createdAt = "2026-09-11T16:02:44Z",
        ).toManagedRequest(ZoneId.of("Europe/Moscow"))

        assertEquals(ClassRole.EDITOR, request.requestedRole)
        assertNull(request.message)
        assertEquals(LocalDateTime.of(2026, 9, 11, 19, 2, 44), request.createdAt)
    }

    @Test
    fun `a decision is approved or it is not`() {
        val approved = ApproveAccessRequestResponseDto(requestId = 5, role = "ROLE_EDITOR", who = "@petya")
        assertTrue(approved.toManagedDecision().approved)
        assertEquals(ClassRole.EDITOR, approved.toManagedDecision().role)

        val declined = DeclineAccessRequestResponseDto(requestId = 5, who = "@petya")
        assertTrue(!declined.toManagedDecision().approved)
        assertNull(declined.toManagedDecision().role)
    }

    // -- what a patch actually carries --------------------------------------

    /**
     * The server writes one audit line per field it is given.
     *
     * So a form that sends all four of its boxes on every save fills «📜
     * Журнал» with «class.name: 9А» for a name nobody touched — four lines for
     * a change of city, on the one page that exists to say what changed. An
     * absent field is «не трогать», and that is what an untouched box means.
     */
    @Test
    fun `an unchanged field is left out of the patch`() {
        assertNull(changedValue("9А", "9А"))
        assertNull(changedValue("9А", "  9А  "))
        assertEquals("9Б", changedValue("9А", "9Б"))
    }

    /** A nullable field has three states, and a blank box is the third. */
    @Test
    fun `a nullable field can be left alone, changed or cleared`() {
        assertEquals(ManagementChange.Unchanged, changedOrCleared("Демо-школа", "Демо-школа"))
        assertEquals(ManagementChange.Unchanged, changedOrCleared(null, ""))
        assertEquals(ManagementChange.Unchanged, changedOrCleared(null, "   "))
        assertEquals(ManagementChange.Value("Лицей 2"), changedOrCleared("Демо-школа", "Лицей 2"))
        // The explicit null. An omitted field could never say this.
        assertEquals(ManagementChange.Clear, changedOrCleared("Демо-школа", ""))
        assertEquals(ManagementChange.Clear, changedOrCleared("Демо-школа", null))
    }

    /** Setting a field that was empty is a change, not a clear. */
    @Test
    fun `filling in an empty field sends the value`() {
        assertEquals(ManagementChange.Value("Омск"), changedOrCleared(null, "Омск"))
    }

    // -- the school directory -----------------------------------------------

    @Test
    fun `a school keeps its full name when the short one is missing`() {
        val school = SchoolDto(name = "", fullName = "ШКОЛА № 1").toManagedSchool()
        assertEquals("ШКОЛА № 1", school.fullName)

        val short = SchoolDto(name = "МБОУ СОШ № 1", fullName = "").toManagedSchool()
        // A blank full name falls back to the short one rather than to nothing:
        // the class card has to be able to show *some* unambiguous name.
        assertEquals("МБОУ СОШ № 1", short.fullName)
    }

    @Test
    fun `a nameless row is dropped rather than drawn as an empty button`() {
        val page = ListSchoolsResponseDto(
            schools = listOf(SchoolDto(name = "Школа № 1"), SchoolDto(name = "  ")),
            totalSize = 2,
        ).toManagedSchools()
        assertEquals(1, page.items.size)
        // `total` is the server's count and is not re-derived: it says how many
        // the directory matched, not how many survived this filter.
        assertEquals(2, page.total)
    }

    @Test
    fun `truncated survives the mapping because nothing else says it`() {
        val page = ListSchoolsResponseDto(schools = emptyList(), truncated = true).toManagedSchools()
        assertTrue(page.truncated)
    }

    // -- what a bells write took away ---------------------------------------

    /**
     * The count is the one thing about a bells write that no screen can show.
     *
     * Shrinking a schedule, or pointing the class at a shorter one, leaves
     * every lesson at a number past the new last rung stored on the server and
     * drawn on no phone, in no widget, in no calendar feed and in no digest.
     * Nothing is deleted and everything is gone, and the only trace was an
     * audit line the bot writes and this half of the app cannot read. Until
     * this field the phone answered «Звонки сохранены» over six lessons that
     * had just left the class.
     */
    @Test
    fun `a bells write says how many lessons it stopped ringing`() {
        val written = UpdateBellScheduleResponseDto(BellScheduleDto(id = 3, name = "Обычное"), 2).toManagedBellWrite()
        assertEquals(2, written.silencedLessons)
        assertEquals("Обычное", written.schedule.name)
    }

    /**
     * A read answers without the field, and so does a server older than it.
     * Both have to mean «nothing was silenced» rather than fail to parse.
     */
    @Test
    fun `a schedule that says nothing about it silenced nothing`() {
        val response = UpdateBellScheduleResponseDto(BellScheduleDto(id = 3, name = "Обычное"))
        assertEquals(0, response.toManagedBellWrite().silencedLessons)
    }

    /** A number the server cannot mean is not a school fact to report. */
    @Test
    fun `a negative count is not shown as one`() {
        val written = UpdateBellScheduleResponseDto(BellScheduleDto(id = 3, name = "Обычное"), -1).toManagedBellWrite()
        assertEquals(0, written.silencedLessons)
    }
}
