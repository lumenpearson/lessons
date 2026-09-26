package com.lumenpearson.lessons.ui.diary

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.designsystem.text.correctedString

/**
 * The one place a [DiarySignInProblem] becomes words.
 *
 * Every screen that can fail to get somebody into their diary — the sign-in
 * form in settings, the diary home, and the onboarding's school search, sign-in
 * and import — switches on the same type and says what this file says. Two
 * mappings were how «слишком много попыток» came to read «Не получилось: 429»
 * and a diary switched off on the server read «дневник не отвечает, попробуйте
 * позже», which no amount of waiting fixes (#153).
 *
 * The sentence is picked here as a value ([DiaryProblemMessage]) and only then
 * read out of the resources, so which sentence a problem gets is a JVM test
 * rather than a screenshot, and every `diary_problem_*` name is referenced from
 * a `when` that the compiler holds exhaustive.
 *
 * Where a problem carries the diary's host, the sentence names it: «не
 * отвечает» about a named server is something a family can repeat to the
 * school, and «не отвечает» alone is not.
 */
sealed interface DiaryProblemMessage {

    /** A plain string, with its format arguments. */
    data class Line(@param:StringRes val id: Int, val args: List<String> = emptyList()) :
        DiaryProblemMessage

    /** A sentence with a count in it; [count] is also its only argument. */
    data class Counted(@param:PluralsRes val id: Int, val count: Int) : DiaryProblemMessage

    /**
     * [main], then what the diary itself said, when it said something.
     * The diary's words are shown, never interpreted: they are the one part of
     * a refusal that can say «учётная запись заблокирована» rather than
     * «неверный пароль».
     */
    data class WithUpstream(val main: DiaryProblemMessage, val upstream: String) :
        DiaryProblemMessage
}

/**
 * Which sentence [problem] gets. Pure, so a test can hold every case of it.
 *
 * Two sentences depend on who reads them, and say something false to the
 * wrong reader.
 *
 * @param firstRun read in the first run, where there are no settings yet: the
 *   server's address is not «в разделе «Синхронизация»», which does not exist
 *   until the flow is over, but on the steps themselves.
 * @param inClass read on a phone already in a class: the way round a diary
 *   that refuses our server is not a class code — the family has one.
 */
fun diaryProblemMessage(
    problem: DiarySignInProblem,
    firstRun: Boolean = false,
    inClass: Boolean = false,
): DiaryProblemMessage = when (problem) {
    is DiarySignInProblem.WrongPassword -> {
        val main = DiaryProblemMessage.Line(R.string.diary_problem_wrong_password)
        val said = problem.upstreamMessage?.trim()?.take(UpstreamMessageMax)
        if (said.isNullOrEmpty()) main else DiaryProblemMessage.WithUpstream(main, said)
    }
    is DiarySignInProblem.GosuslugiOnly -> line(R.string.diary_problem_gosuslugi_only)
    is DiarySignInProblem.ProviderUnavailable ->
        hosted(problem.host, R.string.diary_problem_host_down, R.string.diary_problem_unavailable)
    is DiarySignInProblem.ProviderRefusesPhone ->
        hosted(problem.host, R.string.diary_problem_host_refuses_phone, R.string.diary_problem_refuses_phone)
    is DiarySignInProblem.ProviderUntrusted ->
        hosted(problem.host, R.string.diary_problem_host_untrusted, R.string.diary_problem_untrusted)
    // Without a host this is our server's 502: the diary answered it, not the
    // phone, and the sentence that says so already exists.
    is DiarySignInProblem.ProviderUnreadable ->
        hosted(problem.host, R.string.diary_problem_host_unreadable, R.string.diary_problem_unreadable)
    is DiarySignInProblem.ProviderOffline ->
        hosted(problem.host, R.string.diary_problem_host_offline, R.string.diary_problem_offline_upstream)
    is DiarySignInProblem.Timeout ->
        hosted(problem.host, R.string.diary_problem_host_timeout, R.string.diary_problem_timeout)
    DiarySignInProblem.Offline ->
        line(if (firstRun) R.string.diary_problem_offline_first_run else R.string.diary_problem_offline)
    DiarySignInProblem.RegisterUnreachable -> line(R.string.diary_problem_register_unreachable)
    DiarySignInProblem.ServerMissing ->
        line(if (firstRun) R.string.diary_problem_server_missing_first_run else R.string.diary_problem_server_missing)
    DiarySignInProblem.ServerTooOld -> line(R.string.diary_problem_server_too_old)
    DiarySignInProblem.ServerDisabled -> line(R.string.diary_problem_server_disabled)
    DiarySignInProblem.RegionNotServed -> line(R.string.diary_problem_region_not_served)
    DiarySignInProblem.ServerRefusedSession -> line(
        if (inClass) R.string.diary_problem_server_refused_session_in_class else R.string.diary_problem_server_refused_session,
    )
    DiarySignInProblem.ServerAddressRefused -> line(R.string.diary_problem_server_address_refused)
    DiarySignInProblem.NoStudent -> line(R.string.diary_problem_no_student)
    is DiarySignInProblem.TooManyAttempts -> problem.retryAfterSeconds
        ?.takeIf { it > 0 }
        // Rounded up: «через 0 минут» for a wait of forty seconds is a promise
        // the next attempt will break.
        ?.let { seconds -> DiaryProblemMessage.Counted(R.plurals.diary_problem_too_many_wait, minutesFor(seconds)) }
        ?: line(R.string.diary_problem_too_many)
    DiarySignInProblem.SessionAgedOut -> line(R.string.diary_problem_session_aged_out)
    DiarySignInProblem.LoginTooShort -> line(R.string.diary_problem_login_too_short)
    DiarySignInProblem.ReauthRequired -> line(R.string.diary_problem_reauth)
    DiarySignInProblem.SignInRequired -> line(R.string.diary_problem_signed_out)
    is DiarySignInProblem.Unexpected -> DiaryProblemMessage.Line(
        R.string.diary_problem_unknown,
        listOf(problem.detail?.takeIf { it.isNotBlank() } ?: problem.message.orEmpty()),
    )
}

/**
 * Whether the card or dialog showing [this] offers «Повторить».
 *
 * The data layer already decided what helps next ([DiarySignInProblem.action]);
 * a screen that re-derived it would offer a retry for a diary switched off on
 * the server, which answers the same way every time.
 */
val DiarySignInProblem.offersRetry: Boolean
    get() = action == DiarySignInProblem.Action.RETRY

/** [diaryProblemMessage], read out of the resources — through correction mode. */
@Composable
fun DiarySignInProblem.asText(firstRun: Boolean = false, inClass: Boolean = false): String =
    diaryProblemMessage(this, firstRun = firstRun, inClass = inClass).asText()

@Composable
private fun DiaryProblemMessage.asText(): String = when (this) {
    is DiaryProblemMessage.Line ->
        if (args.isEmpty()) correctedString(id) else correctedString(id, *args.toTypedArray())
    // Plurals are not correctable: correction mode keys by string resource,
    // and `ManagementSheets` makes the same trade for the same reason.
    is DiaryProblemMessage.Counted -> pluralStringResource(id, count, count)
    is DiaryProblemMessage.WithUpstream ->
        main.asText() + " " + correctedString(R.string.diary_problem_upstream_said, upstream)
}

/**
 * The problem a failed diary **read** is, for the cases that have one; `null`
 * for the three that are about what this app asked rather than about getting
 * in, which keep sentences of their own.
 */
fun DiaryFailure.asProblem(): DiarySignInProblem? = when (this) {
    DiaryFailure.UnknownStudent, DiaryFailure.BadRange, DiaryFailure.Rejected -> null
    else -> DiarySignInProblem.of(this)
}

private fun line(@StringRes id: Int) = DiaryProblemMessage.Line(id)

private fun hosted(host: String?, @StringRes withHost: Int, @StringRes without: Int) =
    host?.takeIf { it.isNotBlank() }
        ?.let { DiaryProblemMessage.Line(withHost, listOf(it)) }
        ?: DiaryProblemMessage.Line(without)

private fun minutesFor(seconds: Long): Int =
    ((seconds + SecondsPerMinute - 1) / SecondsPerMinute).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

private const val SecondsPerMinute = 60L

/**
 * Enough for any sentence a diary writes about a refusal; past it the text is
 * a page somebody's server sent by mistake, and a dialog is not where to read it.
 */
private const val UpstreamMessageMax = 200
