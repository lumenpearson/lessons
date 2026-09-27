package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.DiaryTargetCodec
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.data.repository.ShellMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The two bearers at rest (#201): sealed on the way into the file, opened on
 * the way out, and a key that is gone costing a sign-in rather than the app.
 *
 * The Keystore itself cannot run here, so the key is held in memory
 * ([MemoryKeys]) under the same [AesGcmTokenCipher] a phone runs: the
 * ciphertext is real, and so is a tag that will not verify. The store is
 * [MemoryStore], because none of this is about files.
 */
class SealedTokensTest {

    private val samara = DiaryTarget.netschool("samara", 1234, "Школа № 5", "ivanova", "Europe/Samara")

    // -- the round trip -------------------------------------------------------

    @Test
    fun `a bearer is never written to the file as it arrived`() = runBlocking {
        val store = MemoryStore()
        val preferences = LessonsPreferences(store, testVault())

        preferences.addSession(session(7, "token-7"))
        preferences.addSession(session(9, "token-9"))
        preferences.writeDiarySession(DiarySession(login = "ivanova", token = "diary-1", target = samara))

        val onDisk = store.value.asMap().values.joinToString(" | ")
        listOf("token-7", "token-9", "diary-1").forEach { bearer ->
            assertFalse("$bearer is in the file as it arrived: $onDisk", bearer in onDisk)
        }
        assertTrue(store.value[DiaryKeys.TOKEN]!!.startsWith(TokenVault.SEALED_PREFIX))
    }

    @Test
    fun `what is sealed opens to the same bearers for every reader`() = runBlocking {
        val keys = MemoryKeys()
        val store = MemoryStore()
        val preferences = LessonsPreferences(store, testVault(keys))
        preferences.addSession(session(7, "token-7"))
        preferences.addSession(session(9, "token-9"))
        preferences.writeDiarySession(DiarySession(login = "ivanova", token = "diary-1", target = samara))

        // A new process: a new vault remembering nothing, over the same key.
        val next = LessonsPreferences(MemoryStore(store.value), testVault(keys))

        assertEquals(listOf("token-7", "token-9"), next.currentSessions().map { it.token })
        assertEquals("token-9", next.currentSession()?.token)
        assertEquals("token-9", next.session.first()?.token)
        assertEquals("diary-1", next.currentDiarySession()?.token)
        assertEquals("diary-1", next.diarySession.first()?.token)
        assertEquals("token-9", next.credentials.current().classToken)
        assertEquals("diary-1", next.credentials.current().diaryToken)
        assertEquals(ShellMode.CLASS, next.current().mode)
    }

    @Test
    fun `joining one class leaves the others' sealed tokens as they were`() = runBlocking {
        val store = MemoryStore()
        val preferences = LessonsPreferences(store, testVault())
        preferences.addSession(session(7, "token-7"))
        val sealedSeventh = store.value.memberships().single().token

        preferences.addSession(session(9, "token-9"))
        preferences.addSession(session(9, "token-9-again"))

        assertEquals(sealedSeventh, store.value.memberships().first { it.classId == 7L }.token)
        assertEquals("token-9-again", preferences.currentSession()?.token)
    }

    @Test
    fun `the cipher opens only what its own key sealed, and only intact`() {
        val keys = MemoryKeys()
        val cipher = AesGcmTokenCipher(keys)
        val sealed = cipher.seal("token-7".toByteArray())

        assertArrayEquals("token-7".toByteArray(), cipher.open(sealed))

        val damaged = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertRefused { cipher.open(damaged) }
        assertRefused { AesGcmTokenCipher(MemoryKeys().also { it.create() }).open(sealed) }
        keys.lose()
        assertRefused { cipher.open(sealed) }
        assertNotEquals(
            "a random IV per seal: the same bearer never seals to the same bytes twice",
            cipher.seal("token-7".toByteArray()).toList(),
            cipher.seal("token-7".toByteArray()).toList(),
        )
    }

    // -- an install from before sealing ----------------------------------------

    @Test
    fun `a bearer written before sealing is sealed on the first read, and only once`() = runBlocking {
        val keys = MemoryKeys()
        val vault = testVault(keys)
        val before = mutablePreferencesOf(
            MembershipKeys.SESSIONS to """[{"classId":7,"className":"7А","token":"token-7"}]""",
            DiaryKeys.TOKEN to "diary-1",
            DiaryKeys.LOGIN to "parent@example.com",
        )
        val sealing = TokenSealing(vault)

        assertTrue(sealing.shouldMigrate(before))
        val after = sealing.migrate(before)

        assertFalse("token-7" in after.asMap().values.joinToString())
        assertFalse("diary-1" in after.asMap().values.joinToString())
        assertFalse("nothing is left to seal, so the next start does nothing", sealing.shouldMigrate(after))
        assertEquals("sealing twice changes nothing", after, sealing.migrate(after))

        val read = LessonsPreferences(MemoryStore(after), testVault(keys))
        assertEquals("token-7", read.currentSession()?.token)
        assertEquals("diary-1", read.currentDiarySession()?.token)
    }

    @Test
    fun `an install from before the list has its one token sealed where it stands`() = runBlocking {
        val keys = MemoryKeys()
        val before = mutablePreferencesOf(
            MembershipKeys.TOKEN to "old-token",
            MembershipKeys.CLASS_ID to 7L,
            MembershipKeys.CLASS_NAME to "7А",
        )

        val after = TokenSealing(testVault(keys)).migrate(before)

        assertTrue(after[MembershipKeys.TOKEN]!!.startsWith(TokenVault.SEALED_PREFIX))
        assertNull("writing the list is a join's business, not the migration's", after[MembershipKeys.SESSIONS])
        assertEquals("old-token", LessonsPreferences(MemoryStore(after), testVault(keys)).currentSession()?.token)
    }

    @Test
    fun `sealing leaves everything this build cannot read exactly as it was`() = runBlocking {
        val before = mutablePreferencesOf(
            MembershipKeys.SESSIONS to
                """[{"classId":1,"className":"7А","token":"t1","somethingNew":42},""" +
                """{"classId":"not a number","token":"t2"},""" +
                """"not even an object"]""",
        )

        val after = TokenSealing(testVault()).migrate(before)[MembershipKeys.SESSIONS]!!

        assertTrue("a newer build's field survives", "\"somethingNew\":42" in after)
        assertTrue("a record this build cannot decode keeps its class id", "\"not a number\"" in after)
        assertTrue("…and what is not a record at all stays", "\"not even an object\"" in after)
        assertFalse("but no bearer is left bare in any of them", "\"t1\"" in after || "\"t2\"" in after)
    }

    @Test
    fun `a Keystore that cannot seal costs the sealing, never the token or the start`() = runBlocking {
        val vault = TokenVault(BrokenCipher)
        val before = mutablePreferencesOf(DiaryKeys.TOKEN to "diary-1", DiaryKeys.LOGIN to "parent")
        val sealing = TokenSealing(vault)

        val after = sealing.migrate(before)

        assertEquals("the value stays, readable", "diary-1", after[DiaryKeys.TOKEN])
        assertTrue("and the next start asks again", sealing.shouldMigrate(after))

        val preferences = LessonsPreferences(MemoryStore(), vault)
        preferences.addSession(session(7, "token-7"))
        assertEquals("a join still works", "token-7", preferences.credentials.current().classToken)
    }

    // -- a key that is gone ----------------------------------------------------

    /**
     * A restore onto another phone, a wiped Keystore: the tokens are in the
     * file and nothing can open them. The phone lands where a phone with no
     * token lands — the join screen, or the diary's own form — and a join puts
     * it back in a class.
     */
    @Test
    fun `a lost key costs the sign-in, not the app`() = runBlocking {
        val keys = MemoryKeys()
        val store = MemoryStore()
        LessonsPreferences(store, testVault(keys)).apply {
            addSession(session(7, "token-7"))
            writeDiarySession(DiarySession(login = "ivanova", token = "diary-1", target = samara))
        }
        keys.lose()

        val preferences = LessonsPreferences(MemoryStore(store.value), testVault(keys))

        assertNull(preferences.currentSession())
        assertEquals(emptyList<Session>(), preferences.currentSessions())
        assertNull(preferences.session.first())
        assertNull(preferences.currentDiarySession())
        assertEquals(
            "the diary's target outlives its bearer, as after a bare 401: its form asks again",
            samara,
            preferences.currentDiaryTarget(),
        )
        assertEquals(ShellMode.DIARY, preferences.current().mode)
        assertNull(preferences.credentials.current().classToken)
        assertNull(preferences.credentials.current().diaryToken)

        preferences.addSession(session(7, "token-7-again"))
        assertEquals("token-7-again", preferences.currentSession()?.token)
        assertEquals("token-7-again", preferences.credentials.current().classToken)
        assertEquals(ShellMode.CLASS, preferences.current().mode)
    }

    @Test
    fun `with no class and no diary, a lost key lands on the first screen`() = runBlocking {
        val keys = MemoryKeys()
        val store = MemoryStore()
        LessonsPreferences(store, testVault(keys)).addSession(session(7, "token-7"))
        keys.lose()

        assertEquals(ShellMode.NONE, LessonsPreferences(MemoryStore(store.value), testVault(keys)).current().mode)
    }

    /** One Keystore call per value per process — never one per read, and never a loop. */
    @Test
    fun `a token that will not open is asked about once, however often the file is read`() = runBlocking {
        val keys = MemoryKeys()
        val store = MemoryStore()
        LessonsPreferences(store, testVault(keys)).apply {
            addSession(session(7, "token-7"))
            writeDiarySession(DiarySession(login = "ivanova", token = "diary-1", target = samara))
        }
        keys.lose()
        val cipher = CountingCipher(AesGcmTokenCipher(keys))
        val preferences = LessonsPreferences(MemoryStore(store.value), TokenVault(cipher))

        repeat(10) {
            preferences.currentSession()
            preferences.currentDiarySession()
            preferences.current()
            preferences.credentials.refresh()
            preferences.session.first()
        }

        assertEquals("the class token and the diary's, once each", 2, cipher.opens.get())
    }

    @Test
    fun `a bearer that opens is asked about once too, and one just sealed not at all`() = runBlocking {
        val keys = MemoryKeys()
        val cipher = CountingCipher(AesGcmTokenCipher(keys))
        val preferences = LessonsPreferences(MemoryStore(), TokenVault(cipher))

        preferences.addSession(session(7, "token-7"))
        repeat(10) {
            preferences.currentSession()
            preferences.credentials.refresh()
        }

        assertEquals(1, cipher.seals.get())
        assertEquals("the vault remembers what it sealed", 0, cipher.opens.get())
    }

    @Test
    fun `a value sealed by a later build opens to nothing, and is never sent as a bearer`() = runBlocking {
        val prefs: Preferences = mutablePreferencesOf(
            MembershipKeys.SESSIONS to """[{"classId":7,"className":"7А","token":"gcm9:AAAA"}]""",
            DiaryKeys.TOKEN to "gcm9:BBBB",
            DiaryKeys.TARGET to DiaryTargetCodec.encode(samara),
        )

        val preferences = LessonsPreferences(MemoryStore(prefs), testVault())

        assertNull(preferences.credentials.current().classToken)
        assertNull(preferences.credentials.current().diaryToken)
        assertFalse("the migration does not touch it either", prefs.holdsBareToken())
    }

    // -------------------------------------------------------------------------

    private fun session(classId: Long, token: String) =
        Session(classId = classId, className = "$classId«А»", school = null, token = token)

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        fail("opened what it should have refused")
    }
}
