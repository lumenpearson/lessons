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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the rest of the app is told about the icon, and when; over
 * [TestCatalog], for the reason `LauncherAliasesTest` gives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppIconStoreTest {

    private val default = TestCatalog.default
    private val other = TestCatalog.other

    private fun TestScope.store(components: LauncherComponents): AppIconStore {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return AppIconStore(TestCatalog.aliases(components), io = dispatcher, scope = CoroutineScope(dispatcher))
    }

    @Test
    fun `nothing is claimed until the launcher has been read`() = runTest {
        val store = store(TestCatalog.launcher(mapOf(other.alias to true, default.alias to false)))

        assertNull(store.current.value)
        store.refresh()
        assertEquals(other, store.current.value)
    }

    @Test
    fun `a switch is what the store says once it returns`() = runTest {
        val components = TestCatalog.launcher()
        val store = store(components)

        store.switchTo(other)

        assertEquals(other, store.current.value)
        assertEquals(listOf(other), components.enabled())
    }

    @Test
    fun `a switch the platform refuses changes nothing the store says`() = runTest {
        val store = store(TestCatalog.launcher(failure = SecurityException("refused")))
        store.refresh()

        val result = runCatching { store.switchTo(other) }

        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals(default, store.current.value)
    }

    @Test
    fun `a switch that dies between its two calls leaves current naming what the launcher shows`() = runTest {
        // Below Android 13 a switch is two calls. Here the first, which enables the
        // new icon, lands, and the second, which would disable the default, throws:
        // the launcher has two entries, and the one a reconcile keeps is the new one.
        val launcher = TestCatalog.launcher()
        val refused = IllegalStateException("the second call failed")
        val diesHalfway = object : LauncherComponents {
            override fun isEnabled(alias: String) = launcher.isEnabled(alias)
            override fun apply(changes: List<AliasChange>) {
                launcher.apply(changes.take(1))
                throw refused
            }
        }
        val store = store(diesHalfway)
        store.refresh()

        val result = runCatching { store.switchTo(other) }

        assertSame(refused, result.exceptionOrNull())
        assertEquals(listOf(default, other), launcher.enabled())
        assertEquals(TestCatalog.aliases(launcher).current(), store.current.value)
        assertEquals(other, store.current.value)
    }

    @Test
    fun `the background reconcile settles the launcher and says it is done`() = runTest {
        val components = TestCatalog.launcher(mapOf(default.alias to false))
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
            TestCatalog.launcher(mapOf(default.alias to false), failure = IllegalArgumentException("gone")),
        )
        var done = false

        store.reconcileInBackground { done = true }

        assertTrue(done)
        assertNull(store.current.value)
    }

    @Test
    fun `a switch cancelled while the write runs still leaves current naming the new icon`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fake = TestCatalog.launcher()
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
        val store = AppIconStore(
            TestCatalog.aliases(cancelsMidWrite),
            io = dispatcher,
            scope = CoroutineScope(dispatcher),
        )

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
