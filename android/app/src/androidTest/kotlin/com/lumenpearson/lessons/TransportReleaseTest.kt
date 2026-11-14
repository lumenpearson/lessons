package com.lumenpearson.lessons

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.repository.JoinFailure
import java.net.URI
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Three bounded requests: an unexpected answer ends the check without a retry. */
class TransportReleaseTest {
    @Test(timeout = TotalBudgetMillis)
    fun minifiedConnectDecodesClassAndDiaryResponses(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val expected = arguments.getString("expectedCommit")
        assertFalse("This proof requires the minified release app", BuildConfig.DEBUG)
        assertTrue("This proof requires Connect", BuildConfig.TRANSPORT == "connect")
        assertTrue("The installed app must name the exact reviewed source",
            expected?.matches(Regex("[0-9a-f]{40}")) == true && expected == BuildConfig.BUILD_COMMIT)
        val endpoint = checkNotNull(arguments.getString("serverUrl")) { "A verified production URL is required" }
        val uri = URI(endpoint)
        assertTrue("Production must use HTTPS without credentials, query or fragment",
            uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)

        Graph.ensureInitialized(instrumentation.targetContext)
        val repository = Graph.container.sessionRepository
        assertTrue("The native fixture must have no memberships", repository.currentAll().isEmpty())
        val diary = Graph.container.diaryRepository
        requireEmptyDiary(diary)
        val settings = Graph.container.settingsRepository
        val original = settings.settings.first().baseUrl
        try {
            settings.update { it.copy(baseUrl = endpoint) }
            withTimeout(ProbeBudgetMillis) {
                checkDiaryBindings(diary)
                val result = withTimeout(CallBudgetMillis) { repository.join(NativeJoinCode, deviceName = null) }
                assertTrue("Expected public UnknownCode; unexpected answers are never retried",
                    result.exceptionOrNull() === JoinFailure.UnknownCode)
            }
            assertTrue("The probe must not create a membership", repository.currentAll().isEmpty())
            requireEmptyDiary(diary)
            Log.i("LessonsNativeCheck", "Minified Connect capabilities, SignInRequired and UnknownCode; source=$expected")
        } finally {
            withContext(NonCancellable) {
                withTimeout(RestoreBudgetMillis) { settings.update { it.copy(baseUrl = original) } }
            }
        }
    }

    private suspend fun checkDiaryBindings(diary: DiaryRepository) {
        val preflight = withTimeout(CallBudgetMillis) {
            Graph.container.diarySignIn.preflight(DiaryTarget.petersburg("native-preflight"))
        }
        assertTrue("Production must advertise Petersburg before any password is entered", preflight.isSuccess)
        requireEmptyDiary(diary)
        val students = withTimeout(CallBudgetMillis) { diary.students() }
        assertTrue("The unauthenticated diary read must decode SignInRequired",
            students.exceptionOrNull() is DiaryFailure.SignInRequired)
        requireEmptyDiary(diary)
    }

    private suspend fun requireEmptyDiary(diary: DiaryRepository) {
        assertTrue("The native fixture must have no diary bearer", diary.current() == null)
        assertTrue("The native fixture must have no diary target", diary.target.first() == null)
    }

    private companion object {
        const val NativeJoinCode = "!5A!"
        // The production call timeout is sixty seconds; restoration has a separate local-write budget.
        const val CallBudgetMillis = 75_000L
        const val ProbeBudgetMillis = 150_000L
        const val RestoreBudgetMillis = 5_000L
        const val TotalBudgetMillis = 165_000L
    }
}
