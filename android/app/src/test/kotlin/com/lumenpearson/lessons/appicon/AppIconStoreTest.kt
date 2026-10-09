package com.lumenpearson.lessons.appicon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the rest of the app is told about the icon, and when. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppIconStoreTest {

    private val default = AppIconCatalog.default
    private val other = AppIconCatalog.variants.first { it != default }

    private fun TestScope.store(components: FakeLauncherComponents): AppIconStore {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return AppIconStore(LauncherAliases(components), io = dispatcher, scope = CoroutineScope(dispatcher))
    }

    @Test
    fun `nothing is claimed until the launcher has been read`() = runTest {
        val store = store(FakeLauncherComponents(mapOf(other.alias to true, default.alias to false)))

        assertNull(store.current.value)
        store.refresh()
        assertEquals(other, store.current.value)
    }

    @Test
    fun `a switch is what the store says once it returns`() = runTest {
        val components = FakeLauncherComponents()
        val store = store(components)

        store.switchTo(other)

        assertEquals(other, store.current.value)
        assertEquals(listOf(other), components.enabled())
    }

    @Test
    fun `a switch the platform refuses changes nothing the store says`() = runTest {
        val store = store(FakeLauncherComponents(failure = SecurityException("refused")))
        store.refresh()

        val result = runCatching { store.switchTo(other) }

        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals(default, store.current.value)
    }

    @Test
    fun `the background reconcile settles the launcher and says it is done`() = runTest {
        val components = FakeLauncherComponents(mapOf(default.alias to false))
        val store = store(components)
        var done = false

        store.reconcileInBackground { done = true }

        assertTrue(done)
        assertEquals(default, store.current.value)
        assertEquals(listOf(default), components.enabled())
    }

    @Test
    fun `a background reconcile that fails still says it is done`() = runTest {
        // The receiver finishes its broadcast from onDone; a reconcile that threw
        // and never called it would hold the process until the system killed it.
        val store = store(
            FakeLauncherComponents(mapOf(default.alias to false), failure = IllegalArgumentException("gone")),
        )
        var done = false

        store.reconcileInBackground { done = true }

        assertTrue(done)
        assertNull(store.current.value)
    }

    @Test
    fun `a switch cancelled while the write runs still leaves current naming the new icon`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fake = FakeLauncherComponents()
        lateinit var job: Job
        // The write itself cancels the very job that is running switchTo, partway
        // through the platform call, which is what the fix has to survive: the
        // assignment has to happen before that cancellation is ever resolved,
        // not after switchTo resumes.
        val cancelsMidWrite = object : LauncherComponents {
            override fun isEnabled(alias: String) = fake.isEnabled(alias)
            override fun apply(changes: List<AliasChange>) {
                job.cancel()
                fake.apply(changes)
            }
        }
        val store = AppIconStore(LauncherAliases(cancelsMidWrite), io = dispatcher, scope = CoroutineScope(dispatcher))

        // LAZY, then started only once `job` itself has been assigned: on this
        // unconfined dispatcher a plain `launch` would run the body inline before
        // the assignment completed, and `job` would still be unset when apply()
        // reaches for it.
        job = launch(dispatcher, start = CoroutineStart.LAZY) { store.switchTo(other) }
        job.start()

        assertEquals(other, store.current.value)
        assertEquals(listOf(other), fake.enabled())
    }
}
