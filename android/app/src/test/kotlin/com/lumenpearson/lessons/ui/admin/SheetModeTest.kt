package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.AccessRequest
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the four list-and-form sheets are showing, after the thing that shows it
 * has been torn down and built again.
 *
 * Each of these sheets is one screen with several faces: a list, and a form or a
 * confirmation about one row of it. The face was held in a `remember`, because
 * the sealed type naming it carries the row itself — a [BellSchedule], a
 * [ManagedSubject] — and none of those is `Parcelable`. So a rotation put the
 * reader back on the list, and «Управление классом» is precisely where an
 * unsaved form is expensive: six bell times set one by one, or four boxes of a
 * subject. `SubjectEditor` and `ScheduleNameForm` both hold their fields in
 * `rememberSaveable`, which bought nothing at all while the screen around them
 * could not come back. `ClassCardSheet` had it right because its own mode is a
 * plain enum.
 *
 * The fix saves the two things that can be saved — which face, and which row's
 * id — and resolves them against the list the sheet has on hand. These are the
 * resolutions. The case worth the test is the last one in each pair: an id that
 * no longer matches anything. That is not a hypothetical — the same class is
 * edited from the bot, so a schedule really can be deleted, a request really can
 * be granted, while this sheet sits in the background — and the answer has to be
 * the list. A form with no row behind it is «Сохранить» over nothing.
 */
class SheetModeTest {

    // -- «🔔 Звонки» ---------------------------------------------------------

    private val firstBell = BellSchedule(
        id = 1L,
        name = "Обычные уроки",
        isDefault = true,
        periods = listOf(
            BellPeriod(index = 1, startsAt = LocalTime.of(8, 30), endsAt = LocalTime.of(9, 15)),
        ),
    )
    private val shortBell = firstBell.copy(id = 2L, name = "Сокращённые уроки", isDefault = false)
    private val schedules = listOf(firstBell, shortBell)

    @Test
    fun `a saved bells screen comes back on the schedule it was opened on`() {
        assertEquals(
            BellsMode.Periods(shortBell),
            bellsModeOf(BellsScreen.PERIODS, shortBell.id, schedules),
        )
        assertEquals(
            BellsMode.Rename(firstBell),
            bellsModeOf(BellsScreen.RENAME, firstBell.id, schedules),
        )
        assertEquals(
            BellsMode.Delete(firstBell),
            bellsModeOf(BellsScreen.DELETE, firstBell.id, schedules),
        )
    }

    /** The whole point of resolving rather than restoring: the row is live. */
    @Test
    fun `the schedule is taken from the list, not from whatever it was`() {
        val renamed = shortBell.copy(name = "Актированный день")
        val mode = bellsModeOf(BellsScreen.PERIODS, shortBell.id, listOf(firstBell, renamed))
        assertEquals(BellsMode.Periods(renamed), mode)
    }

    @Test
    fun `a schedule deleted from the bot lands on the list, not an empty form`() {
        assertEquals(
            BellsMode.List,
            bellsModeOf(BellsScreen.PERIODS, shortBell.id, listOf(firstBell)),
        )
        assertEquals(BellsMode.List, bellsModeOf(BellsScreen.RENAME, 99L, schedules))
        assertEquals(BellsMode.List, bellsModeOf(BellsScreen.DELETE, shortBell.id, emptyList()))
    }

    /** `null` is "still loading", and is drawn as the list's own skeleton. */
    @Test
    fun `a list that has not arrived yet is the list`() {
        assertEquals(BellsMode.List, bellsModeOf(BellsScreen.PERIODS, shortBell.id, null))
        assertEquals(BellsMode.List, bellsModeOf(BellsScreen.LIST, null, schedules))
    }

    /** Adding is about no schedule, so it must not be refused for want of one. */
    @Test
    fun `adding a schedule survives with no id and no list`() {
        assertEquals(BellsMode.Add, bellsModeOf(BellsScreen.ADD, null, null))
        assertEquals(BellsMode.Add, bellsModeOf(BellsScreen.ADD, null, schedules))
    }

    // -- «📚 Предметы» -------------------------------------------------------

    private val algebra = ManagedSubject(
        id = 10L,
        name = "Алгебра",
        shortName = "Алг",
        teacher = "Иванова А. П.",
        color = "#5B6ABF",
    )
    private val physics = algebra.copy(id = 11L, name = "Физика", shortName = "Физ")
    private val subjects = listOf(algebra, physics)

    @Test
    fun `a saved subjects screen comes back on the subject it was opened on`() {
        assertEquals(
            SubjectsMode.Edit(physics),
            subjectsModeOf(SubjectsScreen.EDIT, physics.id, subjects),
        )
        assertEquals(
            SubjectsMode.Delete(algebra),
            subjectsModeOf(SubjectsScreen.DELETE, algebra.id, subjects),
        )
    }

    @Test
    fun `a subject deleted from the bot lands on the list, not an empty editor`() {
        assertEquals(
            SubjectsMode.List,
            subjectsModeOf(SubjectsScreen.EDIT, physics.id, listOf(algebra)),
        )
        assertEquals(SubjectsMode.List, subjectsModeOf(SubjectsScreen.DELETE, algebra.id, null))
    }

    @Test
    fun `adding a subject survives with no id and no list`() {
        assertEquals(SubjectsMode.Add, subjectsModeOf(SubjectsScreen.ADD, null, null))
    }

    // -- «🙋 Запросы доступа» ------------------------------------------------

    private val asking = AccessRequest(
        id = 20L,
        who = "Пётр",
        requestedRole = ClassRole.EDITOR,
        message = "я староста",
        createdAt = null,
    )
    private val requests = listOf(asking, asking.copy(id = 21L, who = "Анна"))

    @Test
    fun `a saved requests screen comes back on the request it was opened on`() {
        assertEquals(
            RequestsMode.Approve(asking),
            requestsModeOf(RequestsScreen.APPROVE, asking.id, requests),
        )
        assertEquals(
            RequestsMode.Decline(asking),
            requestsModeOf(RequestsScreen.DECLINE, asking.id, requests),
        )
    }

    /** Answered in the bot while this was in the background: the commonest case. */
    @Test
    fun `a request already answered elsewhere lands on the list`() {
        assertEquals(
            RequestsMode.List,
            requestsModeOf(RequestsScreen.APPROVE, asking.id, listOf(asking.copy(id = 21L))),
        )
        assertEquals(RequestsMode.List, requestsModeOf(RequestsScreen.DECLINE, asking.id, null))
    }

    // -- «📱 Устройства» -----------------------------------------------------

    private val phone = ManagedDevice(
        id = 30L,
        deviceName = "Pixel 6a",
        linked = true,
        owner = "Пётр",
        role = ClassRole.EDITOR,
        revoked = false,
        createdAt = null,
        lastSeenAt = null,
        linkedAt = null,
    )
    private val devices = listOf(phone, phone.copy(id = 31L, deviceName = null, linked = false))

    @Test
    fun `a saved devices screen comes back on the phone it was opened on`() {
        assertEquals(
            DevicesMode.Revoke(phone),
            devicesModeOf(DevicesScreen.REVOKE, phone.id, devices),
        )
        assertEquals(
            DevicesMode.Unlink(phone),
            devicesModeOf(DevicesScreen.UNLINK, phone.id, devices),
        )
    }

    /**
     * Not only deletion: «Показывать отключённые» decides what is in this list,
     * so a phone revoked from the bot drops out of it while the switch is off.
     */
    @Test
    fun `a phone that has dropped out of the filtered list lands on the list`() {
        assertEquals(
            DevicesMode.List,
            devicesModeOf(DevicesScreen.REVOKE, phone.id, devices.filter { it.id != phone.id }),
        )
        assertEquals(DevicesMode.List, devicesModeOf(DevicesScreen.UNLINK, phone.id, null))
    }
}
