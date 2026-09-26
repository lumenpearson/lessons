package com.lumenpearson.lessons.ui.onboarding

import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase
import com.lumenpearson.lessons.core.data.diary.DiaryImportProgress
import com.lumenpearson.lessons.core.data.repository.DiaryBinding
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.data.repository.ShellState
import com.lumenpearson.lessons.ui.diary.DiarySchoolRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * The first run's state holder, driven through its steps with fakes: what it
 * writes, what it holds, what it commits, and what it never keeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val login = "ivan.petrov"
    private val password = "correct horse battery staple"

    private fun OnboardingRig.model() = OnboardingViewModel(saved, deps())

    private val OnboardingViewModel.state get() = checkNotNull(flow.value) { "the flow has not started" }

    @Test
    fun `reaching the chooser writes onboardingDone`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val model = rig.model()
        model.start(introduced = false)
        advanceUntilIdle()
        repeat(3) {
            model.next()
            advanceUntilIdle()
        }
        assertEquals(OnboardingStep.PERMISSIONS, model.state.current)
        assertFalse(rig.settings.stored.value.onboardingDone)

        model.next()
        advanceUntilIdle()
        assertEquals(OnboardingStep.WAY_IN, model.state.current)
        assertTrue(rig.settings.stored.value.onboardingDone)
    }

    @Test
    fun `the class-code step holds the shell and a join with no diary finishes`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val model = rig.model()
        model.start(introduced = true)
        advanceUntilIdle()
        model.chooseWay(WayIn.CLASS_CODE)
        model.proceedFromWayIn()
        advanceUntilIdle()
        assertEquals(OnboardingStep.CLASS_CODE, model.state.current)
        assertTrue("the join must not swap the flow for a home", rig.shell.stored.value.held)

        rig.joinedClass(binding = null)
        rig.shell.stored.value = rig.shell.stored.value.copy(mode = ShellMode.CLASS)
        model.onJoined()
        advanceUntilIdle()

        assertFalse(rig.shell.stored.value.held)
        assertTrue("finish clears what was saved", rig.saved.keys().all { rig.saved.get<Any?>(it) == null })
    }

    @Test
    fun `a join whose class has a usable diary commits its sign-in`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val model = rig.model()
        model.start(introduced = true)
        advanceUntilIdle()
        model.chooseWay(WayIn.CLASS_CODE)
        model.proceedFromWayIn()
        advanceUntilIdle()

        rig.joinedClass(DiaryBinding(DiaryProviderKey.NETSCHOOL, "samara", 239L, "Lyceum 239"))
        model.onJoined()
        advanceUntilIdle()

        val state = model.state
        assertEquals(OnboardingStep.SIGN_IN, state.current)
        assertTrue(state.choices.classBound)
        assertFalse("the join screen cannot be walked back into", state.canGoBack)
        val header = checkNotNull(model.signInHeader.value)
        assertTrue(header.classBound)
        assertEquals("asurso.ru", header.place.host)
        assertTrue(rig.shell.stored.value.held)
    }

    @Test
    fun `a join with a diary already signed in finishes rather than asking again`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val model = rig.model()
        model.start(introduced = true)
        advanceUntilIdle()
        rig.diarySession = DiarySession(login, "bearer", DiaryTarget.petersburg(login))
        rig.joinedClass(DiaryBinding(DiaryProviderKey.PETERSBURG, null, null, null))
        rig.shell.hold()

        model.onJoined()
        advanceUntilIdle()

        assertFalse(rig.shell.stored.value.held)
    }

    /** Walks the family's own way to the sign-in step for Samara, school 239. */
    private suspend fun kotlinx.coroutines.test.TestScope.toSamaraSignIn(model: OnboardingViewModel) {
        model.start(introduced = true)
        advanceUntilIdle()
        model.chooseWay(WayIn.FIND_SCHOOL)
        model.proceedFromWayIn()
        advanceUntilIdle()
        assertEquals(OnboardingStep.REGION, model.state.current)
        model.pickRegion("samara")
        advanceUntilIdle()
        model.proceedFromRegion()
        advanceUntilIdle()
        assertEquals(OnboardingStep.SCHOOL, model.state.current)
        model.pickSchool(DiarySchoolRow(239, "Lyceum 239", null))
        model.proceedFromSchool()
        advanceUntilIdle()
        assertEquals(OnboardingStep.PROVIDER, model.state.current)
        val row = checkNotNull(model.provider.value?.main)
        assertEquals(ProviderRowKind.SIGN_IN, row.kind)
        model.signInWith(row.index)
        advanceUntilIdle()
        assertEquals(OnboardingStep.SIGN_IN, model.state.current)
    }

    @Test
    fun `the family's own way signs in to the picked school and commits the import`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val model = rig.model()
        toSamaraSignIn(model)
        assertTrue(rig.shell.stored.value.held)

        model.signIn.setLogin(login)
        model.submitSignIn(password)
        advanceUntilIdle()

        val (target, sent) = rig.signIn.opened.single()
        assertEquals(DiaryProviderKey.NETSCHOOL, target.provider)
        assertEquals("samara", target.region)
        assertEquals(239L, target.schoolId)
        assertEquals(login, target.login)
        assertEquals(password, sent)

        val state = model.state
        assertEquals(OnboardingStep.IMPORT, state.current)
        assertFalse("a second sign-in would mint a second server session", state.canGoBack)

        // The fake import finishes at once; the screen then shows the summary.
        assertNotNull(model.importing.state.value.done)
        model.importShown()
        advanceUntilIdle()
        assertEquals(OnboardingStep.SUMMARY, model.state.current)
        val summary = checkNotNull(model.summary.value)
        assertEquals(login, summary.login)
        assertEquals("asurso.ru", summary.place?.host)
        assertFalse("a diary-only phone draws no class and no notifications row", summary.inClass)
        assertNull(summary.className)

        model.finish()
        advanceUntilIdle()
        assertFalse(rig.shell.stored.value.held)
    }

    /**
     * The pupil the owner asked the summary to name is the one picked on the
     * chooser, not whoever the diary lists first — and the class, when there
     * is one, is what turns on the class card and the notifications row.
     */
    @Test
    fun `the summary names the pupil picked and the class joined`() = runTest(dispatcher) {
        val sister = pupil.copy(id = 8, firstName = "Anna", fullName = "Petrova Anna", className = "5B")
        val rig = OnboardingRig(import = FakeImport(choosing = listOf(pupil, sister)))
        rig.cache.saveStudents(listOf(pupil, sister), java.time.Instant.EPOCH)
        val model = rig.model()
        toSamaraSignIn(model)
        model.signIn.setLogin(login)
        model.submitSignIn(password)
        advanceUntilIdle()
        assertEquals(listOf(7L, 8L), model.importing.state.value.choosing?.map { it.id })

        model.pickStudent(8)
        advanceUntilIdle()
        assertEquals("the import goes on with the pupil picked", 8L, rig.import.picked?.id)
        model.importShown()
        advanceUntilIdle()
        val summary = checkNotNull(model.summary.value)
        assertEquals("Petrova Anna", summary.studentName)
        assertEquals("5B", summary.studentClass)
        assertFalse(summary.inClass)

        // The same page for a phone that also holds a class.
        val joined = OnboardingRig()
        joined.joinedClass(DiaryBinding(DiaryProviderKey.NETSCHOOL, "samara", 239L, "Lyceum 239"))
        val classModel = joined.model()
        classModel.start(introduced = true)
        advanceUntilIdle()
        classModel.chooseWay(WayIn.CLASS_CODE)
        classModel.proceedFromWayIn()
        advanceUntilIdle()
        classModel.onJoined()
        advanceUntilIdle()
        classModel.signIn.setLogin(login)
        classModel.submitSignIn(password)
        advanceUntilIdle()
        classModel.importShown()
        advanceUntilIdle()
        val inClass = checkNotNull(classModel.summary.value)
        assertTrue(inClass.inClass)
        assertEquals("9A", inClass.className)
    }

    /**
     * Back during «Передаём сессию серверу…» used to cancel
     * the request our server finishes anyway: a sealed diary credential tied
     * to no device, which no sign-out could reach, and a second with the next
     * try. The step now stays until the answer is in.
     */
    @Test
    fun `back is refused while the server is adopting the session`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.signIn.registerGate = gate
        val model = rig.model()
        toSamaraSignIn(model)
        model.signIn.setLogin(login)
        model.submitSignIn(password)
        advanceUntilIdle()
        assertEquals(SignInStage.REGISTER, model.signIn.state.value.stage)

        model.back()
        advanceUntilIdle()
        assertEquals("the sign-in is not left mid-registration", OnboardingStep.SIGN_IN, model.state.current)
        assertTrue("nothing said goodbye under the server", rig.signIn.discarded.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OnboardingStep.IMPORT, model.state.current)
        assertNotNull("the phone holds the bearer to the row the server kept", rig.diarySession)
        assertTrue(rig.signIn.discarded.isEmpty())
    }

    @Test
    fun `not now is refused while a class's sign-in is under way`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.signIn.registerGate = gate
        val model = rig.model()
        model.start(introduced = true)
        advanceUntilIdle()
        model.chooseWay(WayIn.CLASS_CODE)
        model.proceedFromWayIn()
        advanceUntilIdle()
        rig.joinedClass(DiaryBinding(DiaryProviderKey.PETERSBURG, null, null, null))
        model.onJoined()
        advanceUntilIdle()
        model.signIn.setLogin(login)
        model.submitSignIn(password)
        advanceUntilIdle()

        model.skipSignIn()
        advanceUntilIdle()
        assertTrue("the flow did not end under the registration", rig.shell.stored.value.held)
        assertEquals(OnboardingStep.SIGN_IN, model.state.current)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OnboardingStep.IMPORT, model.state.current)
    }

    /**
     * The bundle is written when the activity stops; a
     * registration that lands after that reaches the in-memory handle only,
     * and a process death then restored the form over a diary already
     * registered — a second sign-in there orphans the first server row.
     */
    @Test
    fun `a saved sign-in over a diary registered since goes on to its import`() = runTest(dispatcher) {
        val rig = OnboardingRig(shell = FakeShellMode(ShellState(ShellMode.DIARY, held = true)))
        OnboardingState(
            path = listOf(
                OnboardingStep.WAY_IN,
                OnboardingStep.REGION,
                OnboardingStep.SCHOOL,
                OnboardingStep.PROVIDER,
                OnboardingStep.SIGN_IN,
            ),
            choices = OnboardingChoices(
                wayIn = WayIn.FIND_SCHOOL,
                region = "samara",
                school = PickedSchool(239, "Lyceum 239"),
                system = 0,
            ),
        ).toSaved().forEach { (key, value) -> rig.saved[key] = value }
        rig.diarySession = DiarySession(login, "bearer", DiaryTarget.netschool("samara", 239L, "Lyceum 239", login, "Europe/Samara"))

        val model = rig.model()
        model.start(introduced = true)
        advanceUntilIdle()

        assertEquals(OnboardingStep.IMPORT, model.state.current)
        assertFalse("the form cannot be walked back into", model.state.canGoBack)
        assertEquals("the import runs from the start", listOf<DiaryImportPhase?>(null), rig.import.runs)
    }

    @Test
    fun `a saved join screen over a class joined since goes on as the join would`() = runTest(dispatcher) {
        val finished = OnboardingRig(shell = FakeShellMode(ShellState(ShellMode.CLASS, held = true)))
        val atCode = OnboardingState(
            path = listOf(OnboardingStep.WAY_IN, OnboardingStep.CLASS_CODE),
            choices = OnboardingChoices(wayIn = WayIn.CLASS_CODE),
        )
        atCode.toSaved().forEach { (key, value) -> finished.saved[key] = value }
        finished.joinedClass(binding = null)
        finished.model().start(introduced = true)
        advanceUntilIdle()
        assertFalse("a class with no diary: the flow is done", finished.shell.stored.value.held)

        val bound = OnboardingRig(shell = FakeShellMode(ShellState(ShellMode.CLASS, held = true)))
        atCode.toSaved().forEach { (key, value) -> bound.saved[key] = value }
        bound.joinedClass(DiaryBinding(DiaryProviderKey.PETERSBURG, null, null, null))
        val model = bound.model()
        model.start(introduced = true)
        advanceUntilIdle()
        assertEquals(OnboardingStep.SIGN_IN, model.state.current)
        assertTrue(model.state.choices.classBound)
        assertFalse("the join screen cannot be walked back into", model.state.canGoBack)
    }

    /**
     * «Забыли пароль?» took the site of a system
     * picked on the provider step and walked back from, over the class's own
     * diary the form now signs in to.
     */
    @Test
    fun `a class's sign-in after a family attempt links the class's diary site`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        rig.signIn.registerResults += Result.failure(DiarySignInProblem.ServerRefusedSession)
        val model = rig.model()
        toSamaraSignIn(model)
        assertEquals("https://asurso.ru", model.signInHeader.value?.forgotUrl)
        model.signIn.setLogin(login)
        model.submitSignIn(password)
        advanceUntilIdle()
        assertEquals(DiarySignInProblem.ServerRefusedSession, model.signIn.state.value.problem)

        model.toClassCode()
        advanceUntilIdle()
        rig.joinedClass(DiaryBinding(DiaryProviderKey.PETERSBURG, null, null, null))
        model.onJoined()
        advanceUntilIdle()

        val header = checkNotNull(model.signInHeader.value)
        assertEquals(DiaryProviderKey.PETERSBURG, header.provider)
        assertEquals("https://dnevnik2.petersburgedu.ru", header.forgotUrl)
    }

    /** The same, reached by walking back from the family's sign-in rather than through a 409. */
    @Test
    fun `a class's sign-in after walking back from the family's links the class's diary site`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val model = rig.model()
        toSamaraSignIn(model)
        model.back()
        advanceUntilIdle()
        assertEquals(OnboardingStep.PROVIDER, model.state.current)

        model.toClassCode()
        advanceUntilIdle()
        rig.joinedClass(DiaryBinding(DiaryProviderKey.PETERSBURG, null, null, null))
        model.onJoined()
        advanceUntilIdle()

        assertEquals(OnboardingStep.SIGN_IN, model.state.current)
        assertEquals("https://dnevnik2.petersburgedu.ru", model.signInHeader.value?.forgotUrl)
    }

    /**
     * «Войти ещё раз» put the sign-in on the floor with no
     * back and no «Не сейчас»; a sign-in that kept failing there had no way
     * out short of killing the app. It has the import page's own now.
     */
    @Test
    fun `a sign-in reopened after the import can still start over`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        rig.import.steps = listOf(
            DiaryImportProgress.Failed(DiaryImportPhase.SCHEDULE, DiarySignInProblem.ReauthRequired, true, 0.2f),
        )
        val model = rig.model()
        toSamaraSignIn(model)
        model.signIn.setLogin(login)
        model.submitSignIn(password)
        advanceUntilIdle()
        model.signInAgain()
        advanceUntilIdle()
        val reopened = model.state
        assertEquals(OnboardingStep.SIGN_IN, reopened.current)
        assertEquals(SignInExit.START_OVER, signInExitOf(reopened.choices.classBound, reopened.canGoBack))

        // Not while a sign-in is out: the registration would land on a flow
        // that has moved on.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.signIn.registerGate = gate
        rig.signIn.registerResults += Result.failure(DiarySignInProblem.WrongPassword(upstreamMessage = null))
        model.submitSignIn(password)
        advanceUntilIdle()
        model.startOver()
        advanceUntilIdle()
        assertEquals(OnboardingStep.SIGN_IN, model.state.current)
        gate.complete(Unit)
        advanceUntilIdle()

        model.startOver()
        advanceUntilIdle()
        assertEquals(listOf(OnboardingStep.WAY_IN), model.state.path)
        assertEquals(1, rig.signedOut)
    }

    @Test
    fun `a failed registration keeps the session for a retry without the password`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        rig.signIn.registerResults += Result.failure(DiarySignInProblem.RegisterUnreachable)
        val model = rig.model()
        toSamaraSignIn(model)

        model.signIn.setLogin(login)
        model.submitSignIn(password)
        advanceUntilIdle()
        assertEquals(OnboardingStep.SIGN_IN, model.state.current)
        assertTrue(model.signIn.state.value.sessionHeld)

        model.retrySignIn()
        advanceUntilIdle()

        assertEquals(listOf("preflight", "open", "register", "register"), rig.signIn.calls)
        assertEquals(OnboardingStep.IMPORT, model.state.current)
    }

    @Test
    fun `backing out of the sign-in says goodbye to a session the server never took`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        rig.signIn.registerResults += Result.failure(DiarySignInProblem.RegisterUnreachable)
        val model = rig.model()
        toSamaraSignIn(model)
        model.signIn.setLogin(login)
        model.submitSignIn(password)
        advanceUntilIdle()

        model.back()
        advanceUntilIdle()

        assertEquals(OnboardingStep.PROVIDER, model.state.current)
        assertEquals(1, rig.signIn.discarded.size)
        assertFalse(model.signIn.state.value.sessionHeld)
    }

    @Test
    fun `the saved state carries no credential`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        rig.signIn.registerResults += Result.failure(DiarySignInProblem.RegisterUnreachable)
        val model = rig.model()
        toSamaraSignIn(model)
        model.signIn.setLogin(login)
        model.submitSignIn(password)
        advanceUntilIdle()

        for (key in rig.saved.keys()) {
            val value = rig.saved.get<Any?>(key).toString()
            assertFalse("$key holds the password", password in value)
            assertFalse("$key holds the login", login in value)
        }
    }

    @Test
    fun `a cold start with the hold set resumes where the write left it`() = runTest(dispatcher) {
        val diary = OnboardingRig(shell = FakeShellMode(ShellState(ShellMode.DIARY, held = true)))
        val importing = diary.model()
        importing.start(introduced = true)
        advanceUntilIdle()
        assertEquals(listOf(OnboardingStep.IMPORT), importing.state.path)

        val joined = OnboardingRig(shell = FakeShellMode(ShellState(ShellMode.CLASS, held = true)))
        joined.joinedClass(DiaryBinding(DiaryProviderKey.PETERSBURG, null, null, null))
        val signingIn = joined.model()
        signingIn.start(introduced = true)
        advanceUntilIdle()
        assertEquals(listOf(OnboardingStep.SIGN_IN), signingIn.state.path)
        assertTrue(signingIn.state.choices.classBound)

        val done = OnboardingRig(shell = FakeShellMode(ShellState(ShellMode.CLASS, held = true)))
        done.joinedClass(binding = null)
        val finishing = done.model()
        finishing.start(introduced = true)
        advanceUntilIdle()
        assertFalse(done.shell.stored.value.held)
    }

    @Test
    fun `a saved flow comes back as it was, and a finished one starts afresh`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val saved = OnboardingState(
            path = listOf(OnboardingStep.WAY_IN, OnboardingStep.REGION),
            choices = OnboardingChoices(wayIn = WayIn.FIND_SCHOOL, region = "samara"),
        )
        saved.toSaved().forEach { (key, value) -> rig.saved[key] = value }

        val model = rig.model()
        model.start(introduced = true)
        advanceUntilIdle()
        assertEquals(saved, model.state)

        model.finish()
        advanceUntilIdle()
        model.start(introduced = true)
        advanceUntilIdle()
        assertEquals(listOf(OnboardingStep.WAY_IN), model.state.path)
    }

    /**
     * The step holders live as long as the view model, which is the
     * activity's. After «Выйти из дневника» sends a diary-only phone back to
     * the first run, the next one opened on the old region's search and on a
     * sign-in form holding the login that sign-out had just forgotten.
     */
    @Test
    fun `a finished flow's login and searches do not reach the next one`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val model = rig.model()
        model.start(introduced = true)
        advanceUntilIdle()
        model.region.type("Самара")
        model.school.open("samara", "Лицей")
        model.signIn.setLogin(login)
        advanceUntilIdle()

        model.finish()
        advanceUntilIdle()
        model.start(introduced = true)
        advanceUntilIdle()

        assertEquals("", model.signIn.state.value.login)
        assertEquals("", model.region.state.value.query)
        assertTrue(model.region.state.value.rows.isEmpty())
        assertEquals("", model.school.state.value.query)
        assertNull(model.school.state.value.regionKey)
        assertNull(model.signInHeader.value)
        assertNull(model.summary.value)
    }

    /** A first start is not a restart: a recreation keeps the login in memory. */
    @Test
    fun `a flow that has not finished keeps its login across a second start`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val model = rig.model()
        model.start(introduced = true)
        advanceUntilIdle()
        model.signIn.setLogin(login)

        model.start(introduced = true)
        advanceUntilIdle()

        assertEquals(login, model.signIn.state.value.login)
    }

    @Test
    fun `an import the diary ended goes back to the sign-in and resumes its phase`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        rig.import.steps = listOf(
            DiaryImportProgress.Running(DiaryImportPhase.SCHEDULE, 0.2f, 0.5f),
            DiaryImportProgress.Failed(DiaryImportPhase.SCHEDULE, DiarySignInProblem.ReauthRequired, true, 0.2f),
        )
        rig.storedTarget = DiaryTarget.petersburg(login)
        val model = rig.model()
        toSamaraSignIn(model)
        model.signIn.setLogin(login)
        model.submitSignIn(password)
        advanceUntilIdle()
        assertTrue(model.importing.state.value.failed?.needsSignIn == true)

        model.signInAgain()
        advanceUntilIdle()
        assertEquals(OnboardingStep.SIGN_IN, model.state.current)
        assertFalse(model.state.canGoBack)

        rig.import.steps = listOf(DiaryImportProgress.Done(pupil, emptySet(), 1, 1, 1))
        model.submitSignIn(password)
        advanceUntilIdle()
        assertEquals(OnboardingStep.IMPORT, model.state.current)
        assertEquals(listOf(null, DiaryImportPhase.SCHEDULE), rig.import.runs)
        assertNull("the resume point is spent once used", model.state.choices.resumeFrom)
    }

    @Test
    fun `a Gosuslugi-only region never reaches the sign-in`() = runTest(dispatcher) {
        val rig = OnboardingRig()
        val model = rig.model()
        model.start(introduced = true)
        advanceUntilIdle()
        model.chooseWay(WayIn.FIND_SCHOOL)
        model.proceedFromWayIn()
        model.pickRegion("tula")
        advanceUntilIdle()
        model.proceedFromRegion()
        advanceUntilIdle()

        assertEquals(OnboardingStep.PROVIDER, model.state.current)
        val page = checkNotNull(model.provider.value)
        assertTrue(page.rows.none { it.kind == ProviderRowKind.SIGN_IN || it.kind == ProviderRowKind.NEEDS_SCHOOL })
        assertEquals(ProviderRowKind.HANDOFF, page.main?.kind)
    }
}
