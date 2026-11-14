package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.datastore.MemoryStore
import com.lumenpearson.lessons.core.data.datastore.testVault
import com.lumenpearson.lessons.core.data.datastore.PreferenceKeys
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachedRoleOwnerTest {
    @Test
    fun `a legacy role with no server and bearer owner remains unknown`() = runTest {
        val store = MemoryStore()
        val preferences = LessonsPreferences(store, testVault(), UnconfinedTestDispatcher(testScheduler))
        preferences.addSession(Session(7, "A", null, "synthetic-A"))
        store.edit { it[PreferenceKeys.roleKey(7)] = ClassRole.ADMIN.name }
        assertNull(preferences.lastRole(7).first())
    }

    @Test
    fun `a role owner reuses the sealed membership without persisting a second bare credential`() = runTest {
        val store = MemoryStore()
        val preferences = LessonsPreferences(store, testVault(), UnconfinedTestDispatcher(testScheduler))
        preferences.addSession(Session(7, "A", null, "synthetic-A"))
        preferences.rememberRole(7, ClassRole.ADMIN)
        val value = store.data.first()[PreferenceKeys.roleTokenKey(7)]
        org.junit.Assert.assertTrue(value?.startsWith("gcm1:") == true)
        org.junit.Assert.assertFalse(value?.contains("synthetic-A") == true)
        assertEquals(ClassRole.ADMIN, preferences.lastRole(7).first())
    }

    @Test
    fun `a persisted manager role does not belong to a replacement bearer for the same class`() = runTest {
        val preferences = LessonsPreferences(MemoryStore(), testVault(), UnconfinedTestDispatcher(testScheduler))
        preferences.updateSettings { it.copy(baseUrl = "https://school.invalid/") }
        preferences.addSession(Session(7, "A", null, "synthetic-A"))
        preferences.rememberRole(7, ClassRole.ADMIN)
        assertEquals(ClassRole.ADMIN, preferences.lastRole(7).first())
        preferences.addSession(Session(7, "A", null, "synthetic-replacement"))
        assertNull("The new unlinked bearer has never earned the previous role", preferences.lastRole(7).first())
    }

    @Test
    fun `a role cached at another server is not an offline privilege of the current server`() = runTest {
        val preferences = LessonsPreferences(MemoryStore(), testVault(), UnconfinedTestDispatcher(testScheduler))
        preferences.updateSettings { it.copy(baseUrl = "https://school.invalid/") }
        preferences.addSession(Session(7, "A", null, "synthetic-A"))
        preferences.rememberRole(7, ClassRole.OWNER)
        assertEquals(ClassRole.OWNER, preferences.lastRole(7).first())
        preferences.updateSettings { it.copy(baseUrl = "https://successor.invalid/") }
        assertNull(preferences.lastRole(7).first())
    }

    @Test
    fun `the same server and bearer retain their persisted role across preference facades`() = runTest {
        val store = MemoryStore()
        val vault = testVault()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val first = LessonsPreferences(store, vault, dispatcher)
        first.updateSettings { it.copy(baseUrl = "https://school.invalid/") }
        first.addSession(Session(7, "A", null, "synthetic-A"))
        first.rememberRole(7, ClassRole.ADMIN)
        assertEquals(ClassRole.ADMIN, LessonsPreferences(store, vault, dispatcher).lastRole(7).first())
    }
}
