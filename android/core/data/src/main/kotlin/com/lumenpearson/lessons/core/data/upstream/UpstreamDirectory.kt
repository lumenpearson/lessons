package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.catalog.RegionCatalog
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A «Сетевой город» region the phone may talk to.
 *
 * @property origin scheme, host and port only — Sakhalin's `:11111` included.
 * @property password whether a login and password get in at all; `false` where
 *   the region takes Госуслуги only, and then nothing is sent.
 * @property handoffUrl where to send somebody the app cannot sign in for: the
 *   catalog's `handoff_url`, or the system's own site when it has none.
 */
data class NetSchoolRegion(
    val key: String,
    val origin: HttpUrl,
    val password: Boolean,
    val zone: String,
    val handoffUrl: String?,
)

/**
 * The only source of a host the phone sends a diary request to.
 *
 * Nothing typed and nothing received ever becomes a host: a region is picked by
 * its catalog key, the key finds a row here, and the row's origin is where the
 * request goes. The one piece of user input that reaches a diary at all — a
 * school search — travels as a query parameter.
 */
interface UpstreamDirectory {

    /** Petersburg's one server, or `null` if the catalog does not carry it. */
    val petersburg: HttpUrl?

    /** The region under [regionKey], or `null` when it is not allow-listed. */
    fun netschool(regionKey: String?): NetSchoolRegion?

    /** Every origin above, for the client's guard. */
    fun allowedOrigins(): Set<UpstreamOrigin>
}

/**
 * [UpstreamDirectory] over the bundled catalog.
 *
 * An origin that is not `https`, or that carries a path, a query, a fragment or
 * credentials, is dropped rather than used: an `http` origin would send the
 * salted password and the session in clear text, and anything after the
 * authority would be a way to point a request somewhere the origin does not
 * say. The generator refuses the same things; this is the second lock.
 */
class CatalogUpstreamDirectory(catalog: RegionCatalog) : UpstreamDirectory {

    override val petersburg: HttpUrl? = catalog.platforms
        .values
        .firstOrNull { it.provider == PETERSBURG_PROVIDER }
        ?.origin
        ?.let(::originOrNull)

    private val regions: Map<String, NetSchoolRegion> = buildMap {
        for (region in catalog.regions) {
            for (system in region.systems) {
                val row = system.netschool ?: continue
                val origin = originOrNull(row.origin) ?: continue
                if (row.region in this) continue
                put(
                    row.region,
                    NetSchoolRegion(
                        key = row.region,
                        origin = origin,
                        password = row.password,
                        zone = row.zone.ifBlank { region.zone },
                        // Opened in the browser, never requested by the app,
                        // so a path is fine; plain http is not.
                        handoffUrl = (system.handoffUrl ?: system.webUrl)
                            ?.takeIf { it.toHttpUrlOrNull()?.scheme == "https" },
                    ),
                )
            }
        }
    }

    private val origins: Set<UpstreamOrigin> = buildSet {
        petersburg?.let { add(UpstreamOrigin.of(it)) }
        regions.values.forEach { add(UpstreamOrigin.of(it.origin)) }
    }

    override fun netschool(regionKey: String?): NetSchoolRegion? = regionKey?.let(regions::get)

    override fun allowedOrigins(): Set<UpstreamOrigin> = origins

    companion object {
        private const val PETERSBURG_PROVIDER = "petersburg"

        /**
         * [raw] as a bare `https` origin, or `null`. Public to this module's
         * tests because the rule is the allow-list's whole defence.
         */
        internal fun originOrNull(raw: String): HttpUrl? {
            val url = raw.toHttpUrlOrNull() ?: return null
            val bare = url.scheme == "https" &&
                url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.encodedPath == "/" &&
                url.query == null &&
                url.fragment == null &&
                // `toHttpUrl` normalises a trailing slash in, so the raw text is
                // what says whether anything followed the authority.
                !raw.removePrefix("https://").contains('/')
            return url.takeIf { bare }
        }
    }
}
