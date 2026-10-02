package com.lumenpearson.lessons.ui.developer

import com.lumenpearson.lessons.core.data.developer.CheckKind
import com.lumenpearson.lessons.core.data.developer.CheckOutcome
import com.lumenpearson.lessons.core.data.developer.CheckResult
import com.lumenpearson.lessons.core.data.developer.DeveloperAccess
import com.lumenpearson.lessons.core.data.developer.DeveloperRole
import com.lumenpearson.lessons.core.data.developer.DeveloperState
import com.lumenpearson.lessons.core.data.developer.DeveloperTool
import com.lumenpearson.lessons.core.data.diagnostics.ActivityEntry
import com.lumenpearson.lessons.core.data.diagnostics.ActivityKind
import com.lumenpearson.lessons.core.data.diagnostics.NetworkEntry
import com.lumenpearson.lessons.core.data.diagnostics.NetworkSource
import java.time.ZoneOffset
import org.junit.Assert.assertTrue
import org.junit.Test

/** The developer page as one text (#237): everything on it, in order, and nothing more. */
class DeveloperReportTest {

    private val zone = ZoneOffset.UTC

    private val granted = DeveloperUiState(
        mode = DeveloperState(
            revealed = true,
            access = DeveloperAccess.Granted("dev", DeveloperRole.ADMIN, 0L),
            chosen = setOf(DeveloperTool.NETWORK_LOG),
        ),
        checks = ChecksState.Done(
            listOf(
                CheckResult(
                    kind = CheckKind.DIARY_HOST,
                    outcome = CheckOutcome.FAIL,
                    detail = "Unavailable (SocketTimeoutException)",
                    tookMillis = 20_004,
                    subject = "dnevnik2.petersburgedu.ru",
                ),
                CheckResult(CheckKind.SERVER, CheckOutcome.PASS, "api 1 · schema 0017", 312),
            ),
            atMillis = 0L,
        ),
    )

    private val stuck = NetworkEntry(
        id = 1,
        source = NetworkSource.DIARY,
        method = "POST",
        host = "dnevnik2.petersburgedu.ru",
        path = "/api/user/auth/login",
        startedAtMillis = 0L,
    )

    private val answered = NetworkEntry(
        id = 2,
        source = NetworkSource.SERVER,
        method = "GET",
        host = "lessons.example",
        path = "/api/v1/diary/capabilities",
        startedAtMillis = 1_000L,
        tookMillis = 312,
        status = 200,
        headers = listOf("X-Vercel-Id" to "fra1::abc"),
    )

    @Test
    fun `the report carries the access, the tools, every check and both records`() {
        val text = DeveloperReport.text(
            state = granted,
            network = listOf(stuck, answered),
            activity = listOf(ActivityEntry(1, 0L, ActivityKind.SIGN_IN, "diary PETERSBURG: Timeout in 25004 ms")),
            nowMillis = 73_000L,
            zone = zone,
        )

        val expected = listOf(
            "access: dev, admin",
            "tools: network_log",
            "[FAIL] diary_host dnevnik2.petersburgedu.ru — Unavailable (SocketTimeoutException) (20004 ms)",
            "[PASS] server — api 1 · schema 0017 (312 ms)",
            "diary  POST dnevnik2.petersburgedu.ru/api/user/auth/login → in flight for 73 s",
            "GET lessons.example/api/v1/diary/capabilities → 200 in 312 ms  X-Vercel-Id: fra1::abc",
            "sign_in  diary PETERSBURG: Timeout in 25004 ms",
        )
        for (line in expected) assertTrue("$line\n---\n$text", text.contains(line))
    }

    @Test
    fun `checks that were never run say so rather than nothing`() {
        val text = DeveloperReport.text(DeveloperUiState(), emptyList(), emptyList(), 0L, zone)

        assertTrue(text, text.contains("== checks\nnot run"))
        assertTrue(text, text.contains("access: signed out of GitHub"))
        assertTrue(text, text.contains("tools: none"))
    }
}
