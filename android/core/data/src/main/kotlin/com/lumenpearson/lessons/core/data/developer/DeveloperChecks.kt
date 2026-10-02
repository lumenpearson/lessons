package com.lumenpearson.lessons.core.data.developer

import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.ServerStatus
import com.lumenpearson.lessons.core.data.repository.ShellMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl

/** One thing the developer page can check; the page names each in Russian and English. */
enum class CheckKind {
    SERVER,
    DIARY_HOST,
    GITHUB,
    KEYSTORE,
    NETWORK,
    NOTIFICATIONS,
    EXACT_ALARMS,
    BACKGROUND_SYNC,
    WIDGET,
    ACCOUNTS,
    BUILD,
}

/** How a check ended. [SKIP] is «there is nothing here to check», which is not a fault. */
enum class CheckOutcome { PASS, WARN, FAIL, SKIP }

/**
 * One check's answer.
 *
 * @property subject what was checked when a kind is checked more than once —
 *   a diary's host.
 * @property detail technical and English, like a crash report: a status, a
 *   class name, a count. The verdict a reader acts on is [outcome], which the
 *   page words in the reader's language.
 */
data class CheckResult(
    val kind: CheckKind,
    val outcome: CheckOutcome,
    val detail: String,
    val tookMillis: Long? = null,
    val subject: String? = null,
)

/** What this build is, from the module that knows: `:app`'s `BuildConfig`. */
data class BuildFacts(
    val version: String,
    val commit: String,
    val buildType: String,
)

/** Which accounts this phone holds, as much of them as a check may say. */
data class AccountFacts(
    val classes: Int,
    val diary: DiaryTarget?,
    val mode: ShellMode,
)

/**
 * The developer page's checks (#237), run from the phone's own network.
 *
 * That is the point of running them here rather than anywhere else: #235 was a
 * server that could not reach a diary which a phone in Russia might, and only
 * the phone can say what the phone reaches. Every check has its own deadline, so
 * the whole battery answers within [CheckBudgetMillis] whatever hangs — a
 * battery that could itself show nothing would be the defect it is here to find.
 *
 * Nothing here reads a diary: a diary's host is asked for its front page, with
 * no session, so no family's session is kept alive by a developer looking.
 */
class DeveloperChecks internal constructor(
    private val serverStatus: suspend () -> ServerStatus,
    private val diaryOrigins: suspend () -> List<HttpUrl>,
    private val accounts: suspend () -> AccountFacts,
    private val device: DeviceChecks,
) {

    /**
     * Every check at once, on the IO dispatcher — several are binder calls and
     * the catalog the diary hosts come from is an asset — and the build first.
     */
    suspend fun run(build: BuildFacts): List<CheckResult> = withContext(Dispatchers.IO) { all(build) }

    private suspend fun all(build: BuildFacts): List<CheckResult> = coroutineScope {
        val origins = runCatching { diaryOrigins() }.getOrDefault(emptyList())
        val pending = listOf(
            async { checked(CheckKind.SERVER) { server() } },
            async { checked(CheckKind.GITHUB) { device.github() } },
            async { checked(CheckKind.KEYSTORE) { device.keystore() } },
            async { checked(CheckKind.NETWORK) { device.network() } },
            async { checked(CheckKind.NOTIFICATIONS) { device.notifications() } },
            async { checked(CheckKind.EXACT_ALARMS) { device.exactAlarms() } },
            async { checked(CheckKind.BACKGROUND_SYNC) { device.backgroundSync() } },
            async { checked(CheckKind.WIDGET) { device.widgets() } },
            async { checked(CheckKind.ACCOUNTS) { accountsLine() } },
        ) + origins.map { origin ->
            async { checked(CheckKind.DIARY_HOST, subject = origin.host) { device.diaryHost(origin) } }
        }
        listOf(CheckResult(CheckKind.BUILD, CheckOutcome.PASS, device.buildLine(build))) + pending.awaitAll()
    }

    private suspend fun server(): Answer = serverAnswer(serverStatus())

    private suspend fun accountsLine(): Answer {
        val facts = accounts()
        val diary = facts.diary?.let { target ->
            listOfNotNull("diary ${target.provider}", target.region).joinToString(" ")
        } ?: "no diary"
        return CheckOutcome.PASS to "${facts.classes} class(es) · $diary · home ${facts.mode}"
    }
}

internal typealias Answer = Pair<CheckOutcome, String>

/**
 * The about card's badge as a check. Degraded is a warning rather than a
 * failure: the API answers, and it is the reads that gained a column that fail.
 */
internal fun serverAnswer(status: ServerStatus): Answer = when (status) {
    is ServerStatus.Ok -> CheckOutcome.PASS to "api ${status.apiVersion} · schema ${status.schema ?: "?"}"
    is ServerStatus.Degraded ->
        CheckOutcome.WARN to "degraded · schema ${status.schema ?: "?"}, code expects ${status.expected ?: "?"}"
    ServerStatus.NotConfigured -> CheckOutcome.SKIP to "no server address"
    ServerStatus.NeedsHttps -> CheckOutcome.FAIL to "http:// address in a build that sends https only"
    ServerStatus.Unreachable -> CheckOutcome.FAIL to "unreachable"
    ServerStatus.Checking -> CheckOutcome.SKIP to "not asked"
}

/** Long enough for a diary host's connect timeout and a slow TLS answer; short enough to wait for. */
const val CheckBudgetMillis: Long = 20_000L

private const val NanosPerMilli = 1_000_000L
private const val MillisPerSecond = 1_000L

/**
 * One check, timed and bounded. Whatever it throws is its answer: a check
 * exists to say what went wrong, so nothing it catches is turned into anything
 * else, and a cancellation is passed on as one.
 */
@Suppress("TooGenericExceptionCaught") // Every failure of a check is that check's answer; see above.
internal suspend fun checked(
    kind: CheckKind,
    subject: String? = null,
    block: suspend () -> Answer,
): CheckResult {
    val started = System.nanoTime()
    val answer = withTimeoutOrNull(CheckBudgetMillis) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            CheckOutcome.FAIL to describe(failure)
        }
    } ?: (CheckOutcome.FAIL to "no answer in ${CheckBudgetMillis / MillisPerSecond} s")
    val took = (System.nanoTime() - started) / NanosPerMilli
    return CheckResult(kind, answer.first, answer.second, took, subject)
}

/** `Unavailable (SocketTimeoutException)` — the failure and, where there is one, what caused it. */
internal fun describe(failure: Throwable): String {
    val name = failure::class.java.simpleName.ifEmpty { "failure" }
    val cause = failure.cause?.let { it::class.java.simpleName }
    return if (cause == null || cause == name) name else "$name ($cause)"
}
