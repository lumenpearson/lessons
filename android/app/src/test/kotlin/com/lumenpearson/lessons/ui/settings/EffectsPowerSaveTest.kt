package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.designsystem.modifier.LocalPowerSaveMode
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/**
 * «Эффекты» under Android's battery saver (#476): each row says what is drawn.
 *
 * Battery saver switches the heavy effects off without touching what the user
 * chose. The rows used to read the stored choice alone, so «Размытие под
 * шторкой» read on while no blur was drawn and nothing said why — which is how
 * #444 was filed as a bug by the person who knows the app best.
 *
 * Three of the rows switch a whole effect, and under battery saver they read
 * off, cannot be pressed and say why. The fourth, «Размытие под шторкой», also
 * switches the gradient wash at the edges, which is a `drawRect` and is drawn
 * under battery saver as it is below Android 13: that row keeps the stored
 * choice and stays pressable, because pressing it still changes what is drawn,
 * and says the blur is gone. `EdgeFadeRow`'s own KDoc is why — a switch that
 * reads «выключено» over something it still draws is the same defect inverted.
 *
 * Battery saver comes from [LocalPowerSaveMode], provided here by hand: that is
 * the fake source. Every setting is stored on throughout, and every press is
 * recorded, so «the stored settings are never written» is a list that stays
 * empty.
 */
// marquee clock: the four titles are at most twenty characters across 411 dp,
// and a subtitle wraps rather than scrolls; the window is tall so the whole
// group is laid out without a scroll.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp-h1600dp")
class EffectsPowerSaveTest {

    @get:Rule val compose = createComposeRule()

    private var saving by mutableStateOf(true)

    /** Every call any row made towards the stored settings, in order. */
    private val written = mutableListOf<String>()

    private val allOn = AppSettings(
        edgeBlur = true,
        motionBlur = true,
        rippleEffects = true,
        themeReveal = true,
    )

    private fun show(settings: AppSettings = allOn) = compose.setContent {
        CompositionLocalProvider(LocalPowerSaveMode provides saving) {
            LessonsTheme {
                Column {
                    EffectsGroup(
                        settings = settings,
                        onEdgeBlur = { written += "edgeBlur=$it" },
                        onMotionBlur = { written += "motionBlur=$it" },
                        onMotionBlurScale = { written += "motionBlurScale=$it" },
                        onRipple = { written += "ripple=$it" },
                        onThemeReveal = { written += "themeReveal=$it" },
                    )
                }
            }
        }
    }

    private fun batterySaver(on: Boolean) {
        saving = on
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test
    fun `under battery saver the three effect rows read off and cannot be pressed`() {
        saving = true
        show()

        for (title in listOf(MOTION_BLUR, RIPPLE, THEME_REVEAL)) {
            compose.onNodeWithText(title).assertIsOff().assertIsNotEnabled().performClick()
        }

        assertEquals(emptyList<String>(), written)
    }

    @Test
    fun `under battery saver each of the three says why`() {
        saving = true
        show()

        compose.onAllNodesWithText(POWER_SAVE_LINE).assertCountEquals(3)
    }

    /** The strength of a blur that is not drawn is a question with no answer. */
    @Test
    fun `under battery saver the motion blur strength is not offered`() {
        saving = true
        show()

        compose.onAllNodesWithText(MOTION_BLUR_AMOUNT, substring = true).assertCountEquals(0)
    }

    @Test
    fun `under battery saver the edge row says the blur is gone and still switches the wash`() {
        saving = true
        show()

        compose.onNodeWithText(EDGE).assertIsOn().assertIsEnabled()
        compose.onNodeWithText(EDGE_POWER_SAVE_LINE).assertExists()

        compose.onNodeWithText(EDGE).performClick()

        assertEquals(listOf("edgeBlur=false"), written)
    }

    @Test
    fun `when battery saver ends every row is back as it was set, and nothing was written`() {
        saving = true
        show()

        batterySaver(false)

        for (title in listOf(EDGE, MOTION_BLUR, RIPPLE, THEME_REVEAL)) {
            compose.onNodeWithText(title).assertIsOn().assertIsEnabled()
        }
        compose.onAllNodesWithText(POWER_SAVE_LINE).assertCountEquals(0)
        compose.onAllNodesWithText(EDGE_POWER_SAVE_LINE).assertCountEquals(0)
        compose.onAllNodesWithText(MOTION_BLUR_AMOUNT, substring = true).assertCountEquals(1)
        assertEquals(emptyList<String>(), written)
    }

    /** And the other way: switched on while the page is open, which is when it happens. */
    @Test
    fun `battery saver switched on while the page is open is shown at once`() {
        saving = false
        show()
        compose.onNodeWithText(RIPPLE).assertIsOn()

        batterySaver(true)

        compose.onNodeWithText(RIPPLE).assertIsOff().assertIsNotEnabled()
        compose.onAllNodesWithText(POWER_SAVE_LINE).assertCountEquals(3)
        assertEquals(emptyList<String>(), written)
    }

    /** Without battery saver, what is off by choice reads off by choice, with no line about it. */
    @Test
    fun `without battery saver a row switched off by the user carries no battery-saver line`() {
        saving = false
        show(allOn.copy(themeReveal = false))

        compose.onNodeWithText(THEME_REVEAL).assertIsOff().assertIsEnabled()
        compose.onAllNodesWithText(POWER_SAVE_LINE).assertCountEquals(0)
    }

    /**
     * Under battery saver a row the user had switched off carries the line too.
     * That is the behaviour wanted, not an oversight: the row cannot be pressed
     * then, and a switch that refuses to move needs its reason beside it whatever
     * it was set to. «Выключено» is true either way, and nothing is written.
     */
    @Test
    fun `under battery saver a row switched off by the user says why it cannot be pressed`() {
        saving = true
        show(allOn.copy(themeReveal = false))

        compose.onNodeWithText(THEME_REVEAL).assertIsOff().assertIsNotEnabled().performClick()
        compose.onAllNodesWithText(POWER_SAVE_LINE).assertCountEquals(3)
        assertEquals(emptyList<String>(), written)
    }

    /**
     * The Samsung builds `isBlurProblematicDevice` covers get no blur at all,
     * battery saver or not, and the row used to say nothing about it. The line
     * is the device's, not battery saver's: saying «пока включён режим
     * энергосбережения» there would promise the blur back when it never comes.
     */
    @Test
    fun `on a phone whose blur is switched off for flickering the edge row says so`() {
        ShadowBuild.setManufacturer("samsung")
        saving = true
        show()

        compose.onNodeWithText(EDGE_PROBLEMATIC_LINE).assertExists()
        compose.onAllNodesWithText(EDGE_POWER_SAVE_LINE).assertCountEquals(0)
        compose.onNodeWithText(EDGE).assertIsOn().assertIsEnabled()
    }

    /**
     * Both edge lines end «содержимое всё равно мягко тает у краёв», and that is
     * only true while the switch is on: the shell draws the wash from the same
     * stored choice. Switched off, there is no wash to promise, so the row says
     * what it would turn on, as it does anywhere else.
     */
    @Test
    fun `switched off, the edge row promises no fade under battery saver`() {
        ShadowBuild.setManufacturer("Google")
        saving = true
        show(allOn.copy(edgeBlur = false))

        compose.onNodeWithText(EDGE).assertIsOff().assertIsEnabled()
        compose.onNodeWithText(EDGE_DESCRIPTION).assertExists()
        compose.onAllNodesWithText(EDGE_POWER_SAVE_LINE).assertCountEquals(0)
    }

    /** The same on the Samsung builds whose blur is switched off for flickering. */
    @Test
    fun `switched off, the edge row promises no fade on a flickering phone either`() {
        ShadowBuild.setManufacturer("samsung")
        saving = false
        show(allOn.copy(edgeBlur = false))

        compose.onNodeWithText(EDGE).assertIsOff().assertIsEnabled()
        compose.onNodeWithText(EDGE_DESCRIPTION).assertExists()
        compose.onAllNodesWithText(EDGE_PROBLEMATIC_LINE).assertCountEquals(0)
    }

    @Test
    fun `elsewhere the edge row carries its own description`() {
        ShadowBuild.setManufacturer("Google")
        saving = false
        show()

        compose.onNodeWithText(EDGE_DESCRIPTION).assertExists()
        compose.onAllNodesWithText(EDGE_PROBLEMATIC_LINE).assertCountEquals(0)
    }

    private companion object {
        // Quoted from `values/`, so that a rename shows up here as a failure.
        const val EDGE = "Размытие под шторкой"
        const val EDGE_DESCRIPTION = "Содержимое плавно тает под строкой состояния."
        const val MOTION_BLUR = "Размытие при прокрутке"
        /** The slider's title carries its value after a colon, so this is matched as a part. */
        const val MOTION_BLUR_AMOUNT = "Сила размытия"
        const val RIPPLE = "Волна"
        const val THEME_REVEAL = "Круговая смена темы"
        const val POWER_SAVE_LINE = "Выключено, пока включён режим энергосбережения"
        const val EDGE_POWER_SAVE_LINE =
            "Размытия нет, пока включён режим энергосбережения, — содержимое всё равно мягко тает у краёв."
        const val EDGE_PROBLEMATIC_LINE =
            "На этом телефоне размытие заставляет экран мерцать, поэтому его нет — " +
                "содержимое всё равно мягко тает у краёв."
    }
}
