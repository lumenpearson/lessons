package com.lumenpearson.lessons.core.data.diary

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.datastore.MemoryStore
import com.lumenpearson.lessons.core.data.datastore.testVault
import com.lumenpearson.lessons.core.data.network.v2.DiaryRemote
import com.lumenpearson.lessons.core.data.repository.DiaryHandover
import com.lumenpearson.lessons.core.data.repository.DiaryRegistration
import com.lumenpearson.lessons.core.data.repository.DiaryRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiarySignInImpl
import com.lumenpearson.lessons.core.data.repository.DiarySignIn
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.toDomain
import com.lumenpearson.lessons.core.data.upstream.FakeUpstreamDirectory
import com.lumenpearson.lessons.core.data.upstream.MutableClock
import com.lumenpearson.lessons.core.data.upstream.PETERSBURG_JWT
import com.lumenpearson.lessons.core.data.upstream.UpstreamSession
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiaryAccountTransitionTest {
    @Test
    fun `an old bearer read begun after clear cannot populate the replacement account cache`() {
        replacementWitness(answerBeforeStore = false)
    }

    @Test
    fun `an old read finishing inside the local transition cannot leave rows under the successor`() {
        replacementWitness(answerBeforeStore = true)
    }

    private fun replacementWitness(answerBeforeStore: Boolean) = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.seed()
        val cleared = CompletableDeferred<Unit>()
        val storePaused = CompletableDeferred<Unit>()
        val allowStore = CompletableDeferred<Unit>()
        val readStarted = CompletableDeferred<Unit>()
        val allowAnswer = CompletableDeferred<Unit>()
        fixture.edits.beforeWrite = {
            if (cleared.isCompleted && !storePaused.isCompleted) {
                storePaused.complete(Unit)
                allowStore.await()
            }
        }
        fixture.api.duringRequest = {
            readStarted.complete(Unit)
            allowAnswer.await()
        }
        val acceptedRemote = object : DiaryRemote by fixture.api {
            override suspend fun registerSession(handover: DiaryHandover) =
                DiaryRegistration(fixture.successor, listOf(student(2, "Next").toDomain()))
        }
        val signIn = DiarySignInImpl(
            acceptedRemote, fixture.preferences, { FakeUpstreamDirectory(null) },
            { error("The already-open synthetic session needs no upstream network call") },
            forgetLocal = { fixture.cache.clear(); cleared.complete(Unit) },
            clock = fixture.clock, ioDispatcher = fixture.dispatcher,
        )
        val upstream = UpstreamSession.Petersburg(
            fixture.successor.target, PETERSBURG_JWT, fixture.clock.instant(),
        )
        val registration = async(start = CoroutineStart.UNDISPATCHED) { signIn.register(upstream) }
        try {
            runCurrent()
            assertTrue("The witness must enter the post-clear/pre-persistence interval", storePaused.isCompleted)
            assertEquals("synthetic-diary-A", fixture.preferences.currentDiarySession()?.token)
            val read = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.students() }
            runCurrent()
            assertTrue("Network IO must remain outside the local transition gate", readStarted.isCompleted)
            if (answerBeforeStore) {
                allowAnswer.complete(Unit)
                runCurrent()
                assertEquals("Old rows must not be cached while the transition owns local persistence", 0,
                    fixture.dao.rowCount)
            }
            allowStore.complete(Unit)
            assertTrue(registration.await().isSuccess)
            assertTrue(upstream.spent)
            assertEquals(fixture.successor, fixture.preferences.currentDiarySession())
            allowAnswer.complete(Unit)
            assertTrue(read.await().isSuccess)
            assertEquals("No previous-family row may survive under the new bearer", 0, fixture.dao.rowCount)
        } finally {
            fixture.edits.beforeWrite = {}
            allowStore.complete(Unit)
            allowAnswer.complete(Unit)
        }
    }

    @Test
    fun `remote cancellation propagates without forgetting the account or its populated cache`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.seed(populate = true)
        val before = fixture.dao.rowCount
        assertTrue(before > 1)
        val cancellation = CancellationException("synthetic cancelled logout")
        fixture.api.failures["logout"] = cancellation

        val thrown = runCatching { fixture.repository.signOut() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(generateSequence(thrown) { it.cause }.take(12).any { it === cancellation })
        assertEquals(fixture.original, fixture.preferences.currentDiarySession())
        assertEquals(before, fixture.dao.rowCount)
    }

    @Test
    fun `cancellation after the credential commit still finishes owned cache cleanup`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.seed(populate = true)
        val committed = CompletableDeferred<Unit>()
        val pending = async(start = CoroutineStart.LAZY) { fixture.repository.signOut() }
        fixture.edits.afterWrite = {
            if (fixture.preferences.currentDiarySession() == null && !committed.isCompleted) {
                committed.complete(Unit)
                pending.cancel(CancellationException("synthetic cancellation after the local commit"))
            }
        }
        try {
            pending.start()
            runCurrent()
            assertTrue("The credential commit, not an earlier cancellation, must be witnessed", committed.isCompleted)
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            assertNull(fixture.preferences.currentDiarySession())
            assertEquals("Signing out must not strand the previous account's rows", 0, fixture.dao.rowCount)
        } finally {
            fixture.edits.afterWrite = {}
        }
    }

    @Test
    fun `an ordinary logout network failure still clears local credentials and cache`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.seed(populate = true)
        fixture.api.failures["logout"] = IOException("synthetic offline")
        assertTrue(fixture.repository.signOut().isSuccess)
        assertNull(fixture.preferences.currentDiarySession())
        assertEquals(0, fixture.dao.rowCount)
    }

    @Test
    fun `a failed first persistence commit retains both account and offline rows`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.seed(populate = true)
        val before = fixture.dao.rowCount
        fixture.edits.beforeWrite = { throw IOException("synthetic disk refusal") }
        try {
            assertTrue(fixture.repository.signOut().isFailure)
            assertEquals(fixture.original, fixture.preferences.currentDiarySession())
            assertEquals(before, fixture.dao.rowCount)
        } finally {
            fixture.edits.beforeWrite = {}
        }
    }

    @Test
    fun `sign-out finishing after a replacement account cannot erase the successor`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.seed(populate = true)
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        val remote = object : DiaryRemote by fixture.api {
            override suspend fun logout() {
                started.complete(Unit)
                answer.await()
            }
        }
        val repository = fixture.repository(remote)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { repository.signOut() }
        try {
            runCurrent()
            assertTrue(started.isCompleted)
            val unlocked = fixture.preferences.membershipChanges.tryLock()
            if (unlocked) fixture.preferences.membershipChanges.unlock()
            assertTrue("The remote call must not own the persistence gate", unlocked)
            fixture.cache.clear()
            fixture.preferences.writeDiarySession(fixture.successor)
            fixture.cache.putStudents(fixture.cache.generation(), listOf(student(2, "Next").toDomain()))
            val before = fixture.dao.rowCount
            answer.complete(Unit)
            pending.await()
            assertEquals(fixture.successor, fixture.preferences.currentDiarySession())
            assertEquals(before, fixture.dao.rowCount)
            assertEquals(listOf(2L), fixture.dao.students.map { it.id })
        } finally {
            answer.complete(Unit)
        }
    }

    @Test
    fun `a registration result cannot seed pupils after another account has replaced it`() = runTest {
        val fixture = Fixture(UnconfinedTestDispatcher(testScheduler))
        fixture.seed()
        val signIn = object : DiarySignIn by UnusedSignIn {
            override suspend fun register(upstream: UpstreamSession): Result<DiaryRegistration> {
                fixture.preferences.writeDiarySession(fixture.successor)
                return Result.success(DiaryRegistration(fixture.original, listOf(student(1).toDomain())))
            }
        }
        val seeding = SeedingDiarySignIn(signIn, fixture.cache, fixture.preferences)
        val upstream = UpstreamSession.Petersburg(fixture.original.target, PETERSBURG_JWT, fixture.clock.instant())

        assertTrue(seeding.register(upstream).isSuccess)

        assertEquals(fixture.successor, fixture.preferences.currentDiarySession())
        assertEquals(0, fixture.dao.rowCount)
    }

    private class Fixture(val dispatcher: CoroutineDispatcher) {
        val edits = Edits(MemoryStore())
        val preferences = LessonsPreferences(edits, testVault(), dispatcher)
        val clock = MutableClock()
        val dao = InMemoryDiaryDao()
        val cache = DiaryCacheImpl({ dao }, preferences, clock)
        val api = ScriptedDiaryApi(lessons = listOf(lesson("2026-09-22", "Previous family lesson")))
        val original = DiarySession("previous-parent", "synthetic-diary-A", DiaryTarget.petersburg("previous-parent"))
        val successor = DiarySession("next-parent", "synthetic-diary-B", DiaryTarget.petersburg("next-parent"))
        val repository = repository(api)

        fun repository(remote: DiaryRemote) = DiaryRepositoryImpl(
            remote, preferences, UnusedSignIn, cache::clear, cache, dispatcher,
        )

        suspend fun seed(populate: Boolean = false) {
            preferences.updateSettings { it.copy(baseUrl = "https://school.example/") }
            preferences.writeDiarySession(original)
            api.credentials = preferences.credentials::current
            if (populate) {
                repository.students().getOrThrow()
                repository.schedule(1, LocalDate.parse("2026-09-21"), LocalDate.parse("2026-09-27")).getOrThrow()
            }
        }
    }

    private class Edits(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        var beforeWrite: suspend () -> Unit = {}
        var afterWrite: suspend () -> Unit = {}
        override val data: Flow<Preferences> get() = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            beforeWrite()
            return delegate.updateData(transform).also { afterWrite() }
        }
    }
}
