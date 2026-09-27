package com.lumenpearson.lessons.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.ShellState
import com.lumenpearson.lessons.core.designsystem.component.LessonsLoadingIndicator
import com.lumenpearson.lessons.core.designsystem.modifier.LiquidRippleState
import com.lumenpearson.lessons.core.designsystem.modifier.LocalLiquidRipple
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollBlur
import com.lumenpearson.lessons.core.designsystem.theme.MotionSettings
import com.lumenpearson.lessons.core.designsystem.theme.ScrollBlurSettings
import com.lumenpearson.lessons.ui.onboarding.OnboardingScreen
import java.time.LocalDate

/**
 * The whole app below the theme.
 *
 * The signed-in part is a pager rather than a navigation graph, which is how
 * [Essentials](https://github.com/sameerasw/essentials) builds its own shell:
 * the three destinations are peers, they keep their scroll position, and the
 * gesture between them is a swipe. A graph would give the same three screens
 * without the swipe, and the swipe is half of what the floating toolbar is for.
 *
 * @param shell the stored mode and hold, `null` while they are still being
 *   read — the splash is shown for that moment rather than guessing a
 *   destination and then yanking the user somewhere else a frame later.
 *   [rootScreen] decides between the way in and a home, and [shellHome] which
 *   home.
 * @param openDate a day the widget asked for; the shell moves to the calendar
 *   and selects it, then calls [onDateOpened] so the request is acted on once.
 */
@Composable
fun LessonsApp(
    shell: ShellState?,
    settings: AppSettings,
    modifier: Modifier = Modifier,
    openDate: LocalDate? = null,
    onDateOpened: () -> Unit = {},
) {
    // Published here rather than inside the signed-in shell, which is where it
    // used to live: the first-run steps and the join screen slide too, and a
    // local provided below them left those transitions permanently unblurred
    // however the setting was set.
    // The one wave for the whole window. Deliberately not saved across
    // configuration changes — a ripple restored on rotation would be a wave
    // from nowhere. Provided here, above every screen, so a theme switch three
    // pages down can fire it from its own row.
    val ripple = remember { LiquidRippleState() }

    CompositionLocalProvider(
        LocalScrollBlur provides ScrollBlurSettings(
            enabled = settings.motionBlur,
            scale = settings.motionBlurScale,
        ),
        // Published beside the blur settings and for the same reason: what has
        // to act on the preference is a transition spec below every screen, and
        // the first-run steps slide too.
        LocalMotion provides MotionSettings(
            enabled = settings.animations,
            speed = settings.motionSpeed,
        ),
        LocalLiquidRipple provides ripple,
    ) {
        // The page colour, painted once for the whole app.
        //
        // Nothing used to paint it. The tabs draw rows and nothing behind them,
        // so what showed between the rows was the *window* background — an
        // Android resource that follows the system's night mode and cannot
        // follow an in-app setting. While the two agreed it looked deliberate.
        // Choosing "светлая" on a phone in dark mode gave white rows and black
        // text on a black page, and "чёрная тема" appeared to do nothing at all,
        // because the only surface in the app that painted itself was the
        // settings layer — which is exactly where both settings did seem to work.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            when (rootScreen(shell)) {
                RootScreen.SPLASH -> SplashShell(modifier = modifier)

                RootScreen.ONBOARDING -> {
                    // One flow whichever way the phone arrives: a fresh install
                    // opens at the welcome, and one that has seen the
                    // introduction — which is what leaving the last class makes
                    // of it — at the chooser, where both ways in are. There is
                    // no bare join screen any more; it could reach neither the
                    // diary nor its sign-out.
                    //
                    // Latched on the first composition of this branch rather
                    // than read live: the flow records the introduction as seen
                    // on reaching the chooser, and a live read would restart it
                    // under the finger. Safe to latch because settings are real
                    // by the time this branch exists at all — the shell's state
                    // combines the settings flow with the mode, so nothing is
                    // emitted, and the splash stays, until preferences have been
                    // read from disk.
                    val introduced = rememberSaveable { settings.onboardingDone }
                    OnboardingScreen(introduced = introduced, modifier = modifier)
                }

                RootScreen.HOME -> {
                    // Never null here — the gate sends NONE to the way in — but
                    // a class home is the least surprising answer if it were.
                    val home = shell?.mode?.let(::shellHome) ?: ShellHome.TIMETABLE
                    // Keyed, so moving between the class and the diary resets
                    // the shell's hoisted state: the settings layer closes, and
                    // the phone lands on the new home rather than in a section
                    // of the old one — or on a pager index the diary has none of.
                    key(home) {
                        HomeShell(
                            home = home,
                            settings = settings,
                            openDate = openDate,
                            onDateOpened = onDateOpened,
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Whether [address] is plain `http://` — what the diary's sign-in warns about.
 * A blank address is not «insecure», it is «not configured»: nothing can be
 * sent at all, so the warning would be about a request that never happens.
 */
internal fun isInsecure(address: String): Boolean =
    address.isNotBlank() && !address.startsWith("https://", ignoreCase = true)

/**
 * Shown only while the session is being read. It is a deliberate blank with a
 * spinner: anything richer would flash for 30 ms and read as a glitch.
 */
@Composable
private fun SplashShell(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        LessonsLoadingIndicator()
    }
}
