package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.lumenpearson.lessons.core.data.repository.DiarySession

/**
 * Seals the bearers an install wrote before #201, the first time the file is
 * opened in a process.
 *
 * A DataStore migration rather than a step in `LessonsPreferences`, because it
 * is DataStore's own answer to «before anything reads this file»: it runs on
 * the store's scope, off the main thread, before the first value is served, and
 * whatever reads the file afterwards finds the tokens sealed. It asks first
 * ([shouldMigrate]), and what it asks is whether any token is still bare — so
 * it is idempotent by construction, and on every start after the first it costs
 * a look at three keys.
 *
 * **It never throws.** An exception out of a migration is an exception out of
 * every read of the file, and the preferences' read side degrades only an
 * `IOException` to defaults; anything else would be a crash at launch, for
 * ever. A token the Keystore will not seal stays as it is (see
 * [TokenVault.seal]), and the next start asks again.
 */
internal class TokenSealing(private val vault: TokenVault) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        runCatching { currentData.holdsBareToken() }.getOrDefault(false)

    override suspend fun migrate(currentData: Preferences): Preferences =
        runCatching { currentData.toMutablePreferences().apply { sealBareTokens(vault) }.toPreferences() }
            .getOrDefault(currentData)

    override suspend fun cleanUp() = Unit
}

/** Whether any bearer in the file is still stored as it arrived. */
internal fun Preferences.holdsBareToken(): Boolean =
    isBare(this[MembershipKeys.TOKEN]) ||
        isBare(this[DiaryKeys.TOKEN]) ||
        this[MembershipKeys.SESSIONS]?.let(::membershipListHoldsBareToken) == true

/**
 * Seals every bearer still stored bare, where it stands: the list's records in
 * place, the four flat keys an install from before the list holds as they are
 * (writing the list is a join's business, not a migration's), and the diary's.
 */
internal fun MutablePreferences.sealBareTokens(vault: TokenVault) {
    this[MembershipKeys.TOKEN]?.takeIf(::isBare)?.let { this[MembershipKeys.TOKEN] = vault.seal(it) }
    this[DiaryKeys.TOKEN]?.takeIf(::isBare)?.let { this[DiaryKeys.TOKEN] = vault.seal(it) }
    this[MembershipKeys.SESSIONS]
        ?.let { sealedMembershipList(it, vault) }
        ?.let { this[MembershipKeys.SESSIONS] = it }
}

/**
 * [toDiarySession] with its bearer opened, or `null` when it will not open —
 * which reads the way a bare `401` leaves the store: the target is still there,
 * so the family is asked to sign in to the same diary again.
 */
internal fun Preferences.openDiarySession(vault: TokenVault): DiarySession? =
    toDiarySession()?.let { stored -> vault.open(stored.token)?.let { stored.copy(token = it) } }

/**
 * Opens every token in one emission of the file, so that whatever maps it next
 * finds each one already remembered.
 *
 * This is where the Keystore is actually asked, and it is called only from a
 * flow running on the IO dispatcher — `LessonsPreferences.opened` — so that the
 * binder call is never made on the thread that collects, which for the screens
 * is the main one.
 */
internal fun Preferences.openEveryToken(vault: TokenVault) {
    openMemberships(vault)
    this[DiaryKeys.TOKEN]?.let(vault::open)
}
