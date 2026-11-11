package com.lumenpearson.lessons.core.data.github

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationBaseTest {
    private val fork = GithubProject("fork-owner", "diary")
    private val devUrl = "https://api.github.com/repos/fork-owner/diary/git/ref/heads/dev"
    private val repoUrl = "https://api.github.com/repos/fork-owner/diary"
    private fun found(sha: String) = GithubRead(200, """{"object":{"sha":"$sha"}}""")

    @Test
    fun `a default-only fork offers owner and reader corrections from the same resolved source`() = runTest {
        for (login in listOf("fork-owner", "reader")) {
            val asked = mutableListOf<String>()
            val mainUrl = "$repoUrl/git/ref/heads/main"
            val base = translationBase(fork) { url ->
                asked += url
                when (url) {
                    devUrl -> GithubRead(404, "{}")
                    repoUrl -> GithubRead(200, """{"default_branch":"main"}""")
                    mainUrl -> found("main-sha")
                    else -> error("Unexpected request: $url")
                }
            }
            assertEquals(TranslationBase("main", "main-sha"), base)
            assertEquals(listOf(devUrl, repoUrl, mainUrl), asked)
            val payload = base!!.pullRequestBody(fork, login, "work/correction", 1, login)
            assertEquals("main", payload.base)
            assertEquals(if (login == fork.owner) "work/correction" else "$login:work/correction", payload.head)
        }
    }

    @Test
    fun `an available integration ref is used without reading a default branch`() = runTest {
        for (project in listOf(fork, GithubProject.UPSTREAM)) {
            val asked = mutableListOf<String>()
            val base = translationBase(project) { url -> asked += url; found("dev-sha") }
            assertEquals(TranslationBase("dev", "dev-sha"), base)
            assertEquals(listOf("${GithubApi.API_BASE}/repos/${project.fullName}/git/ref/heads/dev"), asked)
        }
    }

    @Test
    fun `this project requires dev even when its name uses a different case`() = runTest {
        val project = GithubProject("LUMENPEARSON", "LESSONS")
        val asked = mutableListOf<String>()
        assertNull(translationBase(project) { url -> asked += url; GithubRead(404, "{}") })
        assertEquals(1, asked.size)
    }

    @Test
    fun `authorization rate limiting and upstream errors do not select another branch`() = runTest {
        for (status in listOf(401, 403, 429, 500, 503)) {
            val asked = mutableListOf<String>()
            assertNull(translationBase(fork) { url -> asked += url; GithubRead(status, "{}") })
            assertEquals(listOf(devUrl), asked)
        }
    }

    @Test
    fun `a fork default ref is encoded for the lookup and preserved for the PR`() = runTest {
        val asked = mutableListOf<String>()
        val encodedUrl = "$repoUrl/git/ref/heads/release/ready%231"
        val base = translationBase(fork) { url ->
            asked += url
            when (url) {
                devUrl -> GithubRead(404, "{}")
                repoUrl -> GithubRead(200, """{"default_branch":"release/ready#1"}""")
                encodedUrl -> found("release-sha")
                else -> error("Unexpected request: $url")
            }
        }
        assertEquals(TranslationBase("release/ready#1", "release-sha"), base)
        assertEquals(listOf(devUrl, repoUrl, encodedUrl), asked)
        assertEquals("release/ready#1",
            base!!.pullRequestBody(fork, "reader", "work/correction", 1, "reader").base)
    }

    @Test
    fun `a missing default target or metadata never produces a writable base`() = runTest {
        for (defaultBranch in listOf("", "main", "dev")) {
            val asked = mutableListOf<String>()
            assertNull(translationBase(fork) { url ->
                asked += url
                if (url == repoUrl) GithubRead(200, """{"default_branch":"$defaultBranch"}""")
                else GithubRead(404, "{}")
            })
            assertEquals(if (defaultBranch == "main") 3 else 2, asked.size)
        }
    }
}
