package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.runtime.Immutable
import com.lumenpearson.lessons.core.data.repository.ShellMode

/**
 * Every screen the first run can show.
 *
 * The first five are the introduction, in Essentials' own order: say what this
 * is, say what it is not and let the user opt out of the one thing it records,
 * set the handful of preferences that change how the app feels, ask the system
 * for what the alerts need — and only then ask for a way in ([WAY_IN]). Putting
 * a credential first asks a stranger for it on a screen that has not yet said
 * what it is for.
 *
 * [PERMISSIONS] stays before [WAY_IN] although a family that takes the diary
 * way in gets no alerts (K23): the owner put the chooser after the existing
 * steps, and moving the ask behind a branch would put it on one path only.
 *
 * From [WAY_IN] the flow branches, which is why it is a path and not an index
 * into this enum (see [OnboardingState]): [CLASS_CODE] is the join screen, and
 * [REGION] → [SCHOOL] → [PROVIDER] → [SIGN_IN] → [IMPORT] → [SUMMARY] is the
 * family's own diary. A class whose join names a diary goes on from
 * [CLASS_CODE] to [SIGN_IN] as well. Reaching [WAY_IN] is what counts as having
 * seen the introduction.
 */
enum class OnboardingStep {
    WELCOME,
    ACKNOWLEDGEMENT,
    PREFERENCES,
    PERMISSIONS,
    WAY_IN,
    CLASS_CODE,
    REGION,
    SCHOOL,
    PROVIDER,
    SIGN_IN,
    IMPORT,
    SUMMARY,
}

/** The two ways in the chooser offers. */
enum class WayIn { CLASS_CODE, FIND_SCHOOL }

/**
 * Which run of steps the dots count.
 *
 * The dots count a chapter rather than the whole flow, because the whole flow
 * is not known until the chooser has been answered, and a count that grew at
 * the chooser would read as the flow getting longer — which is exactly what the
 * dots must never do.
 */
enum class Chapter { INTRO, SCHOOL, CLASS, DIARY }

/** How much of the badge strip a step shows. */
enum class HeroMode {
    /** The badge and the dots. */
    FULL,

    /** The dots only: a search or a keyboard needs the height more than a shape does. */
    COMPACT,

    /** Nothing: the join screen has a heading of its own. */
    HIDDEN,
}

/** The dots: step [k] of [n] in [chapter]. */
@Immutable
data class StepProgress(val chapter: Chapter, val k: Int, val n: Int)

/** A school picked on [OnboardingStep.SCHOOL]: the diary's own id, never a register row. */
@Immutable
data class PickedSchool(val id: Long, val name: String)

/**
 * What a region's catalog row decides about the rest of the chapter.
 *
 * @property showsSchool the region's signable diary is «Сетевой город», whose
 *   sign-in needs a school picked from the region's own list.
 * @property signable the app's own form can sign in to one of its diaries.
 */
@Immutable
data class RegionPlan(val showsSchool: Boolean, val signable: Boolean)

/**
 * The answers the flow has collected. None is a secret: the login and the
 * password are never here (see [OnboardingState]).
 *
 * @property region the catalog key.
 * @property schoolHint the school name typed on the region step, carried to
 *   prefill the school search.
 * @property system the index into the region's catalog systems chosen on the
 *   provider step.
 * @property classBound [OnboardingStep.SIGN_IN] is the joined class's diary
 *   rather than one the family picked.
 * @property resumeFrom the import phase to pick up from after a re-sign-in.
 */
@Immutable
data class OnboardingChoices(
    val wayIn: WayIn? = null,
    val region: String? = null,
    val schoolHint: String? = null,
    val school: PickedSchool? = null,
    val system: Int? = null,
    val classBound: Boolean = false,
    val resumeFrom: String? = null,
)

/**
 * Where the flow is: the steps walked, how far back may be walked, and the
 * answers.
 *
 * A path rather than a step because the flow branches: going back is popping,
 * the slide's direction is depth, and the badge morphs along the path. Ordinal
 * order means nothing once PROVIDER can lead to CLASS_CODE.
 *
 * [floor] is raised by a commit — a join, a registration, a finished import —
 * so that the steps before it cannot be walked back into: the join screen must
 * not reopen over a class already joined, and the sign-in must not mint a
 * second server session.
 *
 * Saved whole in the view model's `SavedStateHandle` ([toSaved]), and nothing
 * else of the flow is: the login lives in the sign-in step's memory, the
 * password in the form's, the diary's session in a plain field.
 */
@Immutable
data class OnboardingState(
    val path: List<OnboardingStep>,
    val floor: Int = 0,
    val choices: OnboardingChoices = OnboardingChoices(),
) {
    init {
        require(path.isNotEmpty()) { "A flow is always on some step" }
    }

    val current: OnboardingStep get() = path.last()
    val depth: Int get() = path.lastIndex
    val canGoBack: Boolean get() = depth > floor
}

/**
 * The flow's rules, as functions of its state: no Compose and no Android, so
 * each can be asked on the JVM.
 */
object OnboardingFlow {

    private val Intro = listOf(
        OnboardingStep.WELCOME,
        OnboardingStep.ACKNOWLEDGEMENT,
        OnboardingStep.PREFERENCES,
        OnboardingStep.PERMISSIONS,
        OnboardingStep.WAY_IN,
    )

    /**
     * A fresh install starts at the welcome; one that has seen the introduction
     * — which is what leaving the last class makes of it — starts at the
     * chooser, where both ways in are, rather than on a bare join screen.
     */
    fun initial(introduced: Boolean): OnboardingState =
        OnboardingState(path = listOf(if (introduced) OnboardingStep.WAY_IN else OnboardingStep.WELCOME))

    /**
     * Where a flow the process lost picks up, when the stored hold says it was
     * mid-way: a registered diary goes on to its import, a joined class whose
     * diary this build can sign in to goes on to that sign-in, and anything
     * else was finished by the write it held for. `null` is «finish».
     */
    fun resume(mode: ShellMode, bindingUsable: Boolean, hasDiarySession: Boolean): OnboardingState? =
        when {
            mode == ShellMode.DIARY -> OnboardingState(path = listOf(OnboardingStep.IMPORT))
            mode == ShellMode.CLASS && bindingUsable && !hasDiarySession -> OnboardingState(
                path = listOf(OnboardingStep.SIGN_IN),
                choices = OnboardingChoices(classBound = true),
            )
            else -> null
        }

    fun push(state: OnboardingState, next: OnboardingStep): OnboardingState =
        state.copy(path = state.path + next)

    /** [push], with the floor raised to the new step: nothing before it can be walked back into. */
    fun commit(state: OnboardingState, next: OnboardingStep): OnboardingState {
        val pushed = push(state, next)
        return pushed.copy(floor = pushed.depth)
    }

    /**
     * One step back, or the state unchanged at the floor.
     *
     * Unchanged rather than an error, because two back events can be drained in
     * one input pass before `BackHandler`'s `enabled` has caught up — tapping
     * back twice during the slide — and the second must find nothing to do.
     */
    fun back(state: OnboardingState): OnboardingState =
        if (state.canGoBack) state.copy(path = state.path.dropLast(1)) else state

    /**
     * Back to the sign-in with the floor lowered to it, for an import the
     * diary ended: the session died, and only a new one continues. When the
     * path did not come through a sign-in — a flow resumed straight into its
     * import — the sign-in replaces the import.
     */
    fun reopenSignIn(state: OnboardingState): OnboardingState {
        val before = state.path.getOrNull(state.depth - 1)
        return if (before == OnboardingStep.SIGN_IN) {
            state.copy(path = state.path.dropLast(1), floor = state.depth - 1)
        } else {
            state.copy(path = state.path.dropLast(1) + OnboardingStep.SIGN_IN, floor = state.depth)
        }
    }

    /** The whole flow again from the chooser, forgetting every answer. */
    fun restart(): OnboardingState = OnboardingState(path = listOf(OnboardingStep.WAY_IN))

    /**
     * What the region's diaries make of the rest of the chapter; `null` for a
     * region not picked yet, which projects the longest route.
     */
    fun regionTail(plan: RegionPlan?): List<OnboardingStep> = buildList {
        if (plan == null || plan.showsSchool) add(OnboardingStep.SCHOOL)
        add(OnboardingStep.PROVIDER)
        if (plan == null || plan.signable) {
            add(OnboardingStep.SIGN_IN)
            add(OnboardingStep.IMPORT)
            add(OnboardingStep.SUMMARY)
        }
    }

    /**
     * The steps still ahead of [state]'s current one, by what is known now.
     * An unknown projects the longest route, so that a count built on it can
     * only shrink as the answers come in.
     */
    fun remainder(state: OnboardingState, plan: RegionPlan?): List<OnboardingStep> =
        when (val step = state.current) {
            OnboardingStep.WELCOME,
            OnboardingStep.ACKNOWLEDGEMENT,
            OnboardingStep.PREFERENCES,
            OnboardingStep.PERMISSIONS -> Intro.drop(Intro.indexOf(step) + 1)

            OnboardingStep.WAY_IN -> when (state.choices.wayIn) {
                WayIn.CLASS_CODE -> listOf(OnboardingStep.CLASS_CODE)
                WayIn.FIND_SCHOOL -> listOf(OnboardingStep.REGION) + regionTail(plan)
                null -> emptyList()
            }

            OnboardingStep.REGION -> regionTail(plan)

            OnboardingStep.SCHOOL,
            OnboardingStep.PROVIDER -> regionTail(plan).let { tail -> tail.drop(tail.indexOf(step) + 1) }

            // Nothing is known until the join answers, and the join screen
            // shows no dots; the next chapter starts behind it.
            OnboardingStep.CLASS_CODE -> emptyList()

            OnboardingStep.SIGN_IN -> listOf(OnboardingStep.IMPORT, OnboardingStep.SUMMARY)
            OnboardingStep.IMPORT -> listOf(OnboardingStep.SUMMARY)
            OnboardingStep.SUMMARY -> emptyList()
        }

    /**
     * Step k of n in the current chapter, or `null` where no dots are drawn:
     * on the join screen, and wherever the chapter is one step long.
     *
     * A chapter starts at the beginning of the path, after the last chooser and
     * after the last join screen. The chooser itself closes the introduction,
     * so what lies beyond it is never counted there.
     */
    fun progress(state: OnboardingState, plan: RegionPlan?): StepProgress? {
        if (state.current == OnboardingStep.CLASS_CODE) return null
        val start = chapterStart(state)
        val walked = state.path.subList(start, state.path.size)
        val ahead = if (state.current == OnboardingStep.WAY_IN) emptyList() else buildList {
            for (step in remainder(state, plan)) {
                if (step == OnboardingStep.CLASS_CODE) break
                add(step)
                if (step == OnboardingStep.WAY_IN) break
            }
        }
        val counted = (walked + ahead).filter { it != OnboardingStep.CLASS_CODE }
        val n = counted.size
        val k = walked.count { it != OnboardingStep.CLASS_CODE }
        if (n < 2 || k < 1) return null
        return StepProgress(chapter = chapterOf(state, start), k = k, n = n)
    }

    private fun chapterStart(state: OnboardingState): Int {
        var start = 0
        for (index in 0 until state.depth) {
            val step = state.path[index]
            if (step == OnboardingStep.WAY_IN || step == OnboardingStep.CLASS_CODE) start = index + 1
        }
        return start
    }

    private fun chapterOf(state: OnboardingState, start: Int): Chapter =
        when (state.path.getOrNull(start - 1)) {
            OnboardingStep.WAY_IN -> Chapter.SCHOOL
            OnboardingStep.CLASS_CODE -> Chapter.CLASS
            else -> if (state.path.first() in Intro) Chapter.INTRO else Chapter.DIARY
        }

    fun heroMode(step: OnboardingStep): HeroMode = when (step) {
        OnboardingStep.CLASS_CODE -> HeroMode.HIDDEN
        OnboardingStep.REGION,
        OnboardingStep.SCHOOL,
        OnboardingStep.PROVIDER,
        OnboardingStep.SIGN_IN -> HeroMode.COMPACT
        else -> HeroMode.FULL
    }

    /**
     * Forward is deeper. Not ordinal: PROVIDER → CLASS_CODE goes to a lower
     * ordinal and is plainly a step forward.
     */
    fun isForward(fromDepth: Int, toDepth: Int): Boolean = toDepth > fromDepth

    /**
     * The steps the badge morphs along: [path], but still holding the steps a
     * back gesture has just popped, so the badge has the shape it is leaving to
     * morph *from*. A path that went elsewhere at some depth replaces the trail.
     */
    fun badgeTrail(previous: List<OnboardingStep>, path: List<OnboardingStep>): List<OnboardingStep> =
        if (previous.size >= path.size && previous.subList(0, path.size) == path) previous else path
}

/** The saved-state keys, one per field, so a key can never hold two things. */
internal object OnboardingKeys {
    const val PATH = "onboarding.path"
    const val FLOOR = "onboarding.floor"
    const val WAY_IN = "onboarding.way_in"
    const val REGION = "onboarding.region"
    const val SCHOOL_HINT = "onboarding.school_hint"
    const val SCHOOL_ID = "onboarding.school_id"
    const val SCHOOL_NAME = "onboarding.school_name"
    const val SYSTEM = "onboarding.system"
    const val CLASS_BOUND = "onboarding.class_bound"
    const val RESUME_FROM = "onboarding.resume_from"

    val all = listOf(
        PATH, FLOOR, WAY_IN, REGION, SCHOOL_HINT, SCHOOL_ID, SCHOOL_NAME, SYSTEM, CLASS_BOUND, RESUME_FROM,
    )
}

/**
 * The state as bundle values: strings, numbers and one `ArrayList<String>`.
 *
 * Plain keys rather than one encoded string, because the path is never empty
 * and nothing here needs escaping; and a pure pair of functions so the mapping
 * is asked on the JVM rather than trusted.
 */
fun OnboardingState.toSaved(): Map<String, Any?> = mapOf(
    OnboardingKeys.PATH to ArrayList(path.map { it.name }),
    OnboardingKeys.FLOOR to floor,
    OnboardingKeys.WAY_IN to choices.wayIn?.name,
    OnboardingKeys.REGION to choices.region,
    OnboardingKeys.SCHOOL_HINT to choices.schoolHint,
    OnboardingKeys.SCHOOL_ID to choices.school?.id,
    OnboardingKeys.SCHOOL_NAME to choices.school?.name,
    OnboardingKeys.SYSTEM to choices.system,
    OnboardingKeys.CLASS_BOUND to choices.classBound,
    OnboardingKeys.RESUME_FROM to choices.resumeFrom,
)

/**
 * [toSaved] read back, or `null` for anything that is not one — an unknown
 * step name from another build, a floor past the path. Nothing is better than
 * a guessed flow: the caller then starts afresh.
 */
fun onboardingStateOf(saved: Map<String, Any?>): OnboardingState? {
    val names = (saved[OnboardingKeys.PATH] as? List<*>) ?: return null
    val path = names.map { name ->
        OnboardingStep.entries.firstOrNull { it.name == name } ?: return null
    }
    if (path.isEmpty()) return null
    val floor = (saved[OnboardingKeys.FLOOR] as? Int) ?: 0
    if (floor !in path.indices) return null
    val schoolId = saved[OnboardingKeys.SCHOOL_ID] as? Long
    val schoolName = saved[OnboardingKeys.SCHOOL_NAME] as? String
    return OnboardingState(
        path = path,
        floor = floor,
        choices = OnboardingChoices(
            wayIn = (saved[OnboardingKeys.WAY_IN] as? String)?.let { name ->
                WayIn.entries.firstOrNull { it.name == name }
            },
            region = saved[OnboardingKeys.REGION] as? String,
            schoolHint = saved[OnboardingKeys.SCHOOL_HINT] as? String,
            school = if (schoolId != null && schoolName != null) PickedSchool(schoolId, schoolName) else null,
            system = saved[OnboardingKeys.SYSTEM] as? Int,
            classBound = saved[OnboardingKeys.CLASS_BOUND] as? Boolean ?: false,
            resumeFrom = saved[OnboardingKeys.RESUME_FROM] as? String,
        ),
    )
}
