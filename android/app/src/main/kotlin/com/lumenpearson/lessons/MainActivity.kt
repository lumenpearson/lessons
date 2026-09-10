package com.lumenpearson.lessons

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.navigation.LessonsApp
import com.lumenpearson.lessons.ui.AppShellViewModel

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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the launch theme, whose only job was to paint the window while the
        // process started, for the plain one. Leaving the starting theme applied
        // would keep its splash icon attributes live for the rest of the session.
        setTheme(R.style.Theme_Lessons)
        super.onCreate(savedInstanceState)
        // Drawn behind the system bars; the Scaffolds below apply the insets.
        enableEdgeToEdge()
        setContent {
            val shellViewModel: AppShellViewModel = viewModel(factory = AppShellViewModel.Factory)
            val shell by shellViewModel.uiState.collectAsStateWithLifecycle()

            // Haptics are a process-wide gate rather than a parameter threaded
            // through every component, so the stored preference is pushed into
            // it here — the one place that already observes the settings flow.
            LaunchedEffect(shell.settings.hapticsEnabled, shell.settings.hapticStrength) {
                LessonsHaptics.enabled.value = shell.settings.hapticsEnabled
                LessonsHaptics.strength.value = shell.settings.hapticStrength
            }

            LessonsTheme(
                themeMode = shell.settings.themeMode,
                dynamicColor = shell.settings.dynamicColor,
                pitchBlack = shell.settings.pitchBlack,
            ) {
                LessonsApp(signedIn = shell.signedIn, settings = shell.settings)
            }
        }
    }
}
