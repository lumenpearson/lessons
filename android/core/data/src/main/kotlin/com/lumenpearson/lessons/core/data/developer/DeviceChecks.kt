package com.lumenpearson.lessons.core.data.developer

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumenpearson.lessons.core.data.datastore.AesGcmTokenCipher
import com.lumenpearson.lessons.core.data.datastore.AndroidKeystoreKeys
import com.lumenpearson.lessons.core.data.datastore.TokenVault
import com.lumenpearson.lessons.core.data.github.GithubApi
import com.lumenpearson.lessons.core.data.sync.SyncScheduler
import com.lumenpearson.lessons.core.data.upstream.UpstreamHttp
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The checks that ask the phone rather than our server: its Keystore, its
 * network, what it lets the app do, and what answers it from outside.
 *
 * Each returns its verdict and a technical line; [checked] times it, bounds it
 * and turns whatever it throws into a failure.
 */
class DeviceChecks internal constructor(
    private val context: Context,
    private val diaryClient: () -> OkHttpClient,
) {

    /**
     * A diary's host, asked for its front page with no session. Any answer at
     * all is a pass — a `302` is a host that answered — and the failure is the
     * one `UpstreamHttp` classifies, cause and all, so a TLS stall reads as one.
     */
    internal suspend fun diaryHost(origin: HttpUrl): Answer {
        val answer = UpstreamHttp.fetch(diaryClient(), Request.Builder().url(origin).get().build())
        return CheckOutcome.PASS to "HTTP ${answer.code}"
    }

    internal suspend fun github(): Answer {
        val agent = GithubApi.userAgent(GithubApi.installedVersion(context))
        val request = GithubApi.apiRequest(GithubApi.API_BASE + "/rate_limit", agent).get().build()
        val answer = UpstreamHttp.fetch(GithubApi.client, request)
        val outcome = if (answer.code in HttpOk) CheckOutcome.PASS else CheckOutcome.WARN
        return outcome to "HTTP ${answer.code}"
    }

    /**
     * A seal and an open through the real key (#201), with random bytes that
     * are never written anywhere. What it proves is that the key this phone's
     * bearers are sealed with exists, or can be made, and works both ways —
     * not that a given stored bearer opens.
     */
    internal suspend fun keystore(): Answer = withContext(Dispatchers.IO) {
        val cipher = AesGcmTokenCipher(AndroidKeystoreKeys(TokenVault.KEY_ALIAS))
        val probe = ByteArray(ProbeBytes).also { SecureRandom().nextBytes(it) }
        val sealed = cipher.seal(probe)
        val opened = cipher.open(sealed)
        if (opened.contentEquals(probe) && !sealed.contentEquals(probe)) {
            CheckOutcome.PASS to "AES-GCM round trip through ${TokenVault.KEY_ALIAS}"
        } else {
            CheckOutcome.FAIL to "the round trip changed the bytes"
        }
    }

    /**
     * The transports of the network in use, and whether Android has validated
     * it. A VPN is a warning rather than a fault: a regional diary that answers
     * only Russian addresses (#235) refuses a VPN's foreign exit the same way.
     */
    internal fun network(): Answer {
        val capabilities = context.getSystemService(ConnectivityManager::class.java)
            ?.let { manager -> manager.activeNetwork?.let(manager::getNetworkCapabilities) }
            ?: return CheckOutcome.FAIL to "no active network"
        val transports = Transports.filter { (id, _) -> capabilities.hasTransport(id) }.map { it.second }
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val line = transports.ifEmpty { listOf("unknown") }.joinToString(", ") +
            if (validated) " · validated" else " · not validated"
        return (if (validated && !vpn) CheckOutcome.PASS else CheckOutcome.WARN) to line
    }

    internal fun notifications(): Answer =
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            CheckOutcome.PASS to "enabled"
        } else {
            CheckOutcome.WARN to "disabled for the app"
        }

    internal fun exactAlarms(): Answer {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return CheckOutcome.PASS to "not restricted below API 31"
        val allowed = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        return if (allowed) CheckOutcome.PASS to "allowed" else CheckOutcome.WARN to "not allowed"
    }

    /** The periodic class sync's state as WorkManager holds it. Not being armed is a fact, not a fault. */
    internal suspend fun backgroundSync(): Answer = withContext(Dispatchers.IO) {
        val states = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK_NAME)
            .get()
            .map { info: WorkInfo -> info.state }
        when {
            states.isEmpty() -> CheckOutcome.SKIP to "not armed"
            states.any { !it.isFinished } -> CheckOutcome.PASS to states.joinToString(", ")
            else -> CheckOutcome.WARN to states.joinToString(", ")
        }
    }

    /**
     * Widgets placed on the home screen. By the receiver's name, because this
     * module must not depend on `:widget` — the same reason the redraw is a
     * broadcast.
     */
    internal fun widgets(): Answer {
        val receiver = ComponentName(context.packageName, WidgetReceiver)
        val placed = AppWidgetManager.getInstance(context).getAppWidgetIds(receiver).size
        return (if (placed > 0) CheckOutcome.PASS else CheckOutcome.SKIP) to "$placed placed"
    }

    internal fun buildLine(build: BuildFacts): String =
        "${build.version} (${build.commit.ifBlank { "no commit" }}) · ${build.buildType} · " +
            "API ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}"

    private companion object {
        const val ProbeBytes = 32
        const val WidgetReceiver = "com.lumenpearson.lessons.widget.LessonsWidgetReceiver"
        val HttpOk = 200..299

        val Transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
        )
    }
}
