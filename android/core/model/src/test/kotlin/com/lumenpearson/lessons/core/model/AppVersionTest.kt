package com.lumenpearson.lessons.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `a leading v and surrounding space are not part of the version`() {
        assertEquals(AppVersion.parse("0.2.0"), AppVersion.parse("  v0.2.0 "))
    }

    @Test
    fun `double digits compare as numbers, not as text`() {
        assertTrue(AppVersion.isNewer(candidate = "v0.10.0", installed = "v0.9.0"))
        assertFalse(AppVersion.isNewer(candidate = "v0.9.0", installed = "v0.10.0"))
    }

    @Test
    fun `a missing component is a zero`() {
        assertEquals(0, AppVersion.parse("1.2")!!.compareTo(AppVersion.parse("1.2.0")!!))
        assertTrue(AppVersion.isNewer(candidate = "1.2.1", installed = "1.2"))
    }

    @Test
    fun `a release candidate is older than the release it leads to`() {
        assertTrue(AppVersion.isNewer(candidate = "1.0.0", installed = "1.0.0-rc.1"))
        assertFalse(AppVersion.isNewer(candidate = "1.0.0-rc.1", installed = "1.0.0"))
    }

    @Test
    fun `release candidates are ordered numerically, not alphabetically`() {
        assertTrue(AppVersion.isNewer(candidate = "1.0.0-rc.10", installed = "1.0.0-rc.2"))
    }

    @Test
    fun `a shorter pre-release comes first`() {
        assertTrue(AppVersion.isNewer(candidate = "1.0.0-rc.1", installed = "1.0.0-rc"))
    }

    @Test
    fun `an alphanumeric identifier outranks a numeric one`() {
        assertTrue(AppVersion.isNewer(candidate = "1.0.0-alpha", installed = "1.0.0-1"))
    }

    @Test
    fun `build metadata is not part of identity`() {
        assertEquals(0, AppVersion.parse("1.0.0+ci.42")!!.compareTo(AppVersion.parse("1.0.0")!!))
    }

    @Test
    fun `build metadata containing a dash is not read as a pre-release`() {
        assertNull(AppVersion.parse("1.0.0+build-7")!!.preRelease)
    }

    @Test
    fun `the same version is never an update`() {
        assertFalse(AppVersion.isNewer(candidate = "v0.1.0", installed = "0.1.0"))
    }

    @Test
    fun `nothing is offered when either side cannot be read`() {
        assertFalse(AppVersion.isNewer(candidate = "nightly", installed = "0.1.0"))
        assertFalse(AppVersion.isNewer(candidate = "0.2.0", installed = null))
    }

    @Test
    fun `a tag with no numbers at all is not a version`() {
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse(null))
    }

    @Test
    fun `a debug suffix would otherwise make every build look outdated`() {
        // The debug build stamps `-debug` onto the version name, and semver
        // reads that as a pre-release. Left alone, a debug install of the
        // current release would be offered that same release as an upgrade,
        // forever. The suffix is therefore stripped before the comparison; this
        // test states what happens if it is not.
        assertTrue(AppVersion.isNewer(candidate = "0.1.0", installed = "0.1.0-debug"))
        assertFalse(AppVersion.isNewer(candidate = "0.1.0", installed = "0.1.0"))
    }

    @Test
    fun `the pre-release flag says whether a tag is final`() {
        assertTrue(AppVersion.parse("1.0.0-beta.1")!!.isPreRelease)
        assertFalse(AppVersion.parse("1.0.0")!!.isPreRelease)
    }
}
