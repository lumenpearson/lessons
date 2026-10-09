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
import com.lumenpearson.lessons.appicon.AppIconCatalog
import com.lumenpearson.lessons.appicon.AppIconStyle
import com.lumenpearson.lessons.appicon.AppIconVariant
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
 */
// marquee clock: it reaches the groups with `performScrollTo`, which holding the
// clock would stop. The longest row title it composes is «Значок приложения»,
// seventeen characters across a 411 dp row with nothing beside it.
@RunWith(RobolectricTestRunner::class)
// Russian, the source, for the reason `ClassRowsScreenTest` gives. The added
// height qualifier is this file's own: with `w411dp` alone the root measures
// 470 dp tall (confirmed by probing the semantics tree directly), so the
// LazyColumn only ever composes the preview and the first two groups — not
// because anything is broken, but because `performScrollTo` on a text match
// requires that node to already exist, and a Lazy list never composes an item
// it has not been scrolled near. Eight groups and the button need a window
// tall enough to compose the whole page up front.
@Config(qualifiers = "ru-rRU-w411dp-h3000dp")
class AppIconRowsTest {

    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val default = AppIconCatalog.default
    private val other = AppIconCatalog.variants.first { it.style != default.style }

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
        variants: List<AppIconVariant> = AppIconCatalog.variants,
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

        for (style in AppIconCatalog.variants.map { it.style }.distinct()) {
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
}
