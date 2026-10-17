package com.lumenpearson.lessons.core.data.github

import com.lumenpearson.lessons.core.data.update.UpdateRepositoryImpl
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every GitHub address the app calls is built from the repository the build
 * names, not from this one's name written into the source (#469).
 *
 * While it was written in, an APK built from a fork offered this repository's
 * releases as its updates — signed with another key, so the installer refused
 * them over the fork's install — and filed its users' bug reports in this
 * repository's tracker, with nothing in the issue saying which build sent it.
 */
class GithubProjectTest {

    private val fork = GithubProject(owner = "somebody", name = "lessons-fork")

    @Test
    fun `a build that names no repository reports to the upstream one`() {
        assertEquals(GithubProject("lumenpearson", "lessons"), GithubProject.fromBuild(""))
        assertEquals(GithubProject.UPSTREAM, GithubProject.fromBuild("   "))
    }

    @Test
    fun `a build that names its repository reports to it`() {
        // What `apk.yml` passes: `github.repository`, which is `owner/name`.
        assertEquals(fork, GithubProject.fromBuild("somebody/lessons-fork"))
        assertEquals(fork, GithubProject.fromBuild(" somebody/lessons-fork\n"))
    }

    @Test
    fun `a name that is not owner slash name is not spliced into an address`() {
        // The build refuses these before anything compiles; this is the second
        // line, so that a value which somehow got past it builds no address
        // with a path, a query or a host of its own in it.
        listOf(
            "somebody", "a/b/c", "/lessons", "somebody/", "a b/c", "x/y?z", "x/y#z", "x/../y",
            "a@b/c", "x/%2e%2e", "-a/b",
        ).forEach { raw -> assertEquals(raw, GithubProject.UPSTREAM, GithubProject.fromBuild(raw)) }
    }

    /**
     * `.` and `..` are made of characters a repository name may hold, so the
     * character rules let them through and only the guard against them stands
     * between `x/..` and `/repos/x/..`, which a URL resolver walks up to
     * `/repos/` — an address of somebody else's choosing.
     */
    @Test
    fun `a name that is only dots is refused, though every dot is allowed`() {
        listOf("x/..", "x/.").forEach { raw ->
            assertEquals(raw, GithubProject.UPSTREAM, GithubProject.fromBuild(raw))
        }
        // The dot itself is not the problem: GitHub names like these exist.
        assertEquals(GithubProject("x", ".github"), GithubProject.fromBuild("x/.github"))
        assertEquals(GithubProject("x", "a.b"), GithubProject.fromBuild("x/a.b"))
    }

    @Test
    fun `the API addresses are the named repository's`() {
        assertEquals("https://api.github.com/repos/somebody/lessons-fork/issues", GithubApi.repoUrl(fork, "/issues"))
        assertEquals("https://api.github.com/repos/somebody/lessons-fork", GithubApi.repoUrl(fork, ""))
        assertEquals(
            "https://raw.githubusercontent.com/somebody/lessons-fork/main/docs/app/manifest.json",
            GithubApi.rawUrl(fork, "docs/app/manifest.json"),
        )
    }

    @Test
    fun `the update check lists the named repository's releases`() {
        assertEquals(
            "https://api.github.com/repos/somebody/lessons-fork/releases?per_page=15",
            UpdateRepositoryImpl.releasesUrl(fork),
        )
    }

    @Test
    fun `the source page is the named repository's`() {
        assertEquals("https://github.com/somebody/lessons-fork", fork.webUrl)
        assertEquals("https://github.com/lumenpearson/lessons", GithubProject.UPSTREAM.webUrl)
    }

    @Test
    fun `the browser path opens the named repository's new issue page with the report filled in`() {
        val url = fork.newIssueUrl(title = "Виджет пуст", body = "Строка 1\nСтрока & 2")

        assertTrue(url, url.startsWith("https://github.com/somebody/lessons-fork/issues/new?"))
        val query = url.substringAfter('?').split('&').associate { pair ->
            pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8.name())
        }
        assertEquals("Виджет пуст", query["title"])
        assertEquals("Строка 1\nСтрока & 2", query["body"])
    }
}
