package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.appicon.AppIconStyle
import com.lumenpearson.lessons.appicon.AppIconVariant
import com.lumenpearson.lessons.appicon.TestCatalog
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * «Значок приложения», composed and pressed: a tile selects, only «Применить»
 * applies, and a trimmed catalog draws no empty group.
 *
 * The rows are handed [TestCatalog] rather than the real catalog: these tests
 * need a second style and a style with five palettes, and the owner may keep
 * neither.
 */
// marquee clock: «Значок приложения», the longest row title at seventeen
// characters, does not overflow a 411 dp row even beside its icon and
// chevron; this test reaches nodes with `performScrollTo`, which holding
// the clock would stop.
@RunWith(RobolectricTestRunner::class)
// Russian, the source, for the reason `ClassRowsScreenTest` gives. The
// height qualifier is this file's own: eight groups, the preview and the
// button need a window taller than this test's own default, because a
// `LazyColumn` composes only what its measured viewport already reaches.
@Config(qualifiers = "ru-rRU-w411dp-h3000dp")
class AppIconRowsTest {

    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val default = TestCatalog.default
    private val other = TestCatalog.other

    private fun label(variant: AppIconVariant): String = context.getString(
        R.string.app_icon_variant,
        context.getString(variant.style.labelRes),
        context.getString(variant.palette.labelRes),
    )

    private fun show(
        state: AppIconUiState = AppIconUiState(current = default),
        selected: AppIconVariant = state.current,
        onSelect: (AppIconVariant) -> Unit = {},
        onApply: () -> Unit = {},
        variants: List<AppIconVariant> = TestCatalog.variants,
    ) = compose.setContent {
        LessonsTheme {
            LazyColumn {
                appIconRows(state, selected, onSelect, onApply, variants)
            }
        }
    }

    @Test
    fun `every style in the catalog is a group of its own`() {
        show()

        for (style in TestCatalog.variants.map { it.style }.distinct()) {
            compose.onNodeWithText(context.getString(style.labelRes)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `a tile selects its icon and applies nothing`() {
        var picked: AppIconVariant? = null
        var applied = false
        show(onSelect = { picked = it }, onApply = { applied = true })

        compose.onNodeWithContentDescription(label(other)).performScrollTo().performClick()

        assertEquals(other, picked)
        assertFalse(applied)
    }

    @Test
    fun `the chosen tile is announced as chosen and the others are not`() {
        show(selected = other)

        compose.onNodeWithContentDescription(label(other)).performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription(label(default)).performScrollTo().assertIsNotSelected()
    }

    @Test
    fun `apply waits for an icon other than the one in use`() {
        show()

        compose.onNodeWithText("Применить").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `apply applies the selection, once`() {
        var applied = 0
        show(selected = other, onApply = { applied++ })

        compose.onNodeWithText("Применить").performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, applied)
    }

    @Test
    fun `apply is held while a switch is running`() {
        show(state = AppIconUiState(current = default, applying = true), selected = other)

        compose.onNodeWithText("Применить").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a trimmed catalog draws only the styles it still has`() {
        show(variants = listOf(default, other))

        compose.onNodeWithText(context.getString(default.style.labelRes)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(other.style.labelRes)).performScrollTo().assertIsDisplayed()
        val gone = AppIconStyle.entries.first { it != default.style && it != other.style }
        compose.onNodeWithText(context.getString(gone.labelRes)).assertDoesNotExist()
    }

    @Test
    fun `the row on «Оформление» names the icon in use and opens the page`() {
        var opened = false
        compose.setContent { LessonsTheme { AppIconLinkRow(current = other, onOpen = { opened = true }) } }

        compose.onNodeWithText(label(other)).assertIsDisplayed()
        compose.onNodeWithText("Значок приложения").performClick()

        assertTrue(opened)
    }

    /**
     * One style trimmed to five palettes draws a full row of four and a short
     * row of one: the fifth tile is the first of that short row, and
     * `AppIconTiles`' trailing `Spacer`s are what keeps it under the first
     * tile of the row above rather than centred in a row of its own.
     */
    @Test
    fun `a short last row of tiles keeps the columns of the full row above it`() {
        val trimmed = TestCatalog.variants.filter { it.style == default.style }.take(TrimmedPaletteCount)
        // Fewer would put the first and the last tile in one row, or make them one tile.
        check(trimmed.size == TrimmedPaletteCount) { "the test catalog has ${trimmed.size} palettes in one style" }
        val first = trimmed.first()
        val fifth = trimmed.last()
        show(variants = trimmed)

        val firstLeft = compose.onNodeWithContentDescription(label(first)).performScrollTo()
            .fetchSemanticsNode().boundsInRoot.left
        val fifthLeft = compose.onNodeWithContentDescription(label(fifth)).performScrollTo()
            .fetchSemanticsNode().boundsInRoot.left

        assertEquals(firstLeft, fifthLeft, BoundsTolerance)
    }
}

/** One more palette than a full row, so the trimmed catalog's second row holds exactly one tile. */
private const val TrimmedPaletteCount = 5

/** Slack for a float pixel bound, not a real difference this test is looking for. */
private const val BoundsTolerance = 0.5f
