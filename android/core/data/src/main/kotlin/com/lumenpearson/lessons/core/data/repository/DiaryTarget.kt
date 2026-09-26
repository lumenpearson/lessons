package com.lumenpearson.lessons.core.data.repository

import java.time.DateTimeException
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which diary a phone signs in to. The server's `provider` wire names.
 *
 * Named «key» because `DiaryProvider` is the server's word for the class that
 * *talks* to a diary, and a phone that only picks one should not borrow it.
 */
enum class DiaryProviderKey(val wire: String) {
    PETERSBURG("petersburg"),
    NETSCHOOL("netschool"),
    ;

    companion object {
        /** `null` for a name this build does not know, so it reads as no diary at all. */
        fun fromWire(raw: String?): DiaryProviderKey? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * Where this phone signs in to a diary — everything but the secret.
 *
 * Kept apart from our bearer because the two die at different times: a bare
 * `401` drops the bearer, and without this the app would forget which diary,
 * which region and which school it had been in, and a family that had only
 * ever used the diary would be dropped back at the start instead of being asked
 * for the password of the same account. It is cleared by signing out and by
 * nothing else.
 *
 * @property region the catalog's key for a «Сетевой город» region; `null` for
 *   Petersburg, which is one city's server.
 * @property schoolId the upstream's own school id («scid»); `null` for Petersburg.
 * @property zone the zone the diary cuts its days at, as the server told us at
 *   registration (`services/diary.zone_for`), so the phone's «today» and the
 *   server's are one rule rather than two tables.
 */
data class DiaryTarget(
    val provider: DiaryProviderKey,
    val region: String?,
    val schoolId: Long?,
    val schoolName: String?,
    val login: String,
    val zone: String = PETERSBURG_ZONE,
) {

    /**
     * [zone] as a [ZoneId], falling back to Moscow the way the server's
     * `timezones.resolve` does. An older phone's tzdata may not know
     * `Asia/Tomsk` or `Europe/Ulyanovsk`, and a diary that cannot open because
     * of a zone name is worse than one whose midnight is an hour off.
     */
    fun zoneId(): ZoneId = try {
        ZoneId.of(zone)
    } catch (_: DateTimeException) {
        ZoneId.of(PETERSBURG_ZONE)
    }

    /**
     * Whether two targets are the same account, for deciding whether what is
     * cached on the phone still belongs to whoever just signed in. Local only:
     * it never leaves the device, and it is not what the server files
     * corrections under — those belong to the child, and every account whose
     * diary lists that pupil reads the same ones.
     */
    fun accountKey(): String =
        "${provider.wire}|${region.orEmpty()}|${login.trim().lowercase(Locale.ROOT)}"

    companion object {
        /** Petersburg's diary is one city's, and that city keeps Moscow time. */
        const val PETERSBURG_ZONE: String = "Europe/Moscow"

        fun petersburg(login: String): DiaryTarget = DiaryTarget(
            provider = DiaryProviderKey.PETERSBURG,
            region = null,
            schoolId = null,
            schoolName = null,
            login = login,
        )

        fun netschool(
            region: String,
            schoolId: Long,
            schoolName: String?,
            login: String,
            zone: String,
        ): DiaryTarget = DiaryTarget(
            provider = DiaryProviderKey.NETSCHOOL,
            region = region,
            schoolId = schoolId,
            schoolName = schoolName,
            login = login,
            zone = zone,
        )
    }
}

/**
 * The stored shape of a [DiaryTarget]: one JSON value under one key, with a
 * version, rather than six loose keys that a half-finished write could leave
 * disagreeing with each other.
 */
internal object DiaryTargetCodec {

    private const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // The version and the zone are written even when they are the
        // defaults: a stored value that says what it is outlives a change to
        // what the default means.
        encodeDefaults = true
    }

    @Serializable
    private data class Stored(
        @SerialName("v") val version: Int = VERSION,
        @SerialName("provider") val provider: String,
        @SerialName("region") val region: String? = null,
        @SerialName("school_id") val schoolId: Long? = null,
        @SerialName("school") val school: String? = null,
        @SerialName("login") val login: String,
        @SerialName("zone") val zone: String = DiaryTarget.PETERSBURG_ZONE,
    )

    fun encode(target: DiaryTarget): String = json.encodeToString(
        Stored.serializer(),
        Stored(
            provider = target.provider.wire,
            region = target.region,
            schoolId = target.schoolId,
            school = target.schoolName,
            login = target.login,
            zone = target.zone,
        ),
    )

    /**
     * `null` for anything this build cannot stand behind: an unreadable value,
     * a later version, or a provider it does not know — which is what a
     * downgrade after a third diary arrives would look like, and signing that
     * account in to Petersburg instead would be worse than asking.
     */
    fun decode(raw: String?): DiaryTarget? {
        if (raw.isNullOrBlank()) return null
        val stored = runCatching { json.decodeFromString(Stored.serializer(), raw) }.getOrNull()
            ?: return null
        if (stored.version != VERSION) return null
        val provider = DiaryProviderKey.fromWire(stored.provider) ?: return null
        return DiaryTarget(
            provider = provider,
            region = stored.region,
            schoolId = stored.schoolId,
            schoolName = stored.school,
            login = stored.login,
            zone = stored.zone,
        )
    }
}
