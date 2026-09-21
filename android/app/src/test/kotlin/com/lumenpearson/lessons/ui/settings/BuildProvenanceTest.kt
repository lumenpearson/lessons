package com.lumenpearson.lessons.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an APK can say about the checkout it came from.
 *
 * All of it is handed in at build time, because a build keeps no memory of its
 * own source: an installed APK cannot work out its repository, branch or commit
 * unless whatever ran the build says so. That makes the empty case the normal
 * one for a local build, and the whole of this type is deciding what to draw
 * when a field is missing.
 */
class BuildProvenanceTest {

    private val full = BuildProvenance(
        repository = "lumenpearson/lessons",
        ref = "dev",
        commit = "a1f2392c08f124d129b26a1004aec2633f224994",
        number = "25",
        builtAt = "2026-09-21T21:33Z",
    )

    private val empty = BuildProvenance(
        repository = "",
        ref = "",
        commit = "",
        number = "",
        builtAt = "",
    )

    @Test
    fun `a build told nothing knows it was built by hand`() {
        assertTrue(empty.isLocal)
        assertFalse(full.isLocal)
    }

    @Test
    fun `a ref alone is not a CI build`() {
        // A local build may well know its branch — a `gradle.properties` can
        // say so — and still have no commit and no run to point at. What makes
        // a build identifiable is the commit, not the branch it sat on.
        assertTrue(empty.copy(ref = "dev").isLocal)
    }

    @Test
    fun `the commit is cut to the seven characters a person compares`() {
        // Seven is what `git log --oneline` prints and what GitHub shows, so it
        // is the form somebody will actually match against a pull request.
        assertEquals("a1f2392", full.shortCommit)
    }

    @Test
    fun `the link goes to the exact commit, with the whole sha`() {
        // The short form is for reading and the full one for the URL: GitHub
        // resolves seven characters today and this pins the diff for ever.
        assertEquals(
            "https://github.com/lumenpearson/lessons/commit/" +
                "a1f2392c08f124d129b26a1004aec2633f224994",
            full.commitUrl,
        )
        assertEquals("https://github.com/lumenpearson/lessons", full.repositoryUrl)
    }

    @Test
    fun `half a provenance produces no link at all`() {
        // A commit with no repository cannot be turned into a URL, and guessing
        // this repository's name would be a link to somebody else's diff on a
        // build of a fork. Both halves or neither.
        assertNull(empty.copy(commit = full.commit).commitUrl)
        assertNull(empty.copy(repository = "lumenpearson/lessons").commitUrl)
        assertNull(empty.repositoryUrl)
    }
}
