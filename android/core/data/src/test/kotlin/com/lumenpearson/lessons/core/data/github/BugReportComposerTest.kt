package com.lumenpearson.lessons.core.data.github

import com.lumenpearson.lessons.core.data.repository.IssueDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first line of a bug report becomes the issue title, and the issue is
 * public. Whatever this cuts, it must not produce a character that is not one.
 */
class BugReportComposerTest {

    /** One under the 72-character limit, so the ellipsis fits inside it. */
    private val cut = 71

    @Test
    fun `a short line is left exactly as it is`() {
        val line = "Виджет показывает вчерашний день"

        assertEquals(line, BugReportComposer.shorten(line))
    }

    /**
     * `take` counts UTF-16 code units, not characters, so a cut landing between
     * the halves of a surrogate pair left the leading half in the title — which
     * is not a character at all, and renders as a replacement box wherever
     * GitHub lists the issue.
     */
    @Test
    fun `a cut through an emoji takes the whole emoji, not half of it`() {
        // The pair straddles the boundary: its high half is the last unit kept.
        val line = "а".repeat(cut - 1) + "😀" + "а".repeat(20)

        val title = BugReportComposer.shorten(line)

        assertFalse("a lone high surrogate survived the cut", title.any { it.isHighSurrogate() })
        assertEquals("а".repeat(cut - 1) + "…", title)
    }

    /** The pair sitting wholly inside the budget is kept, not defensively dropped. */
    @Test
    fun `an emoji that fits is kept`() {
        val line = "а".repeat(cut - 2) + "😀" + "а".repeat(20)

        val title = BugReportComposer.shorten(line)

        assertTrue(title.startsWith("а".repeat(cut - 2) + "😀"))
        assertEquals("а".repeat(cut - 2) + "😀" + "…", title)
    }

    @Test
    fun `a long plain line is cut to the limit and ellipsised`() {
        val title = BugReportComposer.shorten("а".repeat(200))

        assertEquals("а".repeat(cut) + "…", title)
    }

    /** Trailing space before the ellipsis reads as a typo, not as a truncation. */
    @Test
    fun `the space a cut lands on is trimmed away`() {
        val line = "а".repeat(cut - 1) + " " + "б".repeat(20)

        assertEquals("а".repeat(cut - 1) + "…", BugReportComposer.shorten(line))
    }

    /** A fixed device, so the body does not depend on the JVM's empty `Build`. */
    private val device = listOf("Model" to "Pixel 8", "AppVersionName" to "0.9.0")

    /**
     * Both ways a report reaches GitHub carry one mark in the body (#458).
     *
     * The `from-app` label was never it: GitHub drops a label from
     * `POST /issues` without a word for anybody who cannot push here, which
     * is every reader, and the browser's new-issue page was never sent one.
     * So every app report arrived with no label at all, outside every filter
     * the board and the release walk read. A line of the body is the one thing
     * neither path can lose, and full-text search finds it.
     */
    @Test
    fun `the report ends with the mark that says it came from the app`() {
        val withContact = BugReportComposer.compose("Виджет пуст", "me@example.com", "0.9.0", device)
        val withoutContact = BugReportComposer.compose("Виджет пуст", null, "0.9.0", device)

        val mark = "_Отправлено из приложения «Дневник» 0.9.0_"
        assertEquals(mark, withContact.body.lines().last())
        assertEquals(mark, withoutContact.body.lines().last())
        assertTrue(withContact.body.contains("Контакт: me@example.com"))
    }

    @Test
    fun `the mark is the same words in every report, so one search finds them all`() {
        val older = BugReportComposer.compose("", null, "0.8.0", device)
        val newer = BugReportComposer.compose("Что-то", null, "1.0.0-rc.1", device)

        assertTrue(older.body.lines().last().startsWith("_" + BugReportComposer.APP_MARK + " "))
        assertTrue(newer.body.lines().last().startsWith("_" + BugReportComposer.APP_MARK + " "))
        assertEquals("Отправлено из приложения «Дневник»", BugReportComposer.APP_MARK)
    }

    @Test
    fun `the browser path carries the mark too`() {
        val draft = BugReportComposer.compose("Виджет пуст", null, "0.9.0", device)

        val url = GithubProject.UPSTREAM.newIssueUrl(draft.title, draft.body)

        val body = java.net.URLDecoder.decode(url.substringAfter("&body="), Charsets.UTF_8.name())
        assertTrue(body, body.endsWith("_Отправлено из приложения «Дневник» 0.9.0_"))
        assertFalse(url, url.contains("labels="))
    }

    @Test
    fun `the signed-in path asks for no label, which GitHub would drop anyway`() {
        val payload = GithubIssues.requestBody(IssueDraft(title = "t", body = "b"))

        assertFalse(payload, payload.contains("labels"))
        assertFalse(payload, payload.contains("from-app"))
        assertEquals("""{"title":"t","body":"b"}""", payload)
    }
}
