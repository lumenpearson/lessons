package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lumenpearson.lessons.core.data.repository.DiaryBinding
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * How the classes this device belongs to are written down.
 *
 * Split out of [LessonsPreferences] so it can be read and exercised without a
 * Context: every decision here is a pure function of the stored keys, and the
 * one that matters most cannot be reached through the public API at all — an
 * install made before the list existed has to keep working, and getting that
 * wrong signs somebody out of a class they are in, or shows four screens of
 * introduction to a user who has been in the app since September. There is no
 * screen that would report either; a test is the only thing that can.
 */

/**
 * One membership as it is written to disk.
 *
 * A storage type of its own rather than `@Serializable` on [Session]: the
 * public type is what the UI reads, and pinning a wire format onto it would
 * mean a rename in the UI layer silently orphaning every stored membership.
 * Every field has a default so a record written by a newer build, or one that
 * lost a key, still decodes instead of taking the whole list with it.
 */
@Serializable
private data class StoredSession(
    val classId: Long = 0L,
    val className: String = "",
    val school: String? = null,
    val token: String = "",
    /**
     * The class's diary binding, as a raw element decoded on its own
     * ([storedBinding]): a binding this build cannot read must cost the
     * binding, never the membership — and a typed field that failed would
     * take the whole record with it. Absent on every record written before
     * bindings, which read as unbound.
     */
    val diary: JsonElement? = null,
)

/** One class's diary binding as it is written down; see [StoredSession.diary]. */
@Serializable
private data class StoredBinding(
    val provider: String = "",
    val region: String? = null,
    val schoolId: Long? = null,
    val schoolName: String? = null,
)

private fun storedBinding(element: JsonElement?): DiaryBinding? {
    if (element == null || element is JsonNull) return null
    val stored = runCatching { membershipJson.decodeFromJsonElement<StoredBinding>(element) }
        .getOrNull()
        ?: return null
    // A provider a later build added reads as no binding rather than as the
    // wrong diary.
    val provider = DiaryProviderKey.fromWire(stored.provider) ?: return null
    return DiaryBinding(provider, stored.region, stored.schoolId, stored.schoolName)
}

private fun DiaryBinding.stored(): JsonElement = membershipJson.encodeToJsonElement(
    StoredBinding.serializer(),
    StoredBinding(provider.wire, region, schoolId, schoolName),
)

/**
 * Tolerant on the way in for the same reason the network's parser is: a list
 * written by a newer build must not be unreadable by an older one, because the
 * price of that is a phone that says it is in no class while holding the tokens
 * for three.
 */
private val membershipJson = Json {
    ignoreUnknownKeys = true
    // An explicit `null` in a non-null field becomes the declared default
    // rather than an exception, the same way the network's parser treats
    // one. Without it the per-record tolerance above only covers a record
    // that is missing a key, not one that carries a null.
    coerceInputValues = true
}

/**
 * Every membership, in the order they were joined.
 *
 * Decoded **one record at a time**, which is the whole point of the list being
 * an array of small objects. Decoding it as a list would make any single
 * damaged record — an explicit `null` where a newer build wrote one, a wrong
 * type, one flipped byte — throw, and the catch around it would report a phone
 * holding three valid tokens as being in no class at all. Worse than showing
 * the join screen: the next write is `memberships() + the new one`, so the
 * first thing the user does to recover overwrites the other two for good.
 *
 * @see MembershipKeys
 */
internal fun Preferences.memberships(): List<Session> {
    val raw = this[MembershipKeys.SESSIONS] ?: return listOfNotNull(legacyMembership())
    val array = runCatching { membershipJson.parseToJsonElement(raw).jsonArray }
        .getOrNull()
        ?: return emptyList()
    return array.mapNotNull { element ->
        val stored = runCatching { membershipJson.decodeFromJsonElement<StoredSession>(element) }
            .getOrNull()
            ?: return@mapNotNull null
        stored.token.takeIf { it.isNotBlank() }?.let { token ->
            Session(
                classId = stored.classId,
                className = stored.className,
                school = stored.school,
                token = token,
                diary = storedBinding(stored.diary),
            )
        }
    }
}

/**
 * The membership being shown.
 *
 * Falls back to the first stored class when the active id names none of them,
 * which is the state a half-finished write or a hand-edited file can leave: the
 * phone is in classes, so it shows one, rather than claiming to be in none.
 */
internal fun Preferences.activeMembership(): Session? {
    val all = memberships()
    val active = this[MembershipKeys.ACTIVE_CLASS_ID]
    return all.firstOrNull { it.classId == active } ?: all.firstOrNull()
}

/**
 * The one membership the app used to hold, in four flat keys.
 *
 * Read only when the list is absent, which is exactly an install made before
 * the list existed. Everything else about that install — its settings, its
 * server address, its diary — is untouched by the move.
 */
internal fun Preferences.legacyMembership(): Session? {
    val token = this[MembershipKeys.TOKEN]?.takeIf { it.isNotBlank() } ?: return null
    return Session(
        classId = this[MembershipKeys.CLASS_ID] ?: 0L,
        className = this[MembershipKeys.CLASS_NAME].orEmpty(),
        school = this[MembershipKeys.SCHOOL],
        token = token,
    )
}

/**
 * Stores a membership and returns the list it belongs to.
 *
 * Re-joining a class already on this phone replaces its token **in place**
 * rather than appending: the join code is how a pupil recovers from a revoked
 * device, and doing that must not leave the same class listed twice, nor move
 * it to the bottom of a list the user has got used to.
 */
internal fun List<Session>.withMembership(joined: Session): List<Session> =
    if (any { it.classId == joined.classId }) {
        map { if (it.classId == joined.classId) joined else it }
    } else {
        this + joined
    }

/**
 * Writes the list and retires the four flat keys in the same transaction.
 *
 * They are removed rather than kept in step: two places holding the same token
 * is two places to forget, and [memberships] only reads them when the list is
 * absent — so the moment the list exists they are dead weight that could only
 * ever disagree with it.
 */
internal fun MutablePreferences.writeMemberships(value: List<Session>) {
    this[MembershipKeys.SESSIONS] = membershipJson.encodeToString(
        value.map { StoredSession(it.classId, it.className, it.school, it.token, it.diary?.stored()) },
    )
    remove(MembershipKeys.TOKEN)
    remove(MembershipKeys.CLASS_ID)
    remove(MembershipKeys.CLASS_NAME)
    remove(MembershipKeys.SCHOOL)
}

/** Forgets every membership, both the list and the keys it replaced. */
internal fun MutablePreferences.clearMemberships() {
    remove(MembershipKeys.SESSIONS)
    remove(MembershipKeys.ACTIVE_CLASS_ID)
    remove(MembershipKeys.TOKEN)
    remove(MembershipKeys.CLASS_ID)
    remove(MembershipKeys.CLASS_NAME)
    remove(MembershipKeys.SCHOOL)
}

/** The keys the rest of this file is about. */
internal object MembershipKeys {

    /** Every membership, as a JSON array of `StoredSession`. */
    val SESSIONS = stringPreferencesKey("session_list")

    /** Which of them is on screen. Matched against the list, never trusted alone. */
    val ACTIVE_CLASS_ID = longPreferencesKey("session_active_class_id")

    // The one membership the app used to hold. Read only when SESSIONS is
    // absent — an install made before the list existed — and removed the first
    // time the list is written. Do not write these again.
    val TOKEN = stringPreferencesKey("session_token")
    val CLASS_ID = longPreferencesKey("session_class_id")
    val CLASS_NAME = stringPreferencesKey("session_class_name")
    val SCHOOL = stringPreferencesKey("session_school")
}
