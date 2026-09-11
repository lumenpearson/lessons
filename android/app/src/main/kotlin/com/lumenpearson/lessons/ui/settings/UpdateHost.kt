package com.lumenpearson.lessons.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import com.lumenpearson.lessons.core.designsystem.modifier.LiquidRippleState

/**
 * The release sheet, and the check that raises it by itself.
 *
 * Lives in the shell rather than on the updates page because the sheet has two
 * callers: a tap on the updates page, and the check the app runs at launch,
 * which happens whether or not settings is open. One host, one sheet.
 *
 * The two answers ripple in opposite directions. "Обновить" sends the wave out
 * from the button the way every other tap in the app does; "позже" runs it
 * backwards, the ring closing on the button, which is the same gesture said as
 * a no. Both fire before the sheet goes, so the wave starts under the finger
 * rather than under where the finger was.
 *
 * @param ripple the shell's wave, or `null` for a caller that has none.
 * @param screenToRoot converts a point the sheet measured on the screen to the
 *   shell's own coordinates — a modal sheet is a second window, so its
 *   coordinates and the shell's do not line up on their own.
 */
@Composable
internal fun UpdateHost(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    ripple: LiquidRippleState?,
    screenToRoot: (Offset) -> Offset,
) {
    val uriHandler = LocalUriHandler.current

    // Once per composition of the shell, which is once per process for
    // practical purposes; the repository's own once-a-day gate takes care of
    // the rest, so a rotation cannot turn into a second request.
    LaunchedEffect(Unit) { viewModel.checkForUpdatesAtLaunch() }

    if (!state.showReleaseSheet) return

    UpdateSheet(
        check = state.update,
        installedVersion = viewModel.installedVersion,
        onDismiss = viewModel::hideReleaseSheet,
        onOpenRelease = uriHandler::openUri,
        onUpdate = { release, origin ->
            ripple?.fire(screenToRoot(origin))
            viewModel.hideReleaseSheet()
            // The browser downloads and the package installer takes over;
            // nothing here writes an APK to disk or asks to install one.
            uriHandler.openUri(release.apkUrl ?: release.htmlUrl)
        },
        onLater = { release, origin ->
            ripple?.fire(screenToRoot(origin), reverse = true)
            viewModel.dismissUpdate(release.tag)
        },
        onRetry = viewModel::checkForUpdates,
    )
}
