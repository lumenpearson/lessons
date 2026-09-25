package com.lumenpearson.lessons.core.data.network

import com.lumenpearson.lessons.core.data.network.BearerHarness.Companion.CLASS_TOKEN
import com.lumenpearson.lessons.core.data.network.BearerHarness.Companion.DIARY_TOKEN
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The diary's bearer goes on diary reads and nowhere else — and not on the
 * three diary calls that are anonymous on the server, because a stale bearer
 * riding the request that is meant to replace it is a dead session, or another
 * account's, sent along for nothing.
 *
 * Driven through the app's real client, all three interceptors in order, so a
 * change to any of them — or to their order — is seen here.
 */
class DiaryAuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `registration and capabilities carry no bearer even when one is stored`() {
        val harness = BearerHarness(server)

        assertNull(harness.authorizationOn("/api/v1/diary/session", method = "POST"))
        assertNull(harness.authorizationOn("/api/v1/diary/capabilities"))
        // Never called by this build, and anonymous on the server all the same.
        assertNull(harness.authorizationOn("/api/v1/diary/login", method = "POST"))
    }

    @Test
    fun `a diary read carries the diary bearer and never the class token`() {
        val harness = BearerHarness(server)

        for (path in listOf(
            "/api/v1/diary/students",
            "/api/v1/diary/students/7/schedule?from=2026-09-21&to=2026-09-27",
            "/api/v1/diary/students/7/overrides",
        )) {
            assertEquals(path, "Bearer $DIARY_TOKEN", harness.authorizationOn(path))
        }
        assertEquals("Bearer $DIARY_TOKEN", harness.authorizationOn("/api/v1/diary/logout", method = "POST"))
    }

    /** A phone in a class but signed in to no diary sends its diary calls bare, not with the class's token. */
    @Test
    fun `no diary path ever carries the class token`() {
        val harness = BearerHarness(server, diaryToken = null)

        for (path in listOf(
            "/api/v1/diary/students",
            "/api/v1/diary/session",
            "/api/v1/diary/capabilities",
        )) {
            assertNull(path, harness.authorizationOn(path))
        }
        assertEquals("Bearer $CLASS_TOKEN", harness.authorizationOn("/api/v1/bundle"))
    }

    /** The server may sit behind a reverse proxy at a path of its own; the rule is about the diary's part. */
    @Test
    fun `a base URL with a path prefix is still told apart`() {
        val prefixed = BearerHarness(server, basePath = "/lessons/")

        assertEquals("Bearer $DIARY_TOKEN", prefixed.authorizationOn("/api/v1/diary/students"))
        assertNull(prefixed.authorizationOn("/api/v1/diary/session", method = "POST"))
        assertEquals("Bearer $CLASS_TOKEN", prefixed.authorizationOn("/api/v1/bundle"))
    }
}
