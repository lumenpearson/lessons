package com.lumenpearson.lessons.ui.diary

import androidx.compose.runtime.Immutable
import com.lumenpearson.lessons.core.data.catalog.CatalogRegion
import com.lumenpearson.lessons.core.data.catalog.CatalogSystem
import com.lumenpearson.lessons.core.data.catalog.RegionCatalog
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.net.URI

/**
 * What the screens say about a diary target, read out of the bundled catalog:
 * the region's name, the system's name, and the host a password would go to.
 *
 * A plain `:app` value rather than the catalog's own types, which live in
 * `:core:data` and are deliberately not promised stable to the Compose compiler
 * (`compose-stability.conf`): a screen holding a `CatalogRegion` would recompose
 * on every frame of a state it never changed.
 *
 * Both languages are carried, because the catalog is where region names live
 * (not string resources — K16) and which one to show is the composition's
 * question, not the view model's.
 *
 * @property host where the password goes — the origin the sign-in is allowed
 *   to send to, from the same allow-list `OriginGuard` enforces. `null` when
 *   the catalog cannot place the target, and then the form names no host
 *   rather than guessing one.
 */
@Immutable
data class DiaryPlace(
    val regionRu: String?,
    val regionEn: String?,
    val systemRu: String?,
    val systemEn: String?,
    val host: String?,
)

/**
 * [target] as the catalog knows it.
 *
 * The host is read from the same two fields the sign-in's allow-list is built
 * from (`CatalogUpstreamDirectory`: Petersburg's platform origin, a region's
 * «Сетевой город» row) and refused by the same rule — an origin that is not
 * plain `https` with nothing after the authority names no host here, exactly
 * as it is dropped there. So the form never names a host the password could
 * not be sent to. The directory's own type is not reused because its origins
 * are OkHttp's, which `:core:data` keeps to itself.
 */
fun diaryPlaceOf(catalog: RegionCatalog, target: DiaryTarget): DiaryPlace =
    when (target.provider) {
        DiaryProviderKey.PETERSBURG -> {
            val platform = catalog.platforms.values.firstOrNull { it.provider == target.provider.wire }
            val region = catalog.regions.firstOrNull { region ->
                region.systems.any { it.platform == PETERSBURG_PLATFORM && it.action == ACTION_SIGN_IN }
            }
            DiaryPlace(
                regionRu = region?.nameRu,
                regionEn = region?.nameEn,
                systemRu = platform?.nameRu,
                systemEn = platform?.nameEn,
                host = platform?.origin?.let(::httpsHostOf),
            )
        }
        DiaryProviderKey.NETSCHOOL -> {
            val region = catalog.regions.firstOrNull { region ->
                region.systems.any { it.netschool?.region == target.region }
            }
            // The first row for the key, as the directory keeps it.
            val row = catalog.regions.asSequence()
                .flatMap { it.systems.asSequence() }
                .mapNotNull { it.netschool }
                .firstOrNull { it.region == target.region }
            val platform = catalog.platforms[NETSCHOOL_PLATFORM]
            DiaryPlace(
                regionRu = region?.nameRu,
                regionEn = region?.nameEn,
                systemRu = platform?.nameRu,
                systemEn = platform?.nameEn,
                host = row?.origin?.let(::httpsHostOf),
            )
        }
    }

/**
 * The host (and port, when it is not the default) of an origin the allow-list
 * would keep; `null` for one it would drop.
 */
internal fun httpsHostOf(origin: String): String? {
    val uri = runCatching { URI(origin) }.getOrNull() ?: return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val bare = uri.scheme.equals("https", ignoreCase = true) &&
        uri.rawUserInfo == null &&
        uri.rawPath.isNullOrEmpty() &&
        uri.rawQuery == null &&
        uri.rawFragment == null
    if (!bare) return null
    return if (uri.port == -1 || uri.port == HttpsPort) host else "$host:${uri.port}"
}

private const val HttpsPort = 443

/**
 * What the app can do about a region's diary, read from the generator's own
 * answers (`recommended`, `action`) and never re-derived (K18).
 */
sealed interface DiaryRegionChoice {

    /** Petersburg: the account decides the school, so the next step is the form. */
    data object Petersburg : DiaryRegionChoice

    /** A «Сетевой город» region that takes a password: a school has to be picked. */
    data class NetSchool(val regionKey: String, val zone: String) : DiaryRegionChoice

    /**
     * The region's diary lets nobody in with a password here — Госуслуги, or a
     * system the app does not speak. [url] is where the browser can go, or
     * `null` when there is nowhere.
     */
    data class Elsewhere(val url: String?, val gosuslugi: Boolean) : DiaryRegionChoice
}

/**
 * The choice for [region]: its recommended system if that is one the app signs
 * in to, else the first system it can sign in to, else where to send somebody.
 *
 * A signable system that is not the recommended one still wins over a handoff
 * — the recommendation is advice about the region, and a family whose school
 * is on the second system is better served by a form than by a website.
 */
fun diaryChoiceOf(region: CatalogRegion): DiaryRegionChoice {
    val recommended = region.recommended?.system?.let(region.systems::getOrNull)
    val signable = listOfNotNull(recommended) + region.systems
    signable.firstOrNull { it.action == ACTION_SIGN_IN }?.let { system ->
        signInChoice(region, system)?.let { return it }
    }
    val handoff = listOfNotNull(recommended).plus(region.systems)
        .firstOrNull { it.action == ACTION_HANDOFF }
    val url = (handoff ?: recommended)?.let { it.handoffUrl ?: it.webUrl }
        ?.takeIf { it.startsWith("https://") }
    return DiaryRegionChoice.Elsewhere(
        url = url,
        gosuslugi = region.recommended?.modifiers?.contains(MODIFIER_ESIA_ONLY) == true ||
            handoff?.netschool?.password == false,
    )
}

private fun signInChoice(region: CatalogRegion, system: CatalogSystem): DiaryRegionChoice? =
    when (system.platform) {
        PETERSBURG_PLATFORM -> DiaryRegionChoice.Petersburg
        NETSCHOOL_PLATFORM -> system.netschool
            ?.takeIf { it.password }
            ?.let { DiaryRegionChoice.NetSchool(it.region, it.zone.ifBlank { region.zone }) }
        else -> null
    }

/** The catalog's platform key for Petersburg's diary. */
private const val PETERSBURG_PLATFORM = "petersburg"

/** The catalog's platform key for «Сетевой город». */
private const val NETSCHOOL_PLATFORM = "netschool"

/** A system the app's own form signs in to. */
private const val ACTION_SIGN_IN = "signin"

/** A system the browser is sent to, and nothing comes back. */
private const val ACTION_HANDOFF = "handoff"

/** The generator's modifier for a region whose diary takes Госуслуги only. */
private const val MODIFIER_ESIA_ONLY = "esia_only"
