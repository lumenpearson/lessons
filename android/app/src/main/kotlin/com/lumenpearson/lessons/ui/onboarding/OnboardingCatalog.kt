package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.runtime.Immutable
import com.lumenpearson.lessons.core.data.catalog.CatalogRegion
import com.lumenpearson.lessons.core.data.catalog.CatalogSystem
import com.lumenpearson.lessons.core.data.catalog.MatchVia
import com.lumenpearson.lessons.core.data.catalog.RegionCatalog
import com.lumenpearson.lessons.core.data.catalog.RegionMatch
import com.lumenpearson.lessons.core.data.repository.DiaryBinding
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.ui.diary.httpsHostOf
import java.util.Locale

/*
 * The region, school and provider steps, read out of the bundled catalog into
 * `:app` values.
 *
 * Plain values rather than the catalog's own types, which live in `:core:data`
 * and are deliberately not promised stable to the Compose compiler: a step
 * holding a `CatalogRegion` would recompose on every frame of a state it never
 * changed. And read, never re-derived (K18): what is recommended, what can be
 * signed into and in what role are the generator's answers — these functions
 * only filter what to show and name it.
 */

/** What the app can do about a region's diary, in one word, for a row of the region list. */
enum class OfferKind { SIGN_IN, GOSUSLUGI, SITE, UNSUPPORTED, PAPER }

/** [kind], and the name of the system it is about. */
@Immutable
data class RegionOffer(val kind: OfferKind, val systemRu: String?, val systemEn: String?)

/**
 * One region as the region step draws it.
 *
 * @property matched what the query found it by, when that was not its name —
 *   a city, an alias or the subject code — so the row can say why it is here.
 */
@Immutable
data class RegionRowUi(
    val key: String,
    val nameRu: String,
    val nameEn: String,
    val code: String,
    val via: MatchVia,
    val matched: String?,
    val offer: RegionOffer,
)

/** How a system on the provider step is reached. */
enum class ProviderRowKind {
    /** The app's own form signs in. */
    SIGN_IN,

    /** «Сетевой город» without a school picked: its sign-in needs one. */
    NEEDS_SCHOOL,

    /** Госуслуги, in the browser; nothing comes back. */
    HANDOFF,

    /** A diary the app cannot read, whose site the browser can open. */
    SITE,

    /** A diary the app cannot read, with nowhere to send anybody. */
    UNSUPPORTED,

    /** No electronic diary at all. */
    PAPER,
}

/**
 * One system on the provider step.
 *
 * @property index the system's index in the region's catalog row.
 * @property role the catalog's role: `primary`, `secondary`, `migrating_to`.
 * @property url where [ProviderRowKind.HANDOFF] and [ProviderRowKind.SITE] send
 *   the browser — always https, never an app named by package.
 * @property gosuslugi the handoff lands on a Госуслуги sign-in.
 * @property host where a password would go, for a signable row.
 */
@Immutable
data class ProviderRowUi(
    val index: Int,
    val platform: String,
    val nameRu: String,
    val nameEn: String,
    val role: String?,
    val kind: ProviderRowKind,
    val recommended: Boolean,
    val url: String?,
    val gosuslugi: Boolean,
    val host: String?,
)

/** The generator's reason codes, exactly (K18); anything else reads as [UNSUPPORTED]. */
enum class WhyReason(val code: String) {
    PRIMARY("primary"),
    TOR_PRIMARY("tor_primary"),
    FRONT_END("front_end"),
    MOVING_TO("moving_to"),
    UNREACHABLE("unreachable"),
    UNSUPPORTED("unsupported"),
    NO_DIARY("no_diary"),
    ;

    companion object {
        fun of(code: String?): WhyReason = entries.firstOrNull { it.code == code } ?: UNSUPPORTED
    }
}

/** The generator's modifiers on a recommendation. */
enum class WhyModifier(val code: String) {
    UNVERIFIED("unverified"),
    ESIA_ONLY("esia_only"),
}

/**
 * Everything «Почему?» says about the recommended row, as data; the words are
 * `WhyText`'s.
 *
 * @property mainSystemRu the region's own diary where the recommendation is a
 *   front end over it, which is what the front-end sentence names.
 */
@Immutable
data class WhyUi(
    val reason: WhyReason,
    val modifiers: Set<WhyModifier>,
    val confidence: String?,
    val regionRu: String,
    val regionEn: String,
    val systemRu: String,
    val systemEn: String,
    val mainSystemRu: String?,
    val mainSystemEn: String?,
    val schoolYear: String?,
    val surveyMonth: String?,
    val host: String?,
)

/** The provider step, whole. */
@Immutable
data class ProviderPage(
    val regionKey: String,
    val regionRu: String,
    val regionEn: String,
    val rows: List<ProviderRowUi>,
    val why: WhyUi?,
) {
    /** The row the main button acts for: the recommended one, else the first the app can sign in to. */
    val main: ProviderRowUi?
        get() = rows.firstOrNull { it.recommended && it.kind != ProviderRowKind.UNSUPPORTED && it.kind != ProviderRowKind.PAPER }
            ?: rows.firstOrNull { it.kind == ProviderRowKind.SIGN_IN || it.kind == ProviderRowKind.NEEDS_SCHOOL }
}

/**
 * Whether a region's chapter has a school step and a sign-in: a school step
 * exactly where the signable diary is «Сетевой город» (its sign-in names the
 * school), a sign-in wherever the app's form can go at all.
 */
fun regionPlanOf(region: CatalogRegion): RegionPlan {
    val signable = region.systems.filter(::isSignable)
    return RegionPlan(
        showsSchool = signable.any { it.platform == NETSCHOOL },
        signable = signable.isNotEmpty(),
    )
}

/** What a region row says can be done there: its recommended system, read as the catalog has it. */
fun regionOfferOf(catalog: RegionCatalog, region: CatalogRegion): RegionOffer {
    val recommended = region.recommended?.system?.let(region.systems::getOrNull)
    val signable = region.systems.firstOrNull(::isSignable)
    val shown = signable ?: recommended ?: region.systems.firstOrNull()
    val platform = shown?.platform?.let(catalog.platforms::get)
    val kind = when {
        shown == null -> OfferKind.UNSUPPORTED
        shown.platform == PAPER -> OfferKind.PAPER
        isSignable(shown) -> OfferKind.SIGN_IN
        isGosuslugi(region, shown) -> OfferKind.GOSUSLUGI
        httpsUrl(shown) != null && shown.action == ACTION_HANDOFF -> OfferKind.SITE
        else -> OfferKind.UNSUPPORTED
    }
    return RegionOffer(kind = kind, systemRu = platform?.nameRu, systemEn = platform?.nameEn)
}

/** A search hit as a row; see [RegionRowUi.matched]. */
fun regionRowOf(catalog: RegionCatalog, match: RegionMatch): RegionRowUi = regionRowOf(
    catalog = catalog,
    region = match.region,
    via = match.via,
    matched = when (match.via) {
        MatchVia.CITY -> original(catalog, match.region.cities, match.matched)
        MatchVia.ALIAS -> original(catalog, match.region.aliases, match.matched)
        MatchVia.CODE -> match.region.code
        else -> null
    },
)

fun regionRowOf(catalog: RegionCatalog, region: CatalogRegion, via: MatchVia = MatchVia.LIST, matched: String? = null) =
    RegionRowUi(
        key = region.key,
        nameRu = region.nameRu,
        nameEn = region.nameEn,
        code = region.code,
        via = via,
        matched = matched,
        offer = regionOfferOf(catalog, region),
    )

/**
 * The search hands back the words it matched normalised — folded (lower case,
 * ё as е, a dash a space) and without the region-type words, so «Читинская
 * область» comes back as «читинская»; the row names the city or the alias as
 * the catalog spells it, by normalising each candidate the same way.
 */
private fun original(catalog: RegionCatalog, candidates: List<String>, matched: String): String {
    val words = matched.split(' ').filter { it.isNotEmpty() }
    val stop = catalog.search.stopWords.flatMap { tokens(catalog, it) }.toSet()
    return candidates.firstOrNull { candidate -> tokens(catalog, candidate).filter { it !in stop } == words } ?: matched
}

/**
 * `RegionSearch.normalise`'s tokens, from the catalog's own table (no Cyrillic
 * is written in Kotlin): lower case, folded, anything but a letter or a digit
 * a space. That function is internal to `:core:data`, which is why this is a
 * copy of its loop rather than a call.
 */
private fun tokens(catalog: RegionCatalog, text: String): List<String> {
    val table = catalog.search.fold
    val folded = buildString {
        for (char in text.lowercase(Locale.ROOT)) {
            for (c in table[char.toString()] ?: char.toString()) append(if (c.isLetterOrDigit()) c else ' ')
        }
    }
    return folded.split(' ').filter { it.isNotEmpty() }
}

/**
 * The provider step for [region]: the systems a family could mean, in catalog
 * order, with the recommended one marked.
 *
 * Left out: a legacy or unclear system (the survey's history, not a choice), a
 * portal or an admissions site (not a diary), and ТОР where the catalog says
 * the region is not on it. The recommended system is always kept, whatever its
 * role.
 */
fun providerPageOf(catalog: RegionCatalog, region: CatalogRegion, school: PickedSchool?): ProviderPage {
    val recommendedIndex = region.recommended?.system
    val rows = region.systems.mapIndexedNotNull { index, system ->
        val recommended = index == recommendedIndex
        if (!recommended && !shown(catalog, region, system)) return@mapIndexedNotNull null
        val platform = catalog.platforms[system.platform]
        ProviderRowUi(
            index = index,
            platform = system.platform,
            nameRu = platform?.nameRu ?: system.platform,
            nameEn = platform?.nameEn ?: system.platform,
            role = system.role,
            kind = kindOf(region, system, school),
            recommended = recommended,
            url = httpsUrl(system),
            gosuslugi = isGosuslugi(region, system),
            host = hostOf(catalog, system),
        )
    }
    val recommended = recommendedIndex?.let(region.systems::getOrNull)
    val why = recommended?.let { system ->
        val platform = catalog.platforms[system.platform]
        val main = region.systems.firstOrNull { it.role == ROLE_PRIMARY && it !== system }
            ?.platform?.let(catalog.platforms::get)
        WhyUi(
            reason = WhyReason.of(region.recommended?.reason),
            modifiers = region.recommended?.modifiers.orEmpty()
                .mapNotNull { code -> WhyModifier.entries.firstOrNull { it.code == code } }
                .toSet(),
            confidence = region.recommended?.confidence,
            regionRu = region.nameRu,
            regionEn = region.nameEn,
            systemRu = platform?.nameRu ?: system.platform,
            systemEn = platform?.nameEn ?: system.platform,
            mainSystemRu = main?.nameRu,
            mainSystemEn = main?.nameEn,
            schoolYear = catalog.survey.schoolYear,
            surveyMonth = catalog.survey.month,
            host = if (isSignable(system)) hostOf(catalog, system) else null,
        )
    }
    return ProviderPage(
        regionKey = region.key,
        regionRu = region.nameRu,
        regionEn = region.nameEn,
        rows = rows,
        why = why,
    )
}

private fun shown(catalog: RegionCatalog, region: CatalogRegion, system: CatalogSystem): Boolean {
    if (system.role !in ShownRoles) return false
    val kind = catalog.platforms[system.platform]?.kind
    if (kind == KIND_PORTAL || kind == KIND_ADMISSIONS) return false
    if (system.platform == TOR && region.tor == TOR_ABSENT) return false
    return true
}

private fun kindOf(region: CatalogRegion, system: CatalogSystem, school: PickedSchool?): ProviderRowKind = when {
    system.platform == PAPER -> ProviderRowKind.PAPER
    isSignable(system) && system.platform == NETSCHOOL && school == null -> ProviderRowKind.NEEDS_SCHOOL
    isSignable(system) -> ProviderRowKind.SIGN_IN
    system.action == ACTION_HANDOFF && httpsUrl(system) != null ->
        if (isGosuslugi(region, system)) ProviderRowKind.HANDOFF else ProviderRowKind.SITE
    httpsUrl(system) != null -> ProviderRowKind.SITE
    else -> ProviderRowKind.UNSUPPORTED
}

/**
 * The app's own form signs in here: the generator said `signin`, and the
 * system is one of the two this app speaks — Petersburg, or «Сетевой город» in
 * a region whose allow-list row takes a password.
 */
internal fun isSignable(system: CatalogSystem): Boolean = system.action == ACTION_SIGN_IN && when (system.platform) {
    PETERSBURG -> true
    NETSCHOOL -> system.netschool?.password == true
    else -> false
}

private fun isGosuslugi(region: CatalogRegion, system: CatalogSystem): Boolean =
    system.platform == TOR ||
        system.netschool?.password == false ||
        (region.recommended?.system?.let(region.systems::getOrNull) === system &&
            region.recommended?.modifiers?.contains(WhyModifier.ESIA_ONLY.code) == true)

/** The handoff target, else the site, and only ever https (K19). */
private fun httpsUrl(system: CatalogSystem): String? =
    (system.handoffUrl ?: system.webUrl)?.takeIf { it.startsWith("https://") }

/** Where a password for [system] would go, by the allow-list's own rule; `null` if nowhere. */
private fun hostOf(catalog: RegionCatalog, system: CatalogSystem): String? = when (system.platform) {
    PETERSBURG -> catalog.platforms[PETERSBURG]?.origin?.let(::httpsHostOf)
    NETSCHOOL -> system.netschool?.origin?.let(::httpsHostOf)
    else -> null
}

/**
 * The sign-in target for [system] of [region], with the login still blank:
 * the form is where it is typed. `null` for a system the form cannot reach, or
 * «Сетевой город» with no school.
 */
fun targetFor(region: CatalogRegion, system: CatalogSystem, school: PickedSchool?): DiaryTarget? = when {
    !isSignable(system) -> null
    system.platform == PETERSBURG -> DiaryTarget.petersburg(login = "")
    school == null -> null
    else -> system.netschool?.let { row ->
        DiaryTarget.netschool(
            region = row.region,
            schoolId = school.id,
            schoolName = school.name,
            login = "",
            zone = row.zone.ifBlank { region.zone },
        )
    }
}

/**
 * A joined class's diary as a sign-in target, when this build can sign in to
 * it: Petersburg, or «Сетевой город» in a password region with a school. The
 * zone is the catalog's, because the binding carries none.
 */
fun classTargetOf(catalog: RegionCatalog, binding: DiaryBinding?): DiaryTarget? {
    val target = binding?.targetFor(login = "") ?: return null
    return when (target.provider) {
        DiaryProviderKey.PETERSBURG -> target
        DiaryProviderKey.NETSCHOOL -> {
            val row = catalog.regions.asSequence()
                .flatMap { it.systems.asSequence() }
                .mapNotNull { it.netschool }
                .firstOrNull { it.region == target.region }
            if (row == null || !row.password) null else target.copy(zone = row.zone.ifBlank { target.zone })
        }
    }
}

/**
 * The school search's first query, out of the name typed on the region step:
 * the number when there is one — «ГБОУ лицей № 239» → «239» — else the longest
 * word that is not a word every school has. The diary's own list rarely spells
 * a school the way the company register does, and a number matches both.
 */
fun schoolQueryFromHint(hint: String?, schoolWords: Collection<String>): String {
    val text = hint?.trim().orEmpty()
    if (text.isEmpty()) return ""
    Regex("""\d+""").find(text)?.let { return it.value }
    val common = schoolWords.map { it.lowercase() }.toSet()
    return text.split(Regex("""[\s,.«»"()]+"""))
        .filter { it.isNotBlank() && it.lowercase() !in common }
        .maxByOrNull { it.length }
        .orEmpty()
}

private const val PETERSBURG = "petersburg"
private const val NETSCHOOL = "netschool"
private const val TOR = "tor-myschool"
private const val PAPER = "paper"
private const val ACTION_SIGN_IN = "signin"
private const val ACTION_HANDOFF = "handoff"
private const val ROLE_PRIMARY = "primary"
private const val TOR_ABSENT = "absent"
private const val KIND_PORTAL = "portal"
private const val KIND_ADMISSIONS = "admissions"
private val ShownRoles = setOf("primary", "secondary", "migrating_to")
