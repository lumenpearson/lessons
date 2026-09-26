package com.lumenpearson.lessons.core.data.network

import com.lumenpearson.lessons.core.data.network.BearerHarness.Companion.CLASS_TOKEN
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The class's bearer goes on the class's calls, and on nothing anonymous: not
 * on `/join` before there is a class, not on the diary, and not on the school
 * directory — which a phone asks on its first screen, and which has no business
 * learning which class is searching for which school.
 */
class AuthInterceptorTest {

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
    fun `a class call carries the class bearer`() {
        val harness = BearerHarness(server)

        assertEquals("Bearer $CLASS_TOKEN", harness.authorizationOn("/api/v1/bundle?start=2026-09-01"))
        assertEquals("Bearer $CLASS_TOKEN", harness.authorizationOn("/api/v1/manage/subjects"))
    }

    @Test
    fun `the directory search carries no class bearer`() {
        val harness = BearerHarness(server)

        assertNull(harness.authorizationOn("/api/v1/directory/school-regions?q=%D0%BB%D0%B8%D1%86%D0%B5%D0%B9"))
    }

    @Test
    fun `join, health and warmup carry no bearer`() {
        val harness = BearerHarness(server)

        assertNull(harness.authorizationOn("/api/v1/join", method = "POST"))
        assertNull(harness.authorizationOn("/api/v1/health"))
        assertNull(harness.authorizationOn("/api/v1/warmup"))
    }

    /** With no class there is no class token to send, and nothing is sent in its place. */
    @Test
    fun `a phone in no class signs nothing`() {
        val harness = BearerHarness(server, classToken = null, diaryToken = null)

        assertNull(harness.authorizationOn("/api/v1/bundle"))
    }
}
