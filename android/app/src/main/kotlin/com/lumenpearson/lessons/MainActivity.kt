package com.lumenpearson.lessons

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
        super.onCreate(savedInstanceState)
        // Drawn behind the system bars; the Scaffolds below apply the insets.
        enableEdgeToEdge()
        setContent {
            val shellViewModel: AppShellViewModel = viewModel(factory = AppShellViewModel.Factory)
            val shell by shellViewModel.uiState.collectAsStateWithLifecycle()

            LessonsTheme(
                dynamicColor = shell.settings.dynamicColor,
                pitchBlack = shell.settings.pitchBlack,
            ) {
                LessonsApp(signedIn = shell.signedIn)
            }
        }
    }
}
