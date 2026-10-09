package com.lumenpearson.lessons.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.appicon.AliasChange
import com.lumenpearson.lessons.appicon.AppIconCatalog
import com.lumenpearson.lessons.appicon.AppIconStore
import com.lumenpearson.lessons.appicon.AppIconVariant
import com.lumenpearson.lessons.appicon.AppIcons
import com.lumenpearson.lessons.appicon.FakeLauncherComponents
import com.lumenpearson.lessons.appicon.LauncherAliases
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * «Значок приложения» end to end: the page as the app opens it, with its view
 * model from the factory the app uses and the store from `AppIcons`, over a
 * fake launcher. `AppIconRowsTest` hands the rows their state and callbacks;
 * this is the one test that a tile and «Применить» reach the launcher at all.
 *
 * Over the real catalog, because the page draws the real catalog.
 */
// marquee clock: the page's one-line texts are its title «Значок
// приложения» and the style names, the longest «Матовое стекло», none near
// the width of a 411 dp page; this test reaches nodes with `performScrollTo`,
// which holding the clock would stop.
@RunWith(RobolectricTestRunner::class)
// Russian and tall, for the reasons `AppIconRowsTest` gives.
@Config(qualifiers = "ru-rRU-w411dp-h3000dp")
class AppIconScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val default = AppIconCatalog.default

    @After
    fun forgetTheStore() = AppIcons.override(null)

    private fun label(variant: AppIconVariant): String = context.getString(
        R.string.app_icon_variant,
        context.getString(variant.style.labelRes),
        context.getString(variant.palette.labelRes),
    )

    @Test
    fun `a tile and «Применить» switch the launcher once, and the button rests again`() {
        // With only the default kept, the page has no other tile to press.
        val target = AppIconCatalog.variants.firstOrNull { it != default }
        assumeTrue("only the default is left, so there is no other tile to press", target != null)
        val chosen = checkNotNull(target)
        val launcher = FakeLauncherComponents(variants = AppIconCatalog.variants, default = default)
        AppIcons.override(
            AppIconStore(
                LauncherAliases(launcher, AppIconCatalog.variants, default),
                io = Dispatchers.Unconfined,
                scope = CoroutineScope(Dispatchers.Unconfined),
            ),
        )
        compose.setContent { LessonsTheme { AppIconScreen() } }

        compose.onNodeWithContentDescription(label(chosen)).performScrollTo().performClick()
        compose.onNodeWithText("Применить").performScrollTo().assertIsEnabled().performClick()

        compose.onNodeWithText("Применить").assertIsNotEnabled()
        assertEquals(
            listOf(listOf(AliasChange(chosen.alias, enabled = true), AliasChange(default.alias, enabled = false))),
            launcher.calls,
        )
    }
}
