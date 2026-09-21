package com.lumenpearson.lessons

import android.content.ComponentName
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The one manifest attribute the whole app's screen state rests on.
 *
 * `MainActivity` declares `android:configChanges` for every configuration this
 * app can meet in normal use, so Android hands it the change instead of
 * restarting it. That is what makes `remember` enough almost everywhere here:
 * the half-typed correction in «✏️ Исправить», the strings correction mode has
 * open, the sheet somebody is filling in — none of them is a `rememberSaveable`
 * with a `Saver`, and none of them needs to be, because a rotation does not
 * tear the composition down.
 *
 * Drop one flag from that attribute and nothing fails to build, no test that
 * exists breaks, and the app quietly starts losing what people type whenever
 * they turn the phone — or change the font size, or the theme, or the language.
 * An audit read those screens, called them rotation defects, and was wrong for
 * exactly this reason; the attribute is what makes it wrong, so the attribute
 * is what is held here.
 *
 * It says nothing about process death. Android can still kill the process
 * behind a backgrounded activity, and everything above is lost then. That is a
 * different promise, and this app does not make it.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityConfigChangesTest {

    @Test
    fun `the main activity handles every configuration change itself`() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )

        // Named rather than spelled as a number: the manifest lists them by
        // name too, so a reader can put the two side by side.
        val required = mapOf(
            "orientation" to ActivityInfo.CONFIG_ORIENTATION,
            "screenSize" to ActivityInfo.CONFIG_SCREEN_SIZE,
            "screenLayout" to ActivityInfo.CONFIG_SCREEN_LAYOUT,
            "smallestScreenSize" to ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
            "keyboardHidden" to ActivityInfo.CONFIG_KEYBOARD_HIDDEN,
            "uiMode" to ActivityInfo.CONFIG_UI_MODE,
            "density" to ActivityInfo.CONFIG_DENSITY,
            "fontScale" to ActivityInfo.CONFIG_FONT_SCALE,
            "locale" to ActivityInfo.CONFIG_LOCALE,
            "layoutDirection" to ActivityInfo.CONFIG_LAYOUT_DIRECTION,
        )

        val missing = required.filterValues { info.configChanges and it == 0 }.keys
        assertEquals(
            "MainActivity no longer handles these configuration changes itself, " +
                "so Android will restart it and every remembered screen state is lost",
            emptySet<String>(),
            missing,
        )
    }
}
