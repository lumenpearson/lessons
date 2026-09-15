package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ImportConflict
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.TimetableExport
import com.lumenpearson.lessons.core.data.repository.TimetableImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the management page does about calls that land in the wrong order.
 *
 * Eight sheets share one state holder, every one of them reads when it opens,
 * and the server can take the page away on whichever call happens to be next.
 * That is three ways for an answer to arrive after the thing it answers has
 * stopped being true, and none of them can be seen by reading the screen: the
 * page looks exactly as it would if the answer were current.
 *
 * The repository here parks every call on a gate the test opens by hand, so
 * "the second read comes back first" is a line of the test rather than a race
 * that shows up once a fortnight on somebody's phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagementViewModelTest {

    private val repository = FakeManageRepository()
    private val links = FakeDeviceLinkRepository(ClassRole.ADMIN)
    private val session = FakeSessionRepository()

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main; unconfined so that a launch
        // reaches its first suspension — the parked call — before the test line
        // that started it returns.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model() = ManagementViewModel(repository, links, session)

    /**
     * A read in flight when the role is lost must not put its answer back.
     *
     * The refusal empties the page for all eight sheets, and the sentence that
     * replaces them says the class is no longer this user's. A subject list
     * that was already on its way arrives a moment later, and — before this was
     * fixed — was written into the emptied state, where nothing showed it until
     * «Проверить снова» succeeded and drew the previous administrator's class.
     */
    @Test
    fun `an answer that arrives after the page is taken away is dropped`() {
        val model = model()
        model.loadSubjects()
        model.deleteSubject(ManagedSubject(1L, "Алгебра", null, null, null))

        // The write is refused: the account was demoted in the bot.
        repository.answer(index = 1, result = Result.failure(RoleLost))
        assertEquals(RoleLost, model.uiState.value.gone)

        // …and only now does the list the sheet asked for come back.
        repository.answer(
            index = 0,
            result = Result.success(listOf(ManagedSubject(2L, "Химия", null, null, null))),
        )
        assertNull(model.uiState.value.subjects.value)

        // Re-promoted. The page comes back empty, not holding what the old role
        // could see.
        model.recheckRole()
        assertNull(model.uiState.value.gone)
        assertNull(model.uiState.value.subjects.value)
    }

    /**
     * The same rule for a read that succeeds and *then* the page goes.
     *
     * Nothing read under the old role survives, which is what [ManagementUiState]
     * promises by being rebuilt from scratch rather than edited.
     */
    @Test
    fun `a refusal empties what earlier reads had loaded`() {
        val model = model()
        model.loadSubjects()
        repository.answer(0, Result.success(listOf(ManagedSubject(1L, "Алгебра", null, null, null))))
        assertEquals(1, model.uiState.value.subjects.value?.size)

        model.loadStats()
        repository.answer(1, Result.failure(RoleLost))

        assertEquals(RoleLost, model.uiState.value.gone)
        assertNull(model.uiState.value.subjects.value)
    }

    /**
     * «Показывать отключённые» flipped twice: the list must match the switch.
     *
     * The two reads ask different questions of the server and the switch has
     * already answered for itself, so whichever request is slower decides what
     * is on screen. The slower one here is the one the user changed their mind
     * about, and its answer lists a revoked phone under a switch that says
     * revoked phones are hidden.
     */
    @Test
    fun `a stale device list does not overtake the switch`() {
        val model = model()
        model.loadDevices(includeRevoked = false)
        repository.answer(0, Result.success(emptyList<ManagedDevice>()))

        model.loadDevices(includeRevoked = true)
        model.loadDevices(includeRevoked = false)
        assertEquals(listOf(false, true, false), repository.deviceCalls)

        // The request the switch is now on answers first, the abandoned one second.
        repository.answer(2, Result.success(listOf(device(1L, revoked = false))))
        repository.answer(
            1,
            Result.success(listOf(device(1L, revoked = false), device(2L, revoked = true))),
        )

        val state = model.uiState.value
        assertEquals(false, state.showRevokedDevices)
        assertEquals(listOf(1L), state.devices.value?.map { it.id })
        assertTrue(state.devices.value.orEmpty().none { it.revoked })
    }

    /**
     * Consent to overwrite a week does not survive the sheet it was asked in.
     *
     * A paste that collides leaves the sheet on its «Заменить» face, holding the
     * text so that a keystroke between the two taps cannot change what is
     * applied. Closing the sheet loses the text box — it is a composable — but
     * the consent lived in the view model, so opening «📥 Импорт» again landed
     * straight on a red button offering to delete six lessons on behalf of a
     * paste that is nowhere on screen.
     */
    @Test
    fun `a refused import does not wait behind a closed sheet`() {
        val model = model()
        model.loadTimetable()
        repository.answer(0, Result.success(TimetableExport("== Понедельник ==\n1. Алгебра", 6)))

        model.importTimetable("== Вторник ==\n1. Геометрия")
        repository.answer(
            1,
            Result.success(
                TimetableImport(
                    applied = false,
                    days = listOf(2),
                    lessons = 1,
                    bells = 0,
                    conflicts = listOf(ImportConflict(weekday = 2, existing = 6, incoming = 1)),
                    rejected = emptyList(),
                ),
            ),
        )
        assertEquals(
            "== Вторник ==\n1. Геометрия",
            model.uiState.value.pendingImport?.text,
        )

        // The sheet is swiped away and opened again; its one read is all the
        // view model hears about that.
        model.loadTimetable()
        assertNull(model.uiState.value.pendingImport)

        repository.answer(2, Result.success(TimetableExport("== Понедельник ==\n1. Алгебра", 6)))
        assertNull(model.uiState.value.pendingImport)
    }

    /** The live flow is untouched: the second tap still sends the agreed text. */
    @Test
    fun `the second tap sends the text that was agreed to`() {
        val model = model()
        model.importTimetable("== Вторник ==\n1. Геометрия")
        repository.answer(
            0,
            Result.success(
                TimetableImport(
                    applied = false,
                    days = listOf(2),
                    lessons = 1,
                    bells = 0,
                    conflicts = listOf(ImportConflict(weekday = 2, existing = 6, incoming = 1)),
                    rejected = emptyList(),
                ),
            ),
        )

        model.replacePendingImport()
        assertEquals(
            listOf("== Вторник ==\n1. Геометрия" to false, "== Вторник ==\n1. Геометрия" to true),
            repository.importCalls,
        )

        repository.answer(
            1,
            Result.success(
                TimetableImport(
                    applied = true,
                    days = listOf(2),
                    lessons = 1,
                    bells = 0,
                    conflicts = emptyList(),
                    rejected = listOf("1. Алгбера"),
                ),
            ),
        )
        assertNull(model.uiState.value.pendingImport)
        assertEquals(
            ManagementNotice.Imported(days = 1, lessons = 1, bells = 0),
            model.uiState.value.notice,
        )
        // The lines the parser could not read outlive the import that went
        // through, which is the whole reason they are echoed back.
        assertEquals(listOf("1. Алгбера"), model.uiState.value.importRejected)
    }

    private fun device(id: Long, revoked: Boolean) = ManagedDevice(
        id = id,
        deviceName = "Pixel $id",
        linked = true,
        owner = "@anna",
        role = ClassRole.EDITOR,
        revoked = revoked,
        createdAt = null,
        lastSeenAt = null,
        linkedAt = null,
    )

    // -- leaving a class that was just deleted ------------------------------

    @Test
    fun `deleting the class does not leave until the sheet is closed`() {
        // The sheet has to be able to say what happened. Dropping the session
        // on success would swap the whole shell for the join screen mid-word.
        val model = model()
        model.deleteClass("9А")
        repository.answer(0, Result.success(Unit))

        assertTrue(model.uiState.value.classDeleted)
        assertEquals(0, session.signOuts)
    }

    @Test
    fun `closing that sheet drops the token and the cache with it`() {
        val model = model()
        model.deleteClass("9А")
        repository.answer(0, Result.success(Unit))

        model.leaveDeletedClass()

        assertEquals(1, session.signOuts)
    }

    @Test
    fun `nothing leaves a class that was not deleted`() {
        // The same call reaches this view model when the sheet is dismissed
        // after an ordinary look at the class card.
        val model = model()

        model.leaveDeletedClass()

        assertEquals(0, session.signOuts)
    }

    @Test
    fun `a refused delete leaves the class alone`() {
        val model = model()
        model.deleteClass("не то имя")
        repository.answer(0, Result.failure(ManageFailure.Invalid("confirm_name does not match")))

        model.leaveDeletedClass()

        assertFalse(model.uiState.value.classDeleted)
        assertEquals(0, session.signOuts)
    }

    /**
     * Signing out has to empty this state, because this object outlives the
     * class it describes.
     *
     * There is one Activity and no nav graph, so the view model comes from the
     * Activity's store: leaving a class only takes `HomeShell` out of
     * composition. `classDeleted` then survived into the next class — and it is
     * the first branch of the class card, ahead of the load, so the card opened
     * on a class that existed and announced that it had been deleted. Both of
     * its buttons call `leaveDeletedClass`, whose only guard is that same flag,
     * so either one signed the user out of the class they had just joined.
     */
    @Test
    fun `joining another class does not inherit the deleted one's state`() {
        val model = model()
        model.deleteClass("9А")
        repository.answer(0, Result.success(Unit))
        assertTrue(model.uiState.value.classDeleted)

        model.leaveDeletedClass()
        session.rejoin(classId = 2L, className = "9Б")

        assertFalse(model.uiState.value.classDeleted)
        assertEquals(1, session.signOuts)

        // And the button that used to throw the user straight back out now
        // finds nothing to act on.
        model.leaveDeletedClass()
        assertEquals(1, session.signOuts)
    }

    /**
     * The quieter half of the same leak: the previous class's card is drawn for
     * one round trip before `loadClass` answers, which on a shared phone is one
     * class's join code shown to another.
     */
    @Test
    fun `the previous class's card does not outlive it`() {
        val model = model()
        model.loadClass()
        repository.answer(0, Result.success(FakeSessionRepository.CLASS_CARD))
        assertNotNull(model.uiState.value.classCard.value)

        runTest { session.signOut() }

        assertNull(model.uiState.value.classCard.value)
    }

    // -- the join mode ------------------------------------------------------

    @Test
    fun `switching to invites writes through and says which way it went`() {
        val model = model()
        model.loadClass()
        repository.answer(0, Result.success(FakeSessionRepository.CLASS_CARD))

        model.setJoinMode(ClassJoinMode.INVITE)
        assertEquals(listOf(ClassJoinMode.INVITE), repository.joinModeCalls)
        repository.answer(1, Result.success(INVITE_ONLY_CARD))

        val state = model.uiState.value
        assertEquals(ClassJoinMode.INVITE, state.classCard.value?.joinMode)
        assertEquals(ManagementNotice.JoinModeChanged(ClassJoinMode.INVITE), state.notice)
        assertFalse(state.working)
        assertNull(state.writeFailure)
    }

    /**
     * A refused switch must leave the card saying what the class actually is.
     *
     * The failure is the only thing on screen that says the tap did nothing:
     * this row is a toggle, and a toggle that moves and then is told "no" by a
     * sentence somewhere else reads as having worked. The card is the server's
     * answer or it is the card from before — never the mode that was asked for.
     */
    @Test
    fun `a refused switch keeps the card on the mode the class is really in`() {
        val model = model()
        model.loadClass()
        repository.answer(0, Result.success(FakeSessionRepository.CLASS_CARD))

        model.setJoinMode(ClassJoinMode.INVITE)
        repository.answer(1, Result.failure(ManageFailure.Refused("нельзя")))

        val state = model.uiState.value
        assertEquals(ClassJoinMode.OPEN, state.classCard.value?.joinMode)
        assertTrue(state.writeFailure is ManageFailure.Refused)
        assertNull(state.notice)
        assertFalse(state.working)
    }

    /** Back to the class code is the same write, and it says the opposite. */
    @Test
    fun `opening the class code back up is one call and its own notice`() {
        val model = model()
        model.loadClass()
        repository.answer(0, Result.success(INVITE_ONLY_CARD))

        model.setJoinMode(ClassJoinMode.OPEN)
        assertEquals(listOf(ClassJoinMode.OPEN), repository.joinModeCalls)
        repository.answer(1, Result.success(FakeSessionRepository.CLASS_CARD))

        val state = model.uiState.value
        assertEquals(ClassJoinMode.OPEN, state.classCard.value?.joinMode)
        assertEquals(ManagementNotice.JoinModeChanged(ClassJoinMode.OPEN), state.notice)
    }

    /** A demotion between the tap and the answer takes the page, as anywhere else. */
    @Test
    fun `losing the role while switching takes the page away`() {
        val model = model()
        model.loadClass()
        repository.answer(0, Result.success(FakeSessionRepository.CLASS_CARD))

        model.setJoinMode(ClassJoinMode.INVITE)
        repository.answer(1, Result.failure(RoleLost))

        assertEquals(RoleLost, model.uiState.value.gone)
        assertNull(model.uiState.value.classCard.value)
    }

    private companion object {
        /** The same class, after the server has accepted the switch. */
        val INVITE_ONLY_CARD = FakeSessionRepository.CLASS_CARD.copy(
            joinMode = ClassJoinMode.INVITE,
        )
    }
}
