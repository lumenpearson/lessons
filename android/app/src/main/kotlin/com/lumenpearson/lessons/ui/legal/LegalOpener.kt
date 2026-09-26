package com.lumenpearson.lessons.ui.legal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.intl.Locale
import com.lumenpearson.lessons.BuildConfig
import com.lumenpearson.lessons.core.data.legal.LegalDocument
import com.lumenpearson.lessons.core.data.legal.legalUrl
import com.lumenpearson.lessons.ui.common.openInBrowser

/** Where a tap on a legal link ends up. */
internal sealed interface LegalTarget {
    /** The current edition, in the reader's browser. */
    data class Web(val url: String) : LegalTarget

    /** The edition this APK was built with, in a sheet. */
    data object Bundled : LegalTarget
}

/**
 * The browser when there is an address and a network that reaches past its own
 * router; the bundled copy otherwise.
 *
 * "Validated" rather than "connected" because the case worth catching is the
 * school's Wi-Fi with a captive portal: connected, and every page it opens is
 * the portal's sign-in, so the reader would be accepting a document they were
 * never shown. Whether a browser exists is not decided here — it is only known
 * by trying, and a failed try falls back to the same sheet.
 */
internal fun legalTarget(url: String?, online: Boolean): LegalTarget =
    if (url != null && online) LegalTarget.Web(url) else LegalTarget.Bundled

/**
 * Opens the terms or the privacy policy, whichever way this phone can.
 *
 * Built by [rememberLegalOpener], which also hosts the sheet it falls back to.
 * One opener for the first screen's line and for «О приложении», so the two
 * cannot disagree about which copy a reader is shown.
 */
@Stable
class LegalOpener internal constructor(
    private val context: Context,
    private val baseUrl: String,
    private val language: String,
    private val showBundled: (LegalDocument) -> Unit,
) {
    fun open(document: LegalDocument) {
        when (val target = legalTarget(legalUrl(baseUrl, document, language), isOnline(context))) {
            is LegalTarget.Web -> if (!openInBrowser(context, target.url)) showBundled(document)
            LegalTarget.Bundled -> showBundled(document)
        }
    }
}

/**
 * A [LegalOpener], and the sheet it shows when the browser is not an answer.
 *
 * The sheet is composed here rather than by each caller so that a caller needs
 * nothing but this one call: the first screen's line and the about card each
 * get the whole behaviour, fallback included, and neither can forget it. Which
 * document is open is saved state, so a rotation keeps the sheet up.
 *
 * @param baseUrl the build's legal folder; a parameter only so a test can hand
 *   in an address instead of the one this build was configured with.
 */
@Composable
fun rememberLegalOpener(baseUrl: String = BuildConfig.LEGAL_BASE_URL): LegalOpener {
    val context = LocalContext.current
    val language = Locale.current.language
    val shown = rememberSaveable { mutableStateOf<LegalDocument?>(null) }
    val opener = remember(context, baseUrl, language) {
        LegalOpener(context, baseUrl, language) { document -> shown.value = document }
    }
    shown.value?.let { document ->
        LegalDocumentSheet(
            document = document,
            url = legalUrl(baseUrl, document, language),
            onDismiss = { shown.value = null },
        )
    }
    return opener
}

/** Whether the active network has been checked to reach the internet. */
internal fun isOnline(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return runCatching {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)
}

