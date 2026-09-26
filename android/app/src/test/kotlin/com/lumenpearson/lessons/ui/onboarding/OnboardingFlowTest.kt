package com.lumenpearson.lessons.ui.onboarding

import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.ACKNOWLEDGEMENT
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.CLASS_CODE
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.IMPORT
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.PERMISSIONS
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.PREFERENCES
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.PROVIDER
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.REGION
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.SCHOOL
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.SIGN_IN
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.SUMMARY
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.WAY_IN
import com.lumenpearson.lessons.ui.onboarding.OnboardingStep.WELCOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flow's rules, as pure functions of its path: where it starts, how far back
 * it goes, which way a step slides, and what the dots promise.
 *
 * The dots are the part most worth pinning. They count a chapter and they may
 * only shrink as answers come in — a count that grew at the chooser would be
 * the flow getting longer in front of the reader, which is exactly what the old
 * five-dot row was written never to do.
 */
class OnboardingFlowTest {

    private fun path(vararg steps: OnboardingStep, floor: Int = 0, choices: OnboardingChoices = OnboardingChoices()) =
        OnboardingState(path = steps.toList(), floor = floor, choices = choices)

    @Test
    fun `a fresh install starts at the welcome and an introduced one at the chooser`() {
        assertEquals(listOf(WELCOME), OnboardingFlow.initial(introduced = false).path)
        assertEquals(listOf(WAY_IN), OnboardingFlow.initial(introduced = true).path)
    }

    @Test
    fun `back never goes below the floor`() {
        val committed = OnboardingFlow.commit(path(WAY_IN, REGION, PROVIDER, SIGN_IN), IMPORT)
        assertEquals(4, committed.floor)
        assertFalse(committed.canGoBack)
        assertEquals(committed, OnboardingFlow.back(committed))
        // Twice, as two back events drained in one pass would ask.
        assertEquals(committed, OnboardingFlow.back(OnboardingFlow.back(committed)))
    }

    @Test
    fun `back on the first step is left to the system`() {
        assertFalse(OnboardingFlow.initial(introduced = false).canGoBack)
        assertFalse(OnboardingFlow.initial(introduced = true).canGoBack)
        assertEquals(listOf(WELCOME), OnboardingFlow.back(path(WELCOME, ACKNOWLEDGEMENT)).path)
    }

    @Test
    fun `direction follows depth, not ordinal`() {
        val provider = path(WAY_IN, REGION, PROVIDER)
        val code = OnboardingFlow.push(provider, CLASS_CODE)
        assertTrue(CLASS_CODE.ordinal < PROVIDER.ordinal)
        assertTrue(OnboardingFlow.isForward(provider.depth, code.depth))
        assertFalse(OnboardingFlow.isForward(code.depth, OnboardingFlow.back(code).depth))
    }

    @Test
    fun `the introduction is five dots and the chooser closes it`() {
        val welcome = OnboardingFlow.progress(path(WELCOME), plan = null)
        assertEquals(StepProgress(Chapter.INTRO, 1, 5), welcome)

        val chooser = path(WELCOME, ACKNOWLEDGEMENT, PREFERENCES, PERMISSIONS, WAY_IN)
        assertEquals(StepProgress(Chapter.INTRO, 5, 5), OnboardingFlow.progress(chooser, null))
        // Answering the chooser must not grow the introduction's count.
        for (way in WayIn.entries) {
            val answered = chooser.copy(choices = OnboardingChoices(wayIn = way))
            assertEquals(StepProgress(Chapter.INTRO, 5, 5), OnboardingFlow.progress(answered, null))
        }
    }

    @Test
    fun `an introduced phone gets no dots on the chooser`() {
        assertNull(OnboardingFlow.progress(OnboardingFlow.initial(introduced = true), null))
    }

    @Test
    fun `an unknown region projects the longest chapter`() {
        val region = path(WAY_IN, REGION)
        assertEquals(StepProgress(Chapter.SCHOOL, 1, 6), OnboardingFlow.progress(region, null))
    }

    @Test
    fun `choosing a region never lengthens the chapter`() {
        val unknown = OnboardingFlow.progress(path(WAY_IN, REGION), null)!!.n
        for (entry in realCatalog.regions) {
            val plan = regionPlanOf(entry)
            val n = OnboardingFlow.progress(path(WAY_IN, REGION), plan)!!.n
            assertTrue("${entry.key}: $n > $unknown", n <= unknown)
        }
    }

    @Test
    fun `an unsupported region ends the chapter at the provider step`() {
        val plan = regionPlanOf(region("moscow"))
        assertEquals(RegionPlan(showsSchool = false, signable = false), plan)
        assertEquals(StepProgress(Chapter.SCHOOL, 1, 2), OnboardingFlow.progress(path(WAY_IN, REGION), plan))
        assertEquals(StepProgress(Chapter.SCHOOL, 2, 2), OnboardingFlow.progress(path(WAY_IN, REGION, PROVIDER), plan))
    }

    @Test
    fun `k is one-based and never exceeds n along every route`() {
        val routes = listOf(
            listOf(WELCOME, ACKNOWLEDGEMENT, PREFERENCES, PERMISSIONS, WAY_IN, REGION, SCHOOL, PROVIDER, SIGN_IN, IMPORT, SUMMARY),
            listOf(WAY_IN, REGION, PROVIDER, CLASS_CODE, SIGN_IN, IMPORT, SUMMARY),
            listOf(WAY_IN, CLASS_CODE, SIGN_IN, IMPORT, SUMMARY),
            listOf(IMPORT, SUMMARY),
        )
        val plans = listOf(null) + realCatalog.regions.map(::regionPlanOf).distinct()
        for (route in routes) {
            for (plan in plans) {
                for (depth in route.indices) {
                    val progress = OnboardingFlow.progress(OnboardingState(route.take(depth + 1)), plan) ?: continue
                    assertTrue("$route@$depth: $progress", progress.k in 1..progress.n)
                }
            }
        }
    }

    @Test
    fun `the class-code step is uncounted and hides the hero`() {
        assertNull(OnboardingFlow.progress(path(WAY_IN, CLASS_CODE), null))
        assertEquals(HeroMode.HIDDEN, OnboardingFlow.heroMode(CLASS_CODE))
        val afterJoin = OnboardingFlow.commit(path(WAY_IN, CLASS_CODE), SIGN_IN)
        assertEquals(StepProgress(Chapter.CLASS, 1, 3), OnboardingFlow.progress(afterJoin, null))
    }

    @Test
    fun `the search and form steps keep the dots and drop the badge`() {
        for (step in listOf(REGION, SCHOOL, PROVIDER, SIGN_IN)) {
            assertEquals(step.name, HeroMode.COMPACT, OnboardingFlow.heroMode(step))
        }
        for (step in listOf(WELCOME, WAY_IN, IMPORT, SUMMARY)) {
            assertEquals(step.name, HeroMode.FULL, OnboardingFlow.heroMode(step))
        }
    }

    @Test
    fun `a flow the process lost resumes from what the hold was waiting for`() {
        assertEquals(listOf(IMPORT), OnboardingFlow.resume(ShellMode.DIARY, bindingUsable = false, hasDiarySession = true)?.path)
        val signIn = OnboardingFlow.resume(ShellMode.CLASS, bindingUsable = true, hasDiarySession = false)
        assertEquals(listOf(SIGN_IN), signIn?.path)
        assertTrue(signIn!!.choices.classBound)
        assertNull(OnboardingFlow.resume(ShellMode.CLASS, bindingUsable = true, hasDiarySession = true))
        assertNull(OnboardingFlow.resume(ShellMode.CLASS, bindingUsable = false, hasDiarySession = false))
        assertNull(OnboardingFlow.resume(ShellMode.NONE, bindingUsable = true, hasDiarySession = false))
    }

    @Test
    fun `an import the diary ended reopens the sign-in with the floor lowered to it`() {
        val importing = OnboardingFlow.commit(path(WAY_IN, REGION, PROVIDER, SIGN_IN), IMPORT)
        val reopened = OnboardingFlow.reopenSignIn(importing)
        assertEquals(listOf(WAY_IN, REGION, PROVIDER, SIGN_IN), reopened.path)
        assertEquals(3, reopened.floor)
        assertFalse(reopened.canGoBack)

        val resumed = OnboardingFlow.reopenSignIn(path(IMPORT))
        assertEquals(listOf(SIGN_IN), resumed.path)
        assertEquals(0, resumed.floor)
    }

    @Test
    fun `the badge trail keeps a popped step until the path goes elsewhere`() {
        val deep = listOf(WAY_IN, REGION, PROVIDER)
        val popped = listOf(WAY_IN, REGION)
        assertEquals(deep, OnboardingFlow.badgeTrail(deep, popped))
        val elsewhere = listOf(WAY_IN, REGION, SCHOOL)
        assertEquals(elsewhere, OnboardingFlow.badgeTrail(deep, elsewhere))
        assertEquals(listOf(WAY_IN, CLASS_CODE), OnboardingFlow.badgeTrail(popped, listOf(WAY_IN, CLASS_CODE)))
    }

    @Test
    fun `a region's tail follows what it can do`() {
        assertEquals(
            listOf(SCHOOL, PROVIDER, SIGN_IN, IMPORT, SUMMARY),
            OnboardingFlow.regionTail(regionPlanOf(region("samara"))),
        )
        assertEquals(
            listOf(PROVIDER, SIGN_IN, IMPORT, SUMMARY),
            OnboardingFlow.regionTail(regionPlanOf(region("saint-petersburg"))),
        )
        assertEquals(listOf(PROVIDER), OnboardingFlow.regionTail(regionPlanOf(region("tula"))))
    }
}
