package com.lumenpearson.lessons.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lumenpearson.lessons.ui.homework.HomeworkScreen
import com.lumenpearson.lessons.ui.join.JoinScreen
import com.lumenpearson.lessons.ui.settings.SettingsScreen
import com.lumenpearson.lessons.ui.today.TodayScreen
import com.lumenpearson.lessons.ui.week.WeekScreen

/**
 * The whole app below the theme: navigation bar, graph, and the rule that keeps
 * the graph in step with the session.
 *
 * @param signedIn `null` while the stored session is still being read — the
 *   splash is shown for that moment so the start destination is only ever chosen
 *   once, from a known answer.
 * @param navController hoisted so a future deep link from the widget can drive
 *   it from the Activity.
 */
@Composable
fun LessonsApp(
    signedIn: Boolean?,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    if (signedIn == null) {
        SplashShell(modifier = modifier)
        return
    }

    // Captured once: NavHost ignores later changes to startDestination, and
    // sign-in/sign-out are handled by the effect below instead.
    val startDestination = remember {
        if (signedIn) LessonsRoute.Today.path else LessonsRoute.Join.path
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentPath = backStackEntry?.destination?.route

    // Session changes are the single source of truth for entering and leaving the
    // signed-in part of the graph: joining a class or signing out anywhere makes
    // the repository emit, and navigation follows. No screen has to know how the
    // other one is reached.
    LaunchedEffect(signedIn) {
        val path = navController.currentBackStackEntry?.destination?.route
        when {
            !signedIn && path != LessonsRoute.Join.path -> {
                navController.navigate(LessonsRoute.Join.path) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }

            signedIn && path == LessonsRoute.Join.path -> {
                navController.navigate(LessonsRoute.Today.path) {
                    popUpTo(LessonsRoute.Join.path) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (TopLevelDestination.isTopLevel(currentPath)) {
                LessonsNavigationBar(
                    currentPath = currentPath,
                    onSelect = { destination -> navController.switchTab(destination) },
                )
            }
        },
        // Each screen brings its own Scaffold and therefore its own top insets;
        // this outer one only owns the bar at the bottom.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable(LessonsRoute.Join.path) {
                JoinScreen()
            }
            composable(LessonsRoute.Today.path) {
                TodayScreen(
                    onOpenHomework = { navController.switchTab(TopLevelDestination.HOMEWORK) },
                )
            }
            composable(LessonsRoute.Week.path) {
                WeekScreen()
            }
            composable(LessonsRoute.Homework.path) {
                HomeworkScreen()
            }
            composable(LessonsRoute.Settings.path) {
                SettingsScreen()
            }
        }
    }
}

/**
 * Bar destinations behave like tabs: one entry per tab on the back stack, state
 * preserved when coming back, and Back always leaves through Today.
 */
private fun NavHostController.switchTab(destination: TopLevelDestination) {
    navigate(destination.route.path) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The four-tab bar.
 *
 * Plain [NavigationBar] rather than the Expressive `ShortNavigationBar`: this
 * module cannot be compiled here, and `NavigationBar` is the one spelling that
 * is certain to exist in material3 1.5.0-alpha24.
 * // fallback: swap for ShortNavigationBar/ShortNavigationBarItem once verified.
 */
@Composable
private fun LessonsNavigationBar(
    currentPath: String?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            val label = stringResource(destination.labelRes)
            NavigationBarItem(
                selected = currentPath == destination.route.path,
                onClick = { onSelect(destination) },
                icon = { Icon(imageVector = destination.icon, contentDescription = label) },
                label = { Text(text = label) },
            )
        }
    }
}

/**
 * Shown only while the session is being read. It is a deliberate blank with a
 * spinner: anything richer would flash for 30 ms and read as a glitch.
 */
@Composable
private fun SplashShell(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
