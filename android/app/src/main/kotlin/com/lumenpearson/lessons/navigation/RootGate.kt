package com.lumenpearson.lessons.navigation

import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.data.repository.ShellState

/** The three things the app can draw below the theme. */
enum class RootScreen {
    /** The stored state has not been read yet. */
    SPLASH,

    /** The way in: the introduction, or a way into a class or a diary. */
    ONBOARDING,

    /** A home — the class's or the diary's; see [shellHome]. */
    HOME,
}

/**
 * Which of the three [shell] asks for, as one rule the shell only carries out.
 *
 *  * Nothing read yet is the splash, rather than a guessed destination and a
 *    jump a frame later.
 *  * A hold keeps the way in on screen whatever the mode says. The class-code
 *    step joins and the sign-in step registers before the flow is finished —
 *    each flips the mode away from [ShellMode.NONE] while an import or a
 *    summary is still to come — and the flow, not the credential, decides when
 *    onboarding ends (K3). The hold is persisted, so a process death between
 *    the write and the summary brings the flow back.
 *  * No class and no diary is the way in.
 *  * Otherwise a home: a phone with a diary and no class has one now. It used
 *    to be `signedIn = session != null`, and such a phone — which is what
 *    leaving the last class makes of a family signed in to their diary — was
 *    sent to the join screen, where neither the diary nor its sign-out could be
 *    reached (#151).
 */
fun rootScreen(shell: ShellState?): RootScreen = when {
    shell == null -> RootScreen.SPLASH
    shell.held || shell.mode == ShellMode.NONE -> RootScreen.ONBOARDING
    else -> RootScreen.HOME
}
