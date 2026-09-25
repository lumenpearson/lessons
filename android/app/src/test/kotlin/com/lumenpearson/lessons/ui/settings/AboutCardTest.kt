package com.lumenpearson.lessons.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lumenpearson.lessons.core.data.repository.ServerStatus
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «О приложении», drawn.
 *
 * Written after the same card shipped with its two link buttons squeezed to
 * three words a line — it was padded twice, once by the settings list and once
 * by itself, so it was 32 dp narrower on each side than every group above it
 * and «Essentials» came out as «Essent / ials». Nothing about that is visible
 * from a test that only asks whether the card was built, which is the lesson
 * the day ribbon taught this project one batch earlier.
 *
 * What the badges have to get right is that they say the *right* thing about a
 * server nobody can see from here: «не отвечает» and «база и код разошлись» are
 * different situations with different answers, and reporting either as the
 * other is worse than reporting neither.
 */
@RunWith(RobolectricTestRunner::class)
// Phone width, and a window tall enough to hold the whole card.
//
// The width is the half that matters: every defect this test exists for is a
// width one — buttons squeezed to three words a line by a card that was padded
// twice. The height is scaffolding. `performScrollTo` was the obvious way to
// reach the bottom of the card and it does not work here: it drives the
// scrollable's own animation, and the clock is held for the marquees, so
// nothing moves and every node below the fold reports «not displayed» — which
// looks exactly like the element being missing.
@Config(qualifiers = "ru-rRU-w411dp-h3000dp")
class AboutCardTest {

    @get:Rule val compose = createComposeRule()

    /**
     * The card carries marquee lines, which never stop scrolling.
     *
     * A perpetual animation means Compose's clock is never idle, so every
     * assertion below would wait for an idleness that cannot arrive. The frames
     * are advanced by hand instead — see `MarqueeTextTest`, which pays for the
     * same thing for the same reason.
     */
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    /** A build that was told nothing about its own checkout — a local one. */
    private val local = BuildProvenance(
        repository = "",
        ref = "",
        commit = "",
        number = "",
        builtAt = "",
    )

    /** And one the workflow told everything. */
    private val fromCi = BuildProvenance(
        repository = "lumenpearson/lessons",
        ref = "main",
        commit = "9b8eba790ffdd6ab7b5e4acf7f6c4e30538d2477",
        number = "26",
        builtAt = "2026-09-21T23:59Z",
    )

    /**
     * The card at the width the settings list gives it, and nothing else.
     *
     * No `padding` around it on purpose: the list already insets every item by
     * `ScreenPadding`, and the defect this was written after was the card
     * adding a second one and coming out 32 dp narrower on each side than every
     * group above it.
     *
     * **The provenance is passed in, never left to `BuildConfig`.** The first
     * version of this file read it from the build and asserted «Собрано
     * вручную», which is not a fact about the card at all — it is a fact about
     * how the build that compiled the test was configured. It passed under
     * `ci.yml`, which sets none of the five properties, and failed under
     * `apk.yml`, which sets them all: the one workflow whose entire job is to
     * produce an APK could no longer produce one, and the failure named a
     * string on a settings page.
     */
    private fun show(status: ServerStatus, build: BuildProvenance = local) {
        compose.setContent {
            LessonsTheme {
                AboutCard(serverStatus = status, build = build)
            }
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
    }

    /** On screen, not merely composed — which is the whole point here. */
    private fun assertShows(text: String) {
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun `both links are on the card, with their whole labels`() {
        show(ServerStatus.Ok(apiVersion = 1, schema = "0014"))

        // The labels in full. «Essentials» broken into «Essent / ials» is still
        // two nodes with the same text to a matcher that searches substrings,
        // so these are exact — which is what makes the assertion mean the
        // button was wide enough rather than merely present.
        assertShows("Исходный код")
        assertShows("Essentials")
    }

    @Test
    fun `the terms and the privacy policy each have a row of their own`() {
        // The full-size way to the two documents the first screen's small line
        // links, for anybody who wants to read them again after onboarding.
        show(ServerStatus.Ok(apiVersion = 1, schema = "0014"))

        assertShows("Условия использования")
        assertShows("Политика конфиденциальности")
    }

    @Test
    fun `the design credit keeps the space before its link`() {
        // #155: the words before the link were their own string, aapt2 trimmed
        // its trailing space, and the card said «по мотивамEssentials».
        show(ServerStatus.Ok(apiVersion = 1, schema = "0014"))

        assertShows("Оформление, шрифт и компоненты — по мотивам Essentials, лицензия MIT.")
    }

    @Test
    fun `a healthy server says so, and names what it is`() {
        show(ServerStatus.Ok(apiVersion = 1, schema = "0014"))

        assertShows("Сервер на связи")
        assertShows("API v1")
        assertShows("Схема 0014")
    }

    @Test
    fun `a server whose database is behind its code is not reported as healthy`() {
        // The state nothing else in the app can see: `/health` is green
        // throughout it, and what fails is every read of whatever gained a
        // column. It is the deployment failure this project has actually had.
        show(ServerStatus.Degraded(schema = "0013", expected = "0014", detail = "База отстала"))

        assertShows("Сервер: база и код разошлись")
        assertShows("Схема 0013")
        // Both numbers, because one of them is not an answer. «Схема 0013»
        // alone draws «база отстала» and «база впереди кода» identically, and
        // they are opposite mistakes: one waits for a migration to be applied,
        // the other for a deploy to catch up. `detail` says which in the
        // server's own Russian, which is no use on a card read in two
        // languages — the pair of revisions is.
        assertShows("Ожидается 0014")
    }

    // Two tests rather than one call each way: `ComposeTestRule` refuses a
    // second `setContent`, and the pair is what makes «не отвечает» and «адреса
    // нет» different sentences rather than one apology.
    @Test
    fun `a server that did not answer says so`() {
        show(ServerStatus.Unreachable)

        assertShows("Сервер не отвечает")
    }

    @Test
    fun `a server nobody configured is a different sentence`() {
        show(ServerStatus.NotConfigured)

        assertShows("Адрес сервера не задан")
    }

    @Test
    fun `a build that was told nothing about itself says that rather than nothing`() {
        // What a fresh clone with no configuration produces. A page with no
        // build chips at all reads as one that forgot to draw them.
        show(ServerStatus.Checking, build = local)

        assertShows("Собрано вручную")
        assertShows("Проверяю сервер…")
    }

    @Test
    fun `a build the workflow told everything names where it came from`() {
        show(ServerStatus.Checking, build = fromCi)

        assertShows("lumenpearson/lessons")
        assertShows("Ветка main")
        // Seven characters, not forty: the form a person compares against a
        // pull request. The full sha is what the link uses.
        assertShows("Коммит 9b8eba7")
        assertShows("Сборка №26")
    }

    @Test
    fun `a build that knows where it came from does not also claim to be local`() {
        // The two are opposites and both are chips, so drawing both would be a
        // page contradicting itself in one row.
        show(ServerStatus.Checking, build = fromCi)

        compose.onAllNodesWithText("Собрано вручную").assertCountEquals(0)
    }

    @Test
    fun `the facts are on the card`() {
        show(ServerStatus.Checking)

        assertShows("Любопытное")
    }
}
