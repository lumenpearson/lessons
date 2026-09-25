package com.lumenpearson.lessons.core.data.catalog

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The region catalog the server's generator writes, as the phone reads it.
 *
 * `server/app/catalog/data/regions.json` is bundled as an asset exactly as it
 * is committed (`core/data/build.gradle.kts`): generated from the survey by
 * `server/scripts/region_catalog.py`, never copied and never edited by hand. It
 * is the only place the phone learns which diaries exist, which region runs
 * which, and — through [CatalogNetSchool.origin] and [CatalogPlatform.origin] —
 * every host it will ever send a password to. `UpstreamDirectory` draws its
 * allow-list from here and nowhere else.
 *
 * The model is a plain reading of the file, field for field, so that what reads
 * it next (the region search, the provider picker) sees the generator's answers
 * — `recommended`, `action`, `role` — rather than a second derivation of them.
 * Unknown keys are ignored, so a newer generator does not break an older app.
 *
 * Region and platform names live here and not in string resources, in both
 * languages ([CatalogRegion.nameRu], [CatalogRegion.nameEn]); the generator's
 * test holds the English twin the way `ResourceTranslationTest` holds a
 * resource's.
 */
@Serializable
data class RegionCatalog(
    @SerialName("schema") val schema: Int = 0,
    @SerialName("survey") val survey: CatalogSurvey = CatalogSurvey(),
    @SerialName("regions") val regions: List<CatalogRegion> = emptyList(),
    @SerialName("platforms") val platforms: Map<String, CatalogPlatform> = emptyMap(),
    @SerialName("search") val search: CatalogSearch = CatalogSearch(),
) {

    /** The region stored under [key], or `null` for one the catalog does not know. */
    fun region(key: String?): CatalogRegion? = key?.let { wanted -> regions.firstOrNull { it.key == wanted } }

    companion object {
        /** The asset's name: the file sits at the asset root, beside the guide's. */
        const val ASSET = "regions.json"

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        /** Reads a catalog from its JSON text. Throws on a file that is not one. */
        fun parse(text: String): RegionCatalog = json.decodeFromString(serializer(), text)

        /**
         * Reads the bundled catalog. A few hundred kilobytes of JSON: call it off
         * the main thread, once, and keep the answer.
         */
        fun load(context: Context): RegionCatalog =
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
    }
}

/** When the survey behind the catalog was taken, for a screen that says how fresh it is. */
@Serializable
data class CatalogSurvey(
    @SerialName("month") val month: String? = null,
    @SerialName("school_year") val schoolYear: String? = null,
)

/**
 * One of the 89 regions.
 *
 * @property key the catalog key, which is also the server's allow-list key for a
 *   «Сетевой город» region and what the directory endpoint answers with.
 * @property code the two-digit subject code.
 * @property zone the region's administrative centre's zone.
 * @property order the constitution's order (Art. 65), which is the list's.
 */
@Serializable
data class CatalogRegion(
    @SerialName("key") val key: String,
    @SerialName("code") val code: String = "",
    @SerialName("iso") val iso: String? = null,
    @SerialName("name_ru") val nameRu: String = "",
    @SerialName("name_en") val nameEn: String = "",
    @SerialName("aliases") val aliases: List<String> = emptyList(),
    @SerialName("cities") val cities: List<String> = emptyList(),
    @SerialName("dadata_names") val dadataNames: List<String> = emptyList(),
    @SerialName("zone") val zone: String = "",
    @SerialName("order") val order: Int = 0,
    @SerialName("mandatory") val mandatory: Boolean = false,
    @SerialName("tor") val tor: String? = null,
    @SerialName("recommended") val recommended: CatalogRecommendation? = null,
    @SerialName("systems") val systems: List<CatalogSystem> = emptyList(),
)

/**
 * The generator's recommendation for a region, read and never re-derived.
 *
 * @property system an index into [CatalogRegion.systems].
 * @property reason one of the generator's reason codes — `primary`,
 *   `tor_primary`, `front_end`, `moving_to`, `unreachable`, `unsupported`,
 *   `no_diary` — which the screens key their «почему» text by.
 * @property modifiers `unverified`, `esia_only`.
 */
@Serializable
data class CatalogRecommendation(
    @SerialName("platform") val platform: String? = null,
    @SerialName("system") val system: Int? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("modifiers") val modifiers: List<String> = emptyList(),
    @SerialName("confidence") val confidence: String? = null,
)

/**
 * One diary system a region runs.
 *
 * @property action `signin` (the app's own form can sign in here), `handoff`
 *   (open [handoffUrl] in the browser and nothing comes back) or `none`.
 * @property role `primary`, `secondary`, `legacy`, `migrating_to`, `unclear`.
 * @property netschool set only on a «Сетевой город» system this server's
 *   allow-list knows; the one source of such a host.
 */
@Serializable
data class CatalogSystem(
    @SerialName("platform") val platform: String,
    @SerialName("role") val role: String? = null,
    @SerialName("action") val action: String = "none",
    @SerialName("confidence") val confidence: String? = null,
    @SerialName("front_end") val frontEnd: Boolean = false,
    @SerialName("excluded") val excluded: String? = null,
    @SerialName("hosts") val hosts: List<String> = emptyList(),
    @SerialName("web_url") val webUrl: String? = null,
    @SerialName("handoff_url") val handoffUrl: String? = null,
    @SerialName("netschool") val netschool: CatalogNetSchool? = null,
)

/**
 * A «Сетевой город» row of the server's allow-list, as the generator copied it
 * from `providers/netschool/regions.py`.
 *
 * @property password `false` where the region lets people in through Госуслуги
 *   only; the phone sends such a region nothing.
 */
@Serializable
data class CatalogNetSchool(
    @SerialName("region") val region: String,
    @SerialName("origin") val origin: String,
    @SerialName("password") val password: Boolean = false,
    @SerialName("verified") val verified: Boolean = false,
    @SerialName("zone") val zone: String = "",
)

/**
 * One diary platform, whichever regions run it.
 *
 * @property provider `petersburg` or `netschool` where this app can talk to the
 *   platform at all; `null` otherwise.
 * @property origin set only where the platform is one server (Petersburg).
 */
@Serializable
data class CatalogPlatform(
    @SerialName("name_ru") val nameRu: String = "",
    @SerialName("name_en") val nameEn: String = "",
    @SerialName("kind") val kind: String? = null,
    @SerialName("origin") val origin: String? = null,
    @SerialName("provider") val provider: String? = null,
    @SerialName("signin") val signin: String? = null,
    @SerialName("page") val page: String? = null,
    @SerialName("handoff") val handoff: String? = null,
)

/**
 * The words the region search folds, drops and transliterates — carried in the
 * catalog so the phone's search and the server's directory mean one thing by
 * «too common to look up».
 */
@Serializable
data class CatalogSearch(
    @SerialName("cyrillic_layout") val cyrillicLayout: String = "",
    @SerialName("latin_layout") val latinLayout: String = "",
    @SerialName("fold") val fold: Map<String, String> = emptyMap(),
    @SerialName("school_words") val schoolWords: List<String> = emptyList(),
    @SerialName("stop_words") val stopWords: List<String> = emptyList(),
    @SerialName("translit") val translit: List<List<String>> = emptyList(),
    @SerialName("synonyms") val synonyms: Map<String, List<String>> = emptyMap(),
)
