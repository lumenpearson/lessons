package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.runtime.Immutable
import com.lumenpearson.lessons.core.data.repository.DiaryRegistration
import com.lumenpearson.lessons.core.data.repository.DiarySignIn
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.upstream.UpstreamSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The diary's session between the phone's sign-in and our server's answer, as
 * the sign-in step holds it: opaque, so that nothing on this side can read a
 * token out of it — and so that a test can hand over its own, which the real
 * [UpstreamSession]'s internal constructors would not allow.
 */
interface HeldUpstream {
    val target: DiaryTarget
}

/**
 * [DiarySignIn]'s four steps as the onboarding uses them, one at a time: the
 * step has to hold the session between the diary's answer and ours, so that a
 * registration that failed for want of a network is retried without asking for
 * the password again.
 */
interface OnboardingSignIn {
    suspend fun preflight(target: DiaryTarget): Result<Unit>
    suspend fun open(target: DiaryTarget, password: String): Result<HeldUpstream>
    suspend fun register(held: HeldUpstream): Result<DiaryRegistration>
    suspend fun discard(held: HeldUpstream)
}

/** The real sign-in, its session wrapped so nothing but this adapter unwraps it. */
fun DiarySignIn.forOnboarding(): OnboardingSignIn = object : OnboardingSignIn {

    private inner class Held(val session: UpstreamSession) : HeldUpstream {
        override val target: DiaryTarget get() = session.target
        override fun toString(): String = "HeldUpstream(<redacted>)"
    }

    override suspend fun preflight(target: DiaryTarget) = this@forOnboarding.preflight(target)

    override suspend fun open(target: DiaryTarget, password: String): Result<HeldUpstream> =
        openUpstream(target, password).map { Held(it) }

    override suspend fun register(held: HeldUpstream): Result<DiaryRegistration> =
        this@forOnboarding.register((held as Held).session)

    override suspend fun discard(held: HeldUpstream) = this@forOnboarding.discard((held as Held).session)
}

/** What the sign-in is doing, for the line under the form. */
enum class SignInStage {
    /** Asking our server whether it can keep a session for this diary. */
    CHECKING,

    /** The phone signing in to the diary itself, with the password. */
    UPSTREAM,

    /** The diary's session going to our server. */
    REGISTER,
}

/**
 * @property login not a secret, but not saved either: it lives as long as this
 *   step's holder, which is the view model's memory (the saved-state test
 *   holds that no login reaches the bundle).
 * @property problem the last failure, until the form is touched again.
 * @property dialog the failure is still to be read in its pop-up.
 * @property sessionHeld the diary let the phone in and our server has not
 *   answered: «Повторить» re-sends the session without the password.
 */
@Immutable
data class SignInUi(
    val login: String = "",
    val stage: SignInStage? = null,
    val problem: DiarySignInProblem? = null,
    val dialog: Boolean = false,
    val sessionHeld: Boolean = false,
) {
    val busy: Boolean get() = stage != null
}

/**
 * The sign-in step's state holder.
 *
 * The diary's session lives in [held], a plain field of this object, and
 * nowhere else: never in `SavedStateHandle` (the bundle is written to disk),
 * DataStore, Room or a log. It is dropped on the server's success, on a
 * failure that already discarded it, and on [abandon] — leaving the step —
 * which also says goodbye to it upstream. The password is never a field at
 * all: it arrives as [submit]'s argument and leaves as [OnboardingSignIn.open]'s.
 *
 * Once the password has gone to the diary, nothing on this side may drop the
 * answer on the floor. [OnboardingSignIn.open] and [OnboardingSignIn.register]
 * run to their end even when the step is cancelled: a cancelled `open` may
 * still have opened a session upstream, and a cancelled `register` still has
 * the whole request on our server, which finishes it and keeps a row whose
 * bearer only this phone would ever have been told. A session the diary
 * opened after the step was left is discarded; one our server took is stored
 * by the registration itself, so the phone holds the only key to that row.
 * The view model refuses to leave the step while [SignInUi.busy], so in
 * practice only the process ending cancels one of these.
 */
class SignInStep(
    private val scope: CoroutineScope,
    private val signIn: OnboardingSignIn,
) {
    private val mutable = MutableStateFlow(SignInUi())
    val state: StateFlow<SignInUi> = mutable.asStateFlow()

    private var held: HeldUpstream? = null
    private var job: Job? = null

    fun setLogin(login: String) = mutable.update { it.copy(login = login) }

    /** Any keystroke: the red fields go back to normal. */
    fun edited() = mutable.update { it.copy(problem = null, dialog = false) }

    fun dismissDialog() = mutable.update { it.copy(dialog = false) }

    /**
     * Preflight, the diary, then our server; [onRegistered] once the server
     * has answered with its bearer. A session already held — a registration
     * that failed — is discarded first: a new password means a new session.
     */
    fun submit(target: DiaryTarget, password: String, onRegistered: () -> Unit) {
        if (job?.isActive == true) return
        val stale = held
        held = null
        job = scope.launch {
            stale?.let { signIn.discard(it) }
            val withLogin = target.copy(login = mutable.value.login)
            mutable.update { it.copy(stage = SignInStage.CHECKING, problem = null, dialog = false, sessionHeld = false) }
            signIn.preflight(withLogin).onFailure { fail(it); return@launch }
            mutable.update { it.copy(stage = SignInStage.UPSTREAM) }
            val step = currentCoroutineContext().job
            val opened = withContext(NonCancellable) {
                signIn.open(withLogin, password).onSuccess { session ->
                    // Left while the diary was answering: nobody will hold
                    // this one, so it is ended now rather than left to idle.
                    // Otherwise it is held before this block returns, so a
                    // leave landing on the way out finds it in [abandon].
                    if (step.isCancelled) signIn.discard(session) else held = session
                }
            }
            currentCoroutineContext().ensureActive()
            opened.onFailure { fail(it); return@launch }
            registerHeld(onRegistered)
        }
    }

    /** The server again, with the session still in hand; no password. */
    fun retry(onRegistered: () -> Unit) {
        if (job?.isActive == true || held == null) return
        job = scope.launch { registerHeld(onRegistered) }
    }

    /**
     * Leaving the step: whatever is in flight stops, and a session the server
     * never took is ended with the diary rather than left to idle there. A
     * session out for registration is not [held] (see [registerHeld]), so it
     * is never ended upstream under our server while the server adopts it.
     */
    fun abandon() {
        job?.cancel()
        job = null
        val stale = held
        held = null
        mutable.update { SignInUi(login = it.login) }
        if (stale != null) scope.launch { signIn.discard(stale) }
    }

    /**
     * [abandon], and the login with it: a flow that has finished is over for
     * whoever typed it, and the next one may be somebody else's.
     */
    fun reset() {
        abandon()
        mutable.value = SignInUi()
    }

    private suspend fun registerHeld(onRegistered: () -> Unit) {
        val session = held ?: return
        // Out of [held] while it is out for registration: [abandon] must not
        // say goodbye upstream to a session our server may be adopting.
        held = null
        mutable.update { it.copy(stage = SignInStage.REGISTER, problem = null, dialog = false) }
        val step = currentCoroutineContext().job
        val outcome = withContext(NonCancellable) {
            signIn.register(session).onFailure { failure ->
                // `register` has already discarded a session it will not see
                // again; holding it here would offer a retry that cannot work.
                if (!problemOf(failure).retryKeepsSession) return@onFailure
                // Left while our server was answering, and it did not keep
                // the session: nobody is left to retry it, so it is ended.
                if (step.isCancelled) signIn.discard(session) else held = session
            }
        }
        currentCoroutineContext().ensureActive()
        outcome.fold(
            onSuccess = {
                mutable.update { it.copy(stage = null, problem = null, dialog = false, sessionHeld = false) }
                onRegistered()
            },
            onFailure = ::fail,
        )
    }

    private fun fail(failure: Throwable) {
        val problem = problemOf(failure)
        mutable.update {
            it.copy(stage = null, problem = problem, dialog = true, sessionHeld = held != null)
        }
    }

    private fun problemOf(failure: Throwable): DiarySignInProblem =
        failure as? DiarySignInProblem ?: DiarySignInProblem.of(failure)
}
