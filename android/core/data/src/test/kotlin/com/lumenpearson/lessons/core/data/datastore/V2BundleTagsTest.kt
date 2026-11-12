package com.lumenpearson.lessons.core.data.datastore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2BundleTagsTest {
    private val preferences = LessonsPreferences(MemoryStore(), testVault())

    @Test
    fun `a v1 tag can never be returned for a v2 request and both versions remain distinct`() = runBlocking {
        preferences.writeBundleTag("1|2026", "v1")
        assertNull(preferences.v2BundleTag("1|2026"))
        preferences.writeV2BundleTag("1|2026", "v2")
        assertEquals("v1", preferences.bundleTag("1|2026"))
        assertEquals("v2", preferences.v2BundleTag("1|2026"))
    }

    @Test
    fun `eviction leaving retention and sign out sweep both namespaces at the same boundary`() = runBlocking {
        for (signature in listOf("1|2025", "1|2026", "12|2025", "12|2026")) {
            preferences.writeBundleTag(signature, "v1:$signature")
            preferences.writeV2BundleTag(signature, "v2:$signature")
        }
        preferences.writeScheduleFingerprint("retained fingerprint")
        preferences.forgetBundleTag("1|2025")
        assertGone("1|2025")
        assertPresent("1|2026")
        assertPresent("12|2025")
        preferences.forgetBundleTagsOf(1)
        assertGone("1|2026")
        assertPresent("12|2025")
        preferences.forgetBundleTagsOutside(listOf(12))
        assertPresent("12|2025")
        assertPresent("12|2026")
        preferences.forgetBundleTagsOutside(emptyList())
        assertGone("12|2025")
        assertGone("12|2026")
        assertEquals("retained fingerprint", preferences.scheduleFingerprint())
    }

    @Test
    fun `retaining one class removes both namespaces of every other class`() = runBlocking {
        for (signature in listOf("1|2025", "1|2026", "12|2025", "12|2026")) {
            preferences.writeBundleTag(signature, "v1:$signature")
            preferences.writeV2BundleTag(signature, "v2:$signature")
        }
        preferences.forgetBundleTagsOutside(listOf(12))
        assertGone("1|2025")
        assertGone("1|2026")
        assertPresent("12|2025")
        assertPresent("12|2026")
    }

    private suspend fun assertGone(signature: String) {
        assertNull(preferences.bundleTag(signature))
        assertNull(preferences.v2BundleTag(signature))
    }

    private suspend fun assertPresent(signature: String) {
        assertEquals("v1:$signature", preferences.bundleTag(signature))
        assertEquals("v2:$signature", preferences.v2BundleTag(signature))
    }
}
