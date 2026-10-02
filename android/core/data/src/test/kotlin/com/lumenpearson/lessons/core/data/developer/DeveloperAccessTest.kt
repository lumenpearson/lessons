package com.lumenpearson.lessons.core.data.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The developer mode's gate (#237): who counts as a developer, how long
 * GitHub's answer stands, and what the page shows meanwhile.
 */
class DeveloperAccessTest {

    private val day = VerdictLifetimeMillis
    private val now = 10 * day

    @Test
    fun `push and above make a developer, triage and read do not`() {
        assertEquals(DeveloperRole.ADMIN, developerRoleOf(RepositoryPermissions(admin = true, push = true)))
        assertEquals(DeveloperRole.MAINTAIN, developerRoleOf(RepositoryPermissions(maintain = true, push = true)))
        assertEquals(DeveloperRole.WRITE, developerRoleOf(RepositoryPermissions(push = true)))
        // What every GitHub account has on a public repository.
        assertNull(developerRoleOf(RepositoryPermissions()))
    }

    @Test
    fun `a verdict stands for its own login, in any case, for a day`() {
        val verdict = DeveloperVerdict("LumenPearson", DeveloperRole.ADMIN, now)

        assertTrue(verdict.standsFor("lumenpearson", now))
        assertTrue(verdict.standsFor("lumenpearson", now + day))
        assertFalse("a day and a millisecond later it is stale", verdict.standsFor("lumenpearson", now + day + 1))
        assertFalse("another account signed in since", verdict.standsFor("somebody", now))
        assertFalse("signed out", verdict.standsFor(null, now))
    }

    @Test
    fun `a clock moved back past the check reads as stale, not as an answer from the future`() {
        val verdict = DeveloperVerdict("dev", DeveloperRole.WRITE, now)

        assertFalse(verdict.standsFor("dev", now - 1))
    }

    /** [accessOf] with the arguments a test varies, named. */
    private fun access(
        verdict: DeveloperVerdict?,
        login: String?,
        checking: Boolean = false,
        failure: String? = null,
        at: Long = now,
    ): DeveloperAccess = accessOf(verdict, login, checking, failure, at)

    @Test
    fun `signed out is signed out whatever is stored`() {
        val verdict = DeveloperVerdict("dev", DeveloperRole.ADMIN, now)

        assertEquals(DeveloperAccess.SignedOut, access(verdict, login = null, checking = true, failure = "x"))
    }

    @Test
    fun `a standing yes outlives the daily re-check, so the tools do not blink`() {
        val verdict = DeveloperVerdict("dev", DeveloperRole.MAINTAIN, now)

        assertEquals(
            DeveloperAccess.Granted("dev", DeveloperRole.MAINTAIN, now),
            access(verdict, "dev", checking = true, at = now + 1),
        )
    }

    @Test
    fun `without a standing yes the page says what is happening`() {
        val refusal = DeveloperVerdict("dev", role = null, checkedAtMillis = now)
        val stale = DeveloperVerdict("dev", DeveloperRole.ADMIN, now - 2 * day)

        assertEquals(DeveloperAccess.Checking("dev"), access(null, "dev", checking = true))
        assertEquals(DeveloperAccess.Denied("dev"), access(refusal, "dev"))
        assertEquals(DeveloperAccess.Unknown("dev", "HTTP 503"), access(stale, "dev", failure = "HTTP 503"))
        assertEquals(DeveloperAccess.Unknown("dev", null), access(null, "dev"))
    }

    @Test
    fun `no tool is on without the access, whatever was chosen`() {
        val chosen = DeveloperTool.entries.toSet()
        fun toolsWith(access: DeveloperAccess) = DeveloperState(revealed = true, access = access, chosen = chosen).tools

        assertEquals(emptySet<DeveloperTool>(), toolsWith(DeveloperAccess.Denied("dev")))
        assertEquals(emptySet<DeveloperTool>(), toolsWith(DeveloperAccess.SignedOut))
        assertEquals(chosen, toolsWith(DeveloperAccess.Granted("dev", DeveloperRole.WRITE, now)))
    }
}
