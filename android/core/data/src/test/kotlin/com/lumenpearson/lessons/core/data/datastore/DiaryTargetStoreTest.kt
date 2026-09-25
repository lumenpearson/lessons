package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.DiaryTargetCodec
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where this phone signs in to a diary, as it is kept: one JSON value that
 * outlives the bearer, read back the same way by every flow that needs it.
 *
 * The edits are the ones `LessonsPreferences` runs inside DataStore's
 * transaction, exercised on a plain `MutablePreferences`: what is under test is
 * which keys each of them leaves behind, and none of that is about files.
 */
class DiaryTargetStoreTest {

    private val samara = DiaryTarget.netschool("samara", 1234, "Школа № 5", "ivanova", "Europe/Samara")

    @Test
    fun `a target round-trips`() {
        assertEquals(samara, DiaryTargetCodec.decode(DiaryTargetCodec.encode(samara)))
        val petersburg = DiaryTarget.petersburg("parent@example.com")
        assertEquals(petersburg, DiaryTargetCodec.decode(DiaryTargetCodec.encode(petersburg)))
    }

    @Test
    fun `the stored value names its version and its zone`() {
        val stored = DiaryTargetCodec.encode(DiaryTarget.petersburg("parent@example.com"))
        assertEquals(true, "\"v\":1" in stored)
        assertEquals(true, "\"zone\":\"Europe/Moscow\"" in stored)
    }

    /** A provider this build does not know is no target — never Petersburg by guess. */
    @Test
    fun `an unknown provider, a later version or garbage reads as no target`() {
        assertNull(DiaryTargetCodec.decode("""{"v":1,"provider":"eljur","login":"x"}"""))
        assertNull(DiaryTargetCodec.decode("""{"v":2,"provider":"netschool","login":"x"}"""))
        assertNull(DiaryTargetCodec.decode("not json"))
        assertNull(DiaryTargetCodec.decode(""))
        assertNull(DiaryTargetCodec.decode(null))
    }

    @Test
    fun `legacy keys read as a Petersburg target in Moscow`() {
        val prefs = mutablePreferencesOf(DiaryKeys.TOKEN to "ours", DiaryKeys.LOGIN to "parent@example.com")

        val target = prefs.toDiaryTarget()!!
        assertEquals(DiaryProviderKey.PETERSBURG, target.provider)
        assertEquals(ZoneId.of("Europe/Moscow"), target.zoneId())
        assertEquals(target, prefs.toDiarySession()!!.target)
    }

    @Test
    fun `an unknown provider reads as no target and no session`() {
        val prefs = mutablePreferencesOf(
            DiaryKeys.TOKEN to "ours",
            DiaryKeys.LOGIN to "x",
            DiaryKeys.TARGET to """{"v":1,"provider":"eljur","login":"x"}""",
        )

        assertNull(prefs.toDiaryTarget())
        // A bearer for an account this build cannot name is not sent.
        assertNull(prefs.toDiarySession())
    }

    @Test
    fun `a written session reads back with its target`() {
        val prefs = mutablePreferencesOf()

        prefs.putDiarySession(DiarySession(login = "ivanova", token = "ours", target = samara))

        assertEquals(DiarySession("ivanova", "ours", samara), prefs.toDiarySession())
        assertEquals(samara, prefs.toDiaryTarget())
    }

    @Test
    fun `a bare 401 clears the token and keeps the target`() {
        val prefs = mutablePreferencesOf()
        prefs.putDiarySession(DiarySession(login = "ivanova", token = "ours", target = samara))
        prefs[DiaryKeys.STUDENT_ID] = 7L

        prefs.dropDiaryToken()

        assertNull(prefs.toDiarySession())
        assertEquals(samara, prefs.toDiaryTarget())
        assertEquals(7L, prefs[DiaryKeys.STUDENT_ID])
    }

    /**
     * An install from before targets derives its target from the token and
     * the login — so dropping the token would drop the target with it, unless
     * it is written down first.
     */
    @Test
    fun `a bare 401 on a legacy install keeps its Petersburg target`() {
        val prefs = mutablePreferencesOf(DiaryKeys.TOKEN to "ours", DiaryKeys.LOGIN to "parent@example.com")

        prefs.dropDiaryToken()

        assertNull(prefs.toDiarySession())
        assertEquals(DiaryTarget.petersburg("parent@example.com"), prefs.toDiaryTarget())
    }

    @Test
    fun `signing out forgets the account and the pupil`() {
        val prefs = mutablePreferencesOf()
        prefs.putDiarySession(DiarySession(login = "ivanova", token = "ours", target = samara))
        prefs[DiaryKeys.STUDENT_ID] = 7L

        prefs.dropDiary()

        assertNull(prefs.toDiarySession())
        assertNull(prefs.toDiaryTarget())
        assertNull(prefs[DiaryKeys.STUDENT_ID])
        assertNull(prefs[DiaryKeys.LOGIN])
    }

    /** An old phone's tzdata may not know a zone the server names; midnight an hour off beats no diary. */
    @Test
    fun `a zone the phone cannot read falls back to Moscow`() {
        assertEquals(ZoneId.of("Europe/Moscow"), samara.copy(zone = "Mars/Olympus").zoneId())
        assertEquals(ZoneId.of("Europe/Samara"), samara.zoneId())
    }

    @Test
    fun `the account key ignores case and spaces in the login, and nothing else`() {
        assertEquals(samara.accountKey(), samara.copy(login = " IVANOVA ").accountKey())
        assertNotEquals(samara.accountKey(), samara.copy(region = "tomsk").accountKey())
        assertNotEquals(samara.accountKey(), DiaryTarget.petersburg("ivanova").accountKey())
    }
}
