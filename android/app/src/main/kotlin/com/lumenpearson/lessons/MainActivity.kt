package com.lumenpearson.lessons

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.core.data.diagnostics.CrashReporter
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.DeepLink
import com.lumenpearson.lessons.navigation.LessonsApp
import com.lumenpearson.lessons.ui.AppShellViewModel
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDate.value = intent.requestedDate()
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
        // Drawn behind the system bars; the screens below apply the insets.
        enableEdgeToEdge()
        pendingDate.value = intent?.requestedDate()
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

            LessonsTheme(
                themeMode = shell.settings.themeMode,
                dynamicColor = shell.settings.dynamicColor,
                pitchBlack = shell.settings.pitchBlack,
            ) {
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

/**
 * The date a widget tap is asking for, or null for a plain launch.
 *
 * Parsed defensively: the extra crosses a process boundary from a pending
 * intent that may have been created by an older build, and a malformed date
 * must open the app, not crash it.
 */
private fun Intent.requestedDate(): LocalDate? {
    if (action != DeepLink.ACTION_OPEN_DAY) return null
    val raw = getStringExtra(DeepLink.EXTRA_DATE) ?: return null
    return runCatching { LocalDate.parse(raw) }.getOrNull()
}
