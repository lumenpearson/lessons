package com.lumenpearson.lessons

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.core.data.diagnostics.CrashReporter
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.ThemeRevealHost
import com.lumenpearson.lessons.core.designsystem.theme.rememberThemeRevealState
import com.lumenpearson.lessons.core.designsystem.theme.resolvesToDark
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DeepLink
import com.lumenpearson.lessons.navigation.LessonsApp
import com.lumenpearson.lessons.ui.AppShellViewModel
import com.lumenpearson.lessons.ui.common.AppLocales
import com.lumenpearson.lessons.ui.translate.CorrectionHost
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The one and only Activity.
 *
 * A single-Activity app is not dogma here: the widget deep-links into a screen,
 * and every one of those links has to land in the same navigation graph so the
 * back stack stays sane. Everything this class does is therefore hoisting: read
 * the theme preferences and the session, hand both to Compose, get out of the
 * way.
 */
class MainActivity : ComponentActivity() {

    /**
     * A date the widget asked for, waiting to be consumed once.
     *
     * A `StateFlow` rather than a parameter because the intent can arrive twice
     * in two different ways: with the activity, and — since the activity is a
     * singleTask — through [onNewIntent] while it is already on screen. Held
     * until the shell has acted on it and cleared it, so a configuration change
     * in between does not lose the request or replay it.
     */
    private val pendingDate = MutableStateFlow<LocalDate?>(null)

    /**
     * The language this instance was attached in, as opposed to the one stored
     * now. Below API 33 they can differ for exactly as long as it takes the
     * effect below to notice and recreate the activity.
     */
    private var attachedLanguage: AppLanguage = AppLanguage.SYSTEM

    /**
     * Where the app's own language is put on, below API 33.
     *
     * It has to be here and nowhere later: the base context is what every
     * `Resources` in this activity — and therefore every `stringResource` in the
     * composition — is resolved through, and by `onCreate` it is already fixed.
     * On 33 and up [AppLocales.wrap] hands the context straight back, because
     * the platform has applied the per-app locale before this line runs.
     */
    override fun attachBaseContext(newBase: Context) {
        val language = AppLocales.languageToAttach()
        if (language == null) {
            super.attachBaseContext(newBase)
            return
        }
        attachedLanguage = language
        super.attachBaseContext(AppLocales.wrap(newBase, language))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDate.value = intent.consumeRequestedDate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the launch theme, whose only job was to paint the window while the
        // process started, for the plain one. Leaving the starting theme applied
        // would keep its splash icon attributes live for the rest of the session.
        setTheme(R.style.Theme_Lessons)
        super.onCreate(savedInstanceState)
        // Installed before anything else can throw. It records nothing until the
        // stored preference turns it on, which the effect below does.
        CrashReporter.install(this)
        // Drawn behind the system bars; the screens below apply the insets. The
        // call is repeated from the composition below, once the stored theme is
        // known — see the effect there.
        enableEdgeToEdge()
        pendingDate.value = intent?.consumeRequestedDate()
        setContent {
            val shellViewModel: AppShellViewModel = viewModel(factory = AppShellViewModel.Factory)
            val shell by shellViewModel.uiState.collectAsStateWithLifecycle()
            val openDate by pendingDate.collectAsStateWithLifecycle()

            // Haptics are a process-wide gate rather than a parameter threaded
            // through every component, so the stored preference is pushed into
            // it here — the one place that already observes the settings flow.
            LaunchedEffect(shell.settings.hapticsEnabled, shell.settings.hapticStrength) {
                LessonsHaptics.enabled.value = shell.settings.hapticsEnabled
                LessonsHaptics.strength.value = shell.settings.hapticStrength
            }

            // Same shape, same reason: a process-wide gate fed from the one place
            // that already observes the settings flow.
            LaunchedEffect(shell.settings.debugMode) {
                CrashReporter.setEnabled(shell.settings.debugMode)
            }

            // The stored language, applied to whatever this device can apply it
            // with. Below 33 this recreates the activity — the base context is
            // set once, in attachBaseContext, so there is no other way to change
            // the language of a screen that is already drawn; on 33 and up the
            // system does the restarting itself. Either way the user gets the
            // new language without leaving the settings page.
            //
            // `languageToApply`, not `shell.settings.language`: until the
            // settings flow has answered, that field is the placeholder the
            // state is constructed with — AppLanguage.SYSTEM — while
            // `attachedLanguage` already holds the real stored value, read
            // synchronously in attachBaseContext. Comparing the two made every
            // launch by somebody who had chosen a language look like a language
            // change, and below API 33 `applyTo` answers one with `recreate()`.
            //
            // That is not a wasted frame, which is why this reads a flag rather
            // than trusting the ordering. The recreation re-enters onCreate with
            // an Intent `consumeRequestedDate` has already emptied, and with a
            // fresh `pendingDate`: a tap on a day in the home-screen widget is
            // taken out of the Intent and then thrown away with the activity
            // that was holding it, so the app opens on the default tab and the
            // date is gone. The shell never gets a chance to act on it, because
            // while this effect runs the splash is still up.
            //
            // The key is the answer itself, so the effect runs again — once —
            // when the placeholder is replaced by what is stored.
            val language = shell.languageToApply
            LaunchedEffect(language) {
                if (language == null) return@LaunchedEffect
                AppLocales.applyTo(
                    activity = this@MainActivity,
                    language = language,
                    attached = attachedLanguage,
                )
            }

            // Created here, above the theme, because the tint below needs to
            // know when a photograph is on screen and the bars are not part of
            // the composition the host wraps.
            val themeReveal = rememberThemeRevealState(enabled = shell.settings.themeReveal)

            // Which way to tint the clock, the battery and the gesture bar.
            //
            // `enableEdgeToEdge()` on its own reads the *system* night mode to
            // decide, and the whole point of the theme setting is that the app
            // may disagree with the system: a light app under a dark system was
            // drawing white status-bar icons onto a white page. Re-applied
            // whenever the answer changes, which is the supported way to do it —
            // the call is idempotent.
            //
            // Held while the reveal is running. The bars are painted by the
            // window, above everything the circle wipes, so re-tinting them when
            // the setting changes puts dark icons over a photograph that is
            // still light — for as long as the wavefront takes to reach the top
            // of the screen, which from a switch in «Оформление» is most of the
            // animation. Waiting costs nothing: until the wave arrives the old
            // tint is the correct one for what is actually drawn up there.
            val darkTheme = shell.settings.themeMode.resolvesToDark()
            LaunchedEffect(darkTheme, themeReveal.revealing) {
                if (themeReveal.revealing) return@LaunchedEffect
                val bars = SystemBarStyle.auto(
                    lightScrim = Color.TRANSPARENT,
                    darkScrim = Color.TRANSPARENT,
                ) { darkTheme }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }

            LessonsTheme(
                themeMode = shell.settings.themeMode,
                dynamicColor = shell.settings.dynamicColor,
                pitchBlack = shell.settings.pitchBlack,
                font = shell.settings.appFont,
                textScale = shell.settings.textScale,
            ) {
                // Wraps the app rather than living inside a screen: the circle
                // has to cross the whole window, and the still it wipes away is
                // a photograph of the whole window.
                ThemeRevealHost(state = themeReveal) {
                    // Inside the theme, because the editor it hosts is a
                    // themed sheet; around everything else, because correction
                    // mode is meant to reach every screen and every sheet the
                    // app can put up, and a text block outside this is a text
                    // block a proofreader cannot fix.
                    CorrectionHost {
                        LessonsApp(
                            signedIn = shell.signedIn,
                            settings = shell.settings,
                            openDate = openDate,
                            onDateOpened = { pendingDate.value = null },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The date a widget tap is asking for, or null for a plain launch.
 *
 * Parsed defensively: the extra crosses a process boundary from a pending
 * intent that may have been created by an older build, and a malformed date
 * must open the app, not crash it.
 */
private fun Intent.consumeRequestedDate(): LocalDate? {
    val date = requestedDate() ?: return null
    // Read once, and then taken out of the Intent itself.
    //
    // `onDateOpened` only clears the local flow; `getIntent()` goes on
    // returning the widget's intent for as long as the task lives. So every
    // recreation replayed the deep link — and below API 33 changing the
    // language *is* a recreation (`AppLocales.applyTo` calls `recreate()`),
    // so switching to English threw the user out of «Оформление» and onto a
    // date they had asked about minutes earlier. The same replay happens on
    // every version when the process is killed in the background and the task
    // is resumed from Recents.
    action = null
    removeExtra(DeepLink.EXTRA_DATE)
    return date
}

private fun Intent.requestedDate(): LocalDate? {
    if (action != DeepLink.ACTION_OPEN_DAY) return null
    val raw = getStringExtra(DeepLink.EXTRA_DATE) ?: return null
    return runCatching { LocalDate.parse(raw) }.getOrNull()
}
