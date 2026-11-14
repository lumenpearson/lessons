package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import com.lumenpearson.lessons.core.data.repository.ClassActionOwner
import com.lumenpearson.lessons.core.data.repository.ClassRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** A legacy class-id-only role has no proof of which server or bearer earned it. */
internal class OwnedRoles(
    private val store: LessonsPreferences,
    private val vault: TokenVault,
    private val io: CoroutineDispatcher,
) {
    fun read(classId: Long, owner: ClassActionOwner? = null) = store.preferences.map { prefs ->
        val member = prefs.openMemberships(vault).firstOrNull { it.classId == classId }
        val address = prefs.toSettings().baseUrl
        val rememberedToken = prefs[PreferenceKeys.roleTokenKey(classId)]?.let(vault::open)
        val matches = member != null && rememberedToken == member.token &&
            prefs[PreferenceKeys.roleAddressKey(classId)] == address &&
            (owner == null || owner.baseUrl == address && owner.token == member.token)
        if (matches) ClassRole.fromWire(prefs[PreferenceKeys.roleKey(classId)]) else null
    }.distinctUntilChanged().flowOn(io)

    suspend fun remember(classId: Long, role: ClassRole?, owner: ClassActionOwner? = null) {
        store.write { prefs ->
            val member = prefs.memberships().firstOrNull { it.classId == classId }
            val token = member?.token?.let(vault::open)
            val address = prefs.toSettings().baseUrl
            if (owner != null && (owner.baseUrl != address || owner.token != token)) return@write
            clear(prefs, classId)
            // Reuse the existing sealed membership value; a broken Keystore costs only the cache.
            if (role == null || token == null) return@write
            val sealed = member?.token?.takeIf { TokenVault.isSealed(it) } ?: return@write
            prefs[PreferenceKeys.roleKey(classId)] = role.name
            prefs[PreferenceKeys.roleAddressKey(classId)] = address
            prefs[PreferenceKeys.roleTokenKey(classId)] = sealed
        }
    }

    private fun clear(prefs: MutablePreferences, classId: Long) {
        prefs.remove(PreferenceKeys.roleKey(classId))
        prefs.remove(PreferenceKeys.roleAddressKey(classId))
        prefs.remove(PreferenceKeys.roleTokenKey(classId))
    }
}
