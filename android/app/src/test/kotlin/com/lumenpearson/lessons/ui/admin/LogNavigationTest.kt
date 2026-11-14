package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.AuditEntry
import com.lumenpearson.lessons.core.data.repository.AuditPage
import com.lumenpearson.lessons.core.data.repository.ClassActionOwner
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManageRepository
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogNavigationTest {
    @Test
    fun `next and back replay opaque request tokens rather than counting inserted rows`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(ManagementPlumbing(scope))
        fixture.actions.loadLog()
        fixture.answer(0, page(100, "opaque/+?=A"))
        fixture.actions.showNextLogPage()
        fixture.answer(1, page(80, "opaque/B"))
        fixture.actions.showPreviousLogPage()
        fixture.answer(2, page(101, "opaque/+?=A"))
        fixture.actions.showNextLogPage()
        fixture.answer(3, page(80, ""))
        assertEquals(listOf("", "opaque/+?=A", "", "opaque/+?=A"), fixture.requests)
        assertEquals(80L, fixture.plumbing.state.value.log.value?.entries?.single()?.id)
    }

    @Test
    fun `failed next and back preserve the visible page and successful token history`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(ManagementPlumbing(scope))
        val first = page(100, "cursor-A")
        fixture.actions.loadLog()
        fixture.answer(0, first)
        fixture.actions.showNextLogPage()
        fixture.pending[1].complete(Result.failure(ManageFailure.Offline(IOException("synthetic offline"))))
        assertSame(first, fixture.plumbing.state.value.log.value)
        fixture.actions.showNextLogPage()
        val second = page(80, "cursor-B")
        fixture.answer(2, second)
        fixture.actions.showPreviousLogPage()
        fixture.pending[3].complete(Result.failure(ManageFailure.Offline(IOException("synthetic offline"))))
        assertSame(second, fixture.plumbing.state.value.log.value)
        fixture.actions.showPreviousLogPage()
        fixture.answer(4, first)
        assertEquals(listOf("", "cursor-A", "cursor-A", "", ""), fixture.requests)
    }

    @Test
    fun `repeated next taps create only one in-flight page request`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(ManagementPlumbing(scope))
        fixture.actions.loadLog()
        fixture.answer(0, page(100, "next-token"))
        fixture.actions.showNextLogPage()
        fixture.actions.showNextLogPage()
        assertEquals(2, fixture.pending.size)
        fixture.answer(1, page(80, ""))
    }

    @Test
    fun `visible positions follow successful row counts and reset on back`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(ManagementPlumbing(scope))
        fixture.actions.loadLog()
        fixture.answer(0, rows(2, "A"))
        assertEquals(1L, fixture.plumbing.state.value.logNavigation.current.firstRow)
        fixture.actions.showNextLogPage()
        fixture.answer(1, rows(1, "B"))
        assertEquals(3L, fixture.plumbing.state.value.logNavigation.current.firstRow)
        fixture.actions.showPreviousLogPage()
        fixture.answer(2, rows(3, "A"))
        assertEquals(1L, fixture.plumbing.state.value.logNavigation.current.firstRow)
        fixture.actions.showNextLogPage()
        fixture.answer(3, rows(1, ""))
        assertEquals(4L, fixture.plumbing.state.value.logNavigation.current.firstRow)
    }

    @Test
    fun `a repeated requested cursor cannot loop the current page`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(ManagementPlumbing(scope))
        fixture.actions.loadLog()
        fixture.answer(0, page(100, "A"))
        fixture.actions.showNextLogPage()
        fixture.answer(1, page(80, "A"))
        fixture.actions.showNextLogPage()
        assertEquals(listOf("", "A"), fixture.requests)
    }

    @Test
    fun `a cursor already visited on the path cannot loop back as next`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(ManagementPlumbing(scope))
        fixture.actions.loadLog()
        fixture.answer(0, page(100, "A"))
        fixture.actions.showNextLogPage()
        fixture.answer(1, page(80, "B"))
        fixture.actions.showNextLogPage()
        fixture.answer(2, page(60, "A"))
        fixture.actions.showNextLogPage()
        assertEquals(listOf("", "A", "B"), fixture.requests)
    }

    @Test
    fun `reset invalidates an old page and its path before the new first page answers`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(ManagementPlumbing(scope))
        fixture.actions.loadLog()
        fixture.answer(0, page(100, "A"))
        fixture.actions.showNextLogPage()
        fixture.plumbing.reset()
        fixture.actions.loadLog()
        fixture.answer(1, page(80, "old-next"))
        fixture.answer(2, page(200, "new-next"))
        assertEquals(200L, fixture.plumbing.state.value.log.value?.entries?.single()?.id)
        assertEquals(LogNavigation(), fixture.plumbing.state.value.logNavigation)
        assertEquals(listOf("", "A", ""), fixture.requests)
    }

    @Test
    fun `a fresh first page resets history only after its successful answer`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(ManagementPlumbing(scope))
        fixture.actions.loadLog()
        fixture.answer(0, page(100, "A"))
        fixture.actions.showNextLogPage()
        fixture.answer(1, page(80, "B"))
        val previous = fixture.plumbing.state.value.logNavigation
        fixture.actions.loadLog()
        assertEquals(previous, fixture.plumbing.state.value.logNavigation)
        fixture.answer(2, page(101, "new-next"))
        assertEquals(LogNavigation(), fixture.plumbing.state.value.logNavigation)
        assertEquals(listOf("", "A", ""), fixture.requests)
    }

    @Test
    fun `an empty next page retains the path to the visible previous page`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(ManagementPlumbing(scope))
        fixture.actions.loadLog()
        fixture.answer(0, page(100, "A"))
        fixture.actions.showNextLogPage()
        fixture.answer(1, rows(0, ""))
        fixture.actions.showPreviousLogPage()
        fixture.answer(2, page(101, "A"))
        assertEquals(listOf("", "A", ""), fixture.requests)
        assertEquals(101L, fixture.plumbing.state.value.log.value?.entries?.single()?.id)
    }

    private fun rows(count: Int, nextToken: String) = AuditPage(
        List(count) { AuditEntry(it.toLong(), "change", "Entry $it", null, null) }, nextToken,
    )

    private class Fixture(val plumbing: ManagementPlumbing) {
        init {
            plumbing.bindOwner(ClassActionOwner(1L, "https://school.invalid/", "synthetic-log-owner"))
        }

        val requests = mutableListOf<String>()
        val pending = mutableListOf<CompletableDeferred<Result<AuditPage>>>()
        private val repository = object : ManageRepository by FakeManageRepository() {
            override fun forOwner(owner: ClassActionOwner): ManageRepository = this
            override suspend fun log(pageSize: Int, pageToken: String): Result<AuditPage> {
                requests += pageToken
                return CompletableDeferred<Result<AuditPage>>().also { pending += it }.await()
            }
        }
        val actions = LogActions(plumbing, repository)
        fun answer(index: Int, page: AuditPage) { pending[index].complete(Result.success(page)) }
    }

    private fun page(id: Long, nextToken: String) = AuditPage(
        listOf(AuditEntry(id, "change", "Entry $id", null, null)), nextToken,
    )
}
