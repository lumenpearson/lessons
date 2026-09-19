package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ReleaseInfo
import com.lumenpearson.lessons.core.data.repository.UpdateCheck
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import java.time.Instant
import java.util.Locale

/**
 * The sheet the shell raises with the outcome of an update check.
 *
 * Essentials' update sheet in its four moods: a release to install, nothing
 * newer, still asking, could not ask. One composable for all four rather than
 * one per state, because the check is a [UpdateCheck] that moves between them
 * while the sheet is open — "Повторить" turns Failed back into Checking under
 * the same drag handle — and a sheet replaced by a different sheet on every
 * transition would slide out and back in for each.
 *
 * The sheet never dismisses itself. Every button reports to the caller, and the
 * caller decides whether the sheet goes: "Позже" has to record the tag before
 * the sheet leaves, and "Обновить" hands the APK to the browser while the sheet
 * stays as the thing to come back to.
 *
 * @param installedVersion what is on the phone, shown against the release tag.
 * @param onOpenRelease the release page on GitHub — the outlined pill, and the
 *   filled one too when a release has no APK asset to offer.
 * @param onUpdate the release to fetch, with the centre of the pressed button
 *   in **screen** coordinates. A modal sheet is a window of its own, so a
 *   position inside it means nothing to the app window underneath; the screen
 *   is the one frame both share, and the shell converts from there when it
 *   fires the ripple.
 * @param onLater the same origin for the same ripple, and the release so the
 *   caller knows which tag was declined.
 */
@Composable
fun UpdateSheet(
    check: UpdateCheck,
    installedVersion: String,
    onDismiss: () -> Unit,
    onOpenRelease: (url: String) -> Unit,
    onUpdate: (release: ReleaseInfo, origin: Offset) -> Unit,
    onLater: (release: ReleaseInfo, origin: Offset) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LessonsBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        UpdateSheetContent(
            check = check,
            installedVersion = installedVersion,
            onOpenRelease = onOpenRelease,
            onUpdate = onUpdate,
            onLater = onLater,
            onRetry = onRetry,
        )
    }
}

/**
 * Everything under the drag handle, on its own so the previews can draw it: a
 * `ModalBottomSheet` is a window, and a window renders as nothing in a preview.
 */
@Composable
private fun UpdateSheetContent(
    check: UpdateCheck,
    installedVersion: String,
    onOpenRelease: (url: String) -> Unit,
    onUpdate: (release: ReleaseInfo, origin: Offset) -> Unit,
    onLater: (release: ReleaseInfo, origin: Offset) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, bottom = SheetBottomInset),
        verticalArrangement = Arrangement.spacedBy(SheetBlockGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (check) {
            is UpdateCheck.Available -> AvailableContent(
                release = check.release,
                installedVersion = installedVersion,
                onOpenRelease = onOpenRelease,
                onUpdate = onUpdate,
                onLater = onLater,
            )

            is UpdateCheck.UpToDate -> UpToDateContent(
                current = check.current,
                installedVersion = installedVersion,
                onOpenRelease = onOpenRelease,
            )

            is UpdateCheck.Failed -> FailedContent(onRetry = onRetry)

            // Idle is what the sheet sees for the frame between being raised
            // and the check starting; drawn as checking so nothing flashes.
            UpdateCheck.Checking, UpdateCheck.Idle -> CheckingContent()
        }
    }
}

@Composable
private fun AvailableContent(
    release: ReleaseInfo,
    installedVersion: String,
    onOpenRelease: (url: String) -> Unit,
    onUpdate: (release: ReleaseInfo, origin: Offset) -> Unit,
    onLater: (release: ReleaseInfo, origin: Offset) -> Unit,
) {
    SheetHeader(
        icon = Icons.Rounded.SystemUpdate,
        tone = accentTone(0),
        title = correctedString(R.string.update_sheet_available_title),
        subtitle = correctedString(
            R.string.update_sheet_version_change,
            versionLabel(installedVersion),
            versionLabel(release.tag),
        ),
    )

    NotesSection(
        label = correctedString(R.string.update_sheet_whats_new, versionLabel(release.tag)),
        notes = release.notes,
    )

    val apkUrl = release.apkUrl
    // With no APK to hand over, the filled button opens the page itself, and a
    // second button above it doing the same thing would be noise.
    if (apkUrl != null) {
        SheetPill(
            label = correctedString(R.string.update_sheet_view_on_github),
            filled = false,
            onClick = { onOpenRelease(release.htmlUrl) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SheetPillGap),
    ) {
        SheetPill(
            label = correctedString(R.string.update_sheet_later),
            filled = false,
            onClick = { origin -> onLater(release, origin) },
            modifier = Modifier.weight(1f),
        )
        if (apkUrl != null) {
            val bytes = release.apkBytes
            SheetPill(
                label = if (bytes != null) {
                    correctedString(R.string.update_sheet_update_with_size, formatBytes(bytes))
                } else {
                    correctedString(R.string.update_sheet_update)
                },
                filled = true,
                icon = Icons.Rounded.Download,
                onClick = { origin -> onUpdate(release, origin) },
                modifier = Modifier.weight(1f),
            )
        } else {
            SheetPill(
                label = correctedString(R.string.update_sheet_open_release),
                filled = true,
                onClick = { onOpenRelease(release.htmlUrl) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun UpToDateContent(
    current: ReleaseInfo?,
    installedVersion: String,
    onOpenRelease: (url: String) -> Unit,
) {
    SheetHeader(
        icon = Icons.Rounded.TaskAlt,
        tone = accentTone(0),
        title = correctedString(R.string.update_sheet_up_to_date_title),
        subtitle = correctedString(R.string.update_sheet_installed, versionLabel(installedVersion)),
    )

    if (current != null) {
        NotesSection(
            label = correctedString(R.string.update_sheet_release_notes, versionLabel(current.tag)),
            notes = current.notes,
        )
        SheetPill(
            label = correctedString(R.string.update_sheet_view_on_github),
            filled = false,
            onClick = { onOpenRelease(current.htmlUrl) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        // A build ahead of every published release — a local one, or a tag the
        // workflow has not finished. Said quietly: nothing here is wrong.
        Text(
            text = correctedString(R.string.update_sheet_unknown_version, versionLabel(installedVersion)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = HeaderGap),
        )
    }
}

@Composable
private fun CheckingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HeaderGap),
        modifier = Modifier.padding(vertical = CheckingInset),
    ) {
        LoadingIndicator()
        Text(
            text = correctedString(R.string.update_sheet_checking),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailedContent(onRetry: () -> Unit) {
    SheetHeader(
        icon = Icons.Rounded.CloudOff,
        tone = errorTone(),
        title = correctedString(R.string.update_sheet_failed_title),
        subtitle = correctedString(R.string.update_sheet_failed_body),
    )
    SheetPill(
        label = correctedString(R.string.update_sheet_retry),
        filled = true,
        onClick = { onRetry() },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The tile, the headline and the line under it, all centred, as the reference sheet has them. */
@Composable
private fun SheetHeader(
    icon: ImageVector,
    tone: AccentTone,
    title: String,
    subtitle: String?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HeaderGap),
        modifier = Modifier.padding(top = HeaderTop),
    ) {
        AccentIconTile(icon = icon, tone = tone, size = HeroTile)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The label above the notes and the card holding them.
 *
 * The card is `surfaceBright`, the row colour of the design language, not a
 * tonal step above the sheet: the sheet is already `surfaceContainerHigh`, and
 * the next step up is a colour nobody can tell from it in a dark scheme. A row
 * on a page is the one contrast this app is built on, and it holds in both.
 */
@Composable
private fun NotesSection(label: String, notes: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(NotesLabelGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Surface(
            shape = RoundedCornerShape(NotesCorner),
            color = MaterialTheme.colorScheme.surfaceBright,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.padding(NotesPadding)) {
                if (notes.isBlank()) {
                    Text(
                        text = correctedString(R.string.update_sheet_no_notes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ReleaseNotes(markdown = notes)
                }
            }
        }
    }
}

/**
 * A 56 dp capsule, filled or outlined — the shape every action in an Essentials
 * sheet takes, and the one the pre-release sheet borrows.
 *
 * [onClick] receives the button's centre on the screen. The shell answers
 * "update" and "later" with a wave that starts under the finger, and the button
 * is the only thing that knows where that is. Screen coordinates rather than
 * window ones: a modal sheet is a window of its own, so `boundsInWindow` here
 * would put the wave's origin somewhere near the top of the app instead of on
 * the button. Callers that have no wave to fire ignore the argument.
 *
 * The label may shrink by up to three points, and only when the pill is too
 * narrow for it. "Обновить · 12,4 МБ" beside its icon does not fit half of a
 * 360 dp screen at full size, and the alternative — an ellipsis through the
 * size — is worse than a smaller label.
 */
@Composable
internal fun SheetPill(
    label: String,
    filled: Boolean,
    onClick: (origin: Offset) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val view = rememberHapticView()
    var centre by remember { mutableStateOf(Offset.Unspecified) }

    val pillModifier = modifier
        .height(SheetPillHeight)
        .onGloballyPositioned { coordinates ->
            val corner = coordinates.positionOnScreen()
            centre = Offset(
                x = corner.x + coordinates.size.width / 2f,
                y = corner.y + coordinates.size.height / 2f,
            )
        }
    val press = {
        LessonsHaptics.press(view)
        onClick(centre)
    }
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(SheetPillIcon),
            )
            Spacer(Modifier.width(SheetPillIconGap))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            autoSize = TextAutoSize.StepBased(
                minFontSize = SheetPillFontMin,
                maxFontSize = SheetPillFontMax,
                stepSize = SheetPillFontStep,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (filled) {
        Button(
            onClick = press,
            modifier = pillModifier,
            shape = CircleShape,
            contentPadding = SheetPillPadding,
            content = content,
        )
    } else {
        OutlinedButton(
            onClick = press,
            modifier = pillModifier,
            shape = CircleShape,
            contentPadding = SheetPillPadding,
            content = content,
        )
    }
}

/**
 * "v0.2.0" for both what is installed and what is offered.
 *
 * The tag carries its own "v"; the installed `versionName` does not, and the
 * about card already prints it as "v0.1.0". A subtitle reading "0.1.0 → v0.2.0"
 * would look like two different kinds of number.
 *
 * Not `@ReadOnlyComposable`, although it reads nothing but a resource:
 * [correctedString] registers the words it hands back for as long as they are
 * on screen, which is an effect, and that is what makes them correctable.
 */
@Composable
private fun versionLabel(version: String): String =
    if (version.startsWith(VersionPrefix, ignoreCase = true)) {
        version
    } else {
        correctedString(R.string.update_version_label, version)
    }

/**
 * "12,4 МБ" for the download button; "812 КБ" below a megabyte.
 *
 * Binary units, because that is what the release page on GitHub prints beside
 * the asset, and a number that differs from the one the user just read there
 * looks like a different file. The decimal comma comes from the locale rather
 * than from the string: a Russian sentence with "12.4" in it is a typo.
 *
 * Composable because the unit is a resource, like every other word on screen —
 * and not `@ReadOnlyComposable`, for the reason given on [versionLabel].
 */
@Composable
internal fun formatBytes(bytes: Long): String {
    val kilobytes = bytes / BytesPerKilobyte
    return if (kilobytes < KilobytesPerMegabyte) {
        correctedString(R.string.update_size_kb, "%.0f".format(SizeLocale, kilobytes))
    } else {
        correctedString(R.string.update_size_mb, "%.1f".format(SizeLocale, kilobytes / KilobytesPerMegabyte))
    }
}

private val SizeLocale: Locale = Locale.forLanguageTag("ru")

private const val BytesPerKilobyte = 1024.0

private const val KilobytesPerMegabyte = 1024.0

private const val VersionPrefix = "v"

/** Room between the last pill and the gesture bar; the sheet's own inset alone leaves none. */
internal val SheetBottomInset = 16.dp

/** Between the blocks of a sheet: header, notes, buttons. */
internal val SheetBlockGap = 12.dp

/** Between two pills in a row; the same 8 dp as the about card's link pills. */
internal val SheetPillGap = 8.dp

/** Essentials' pill height. */
private val SheetPillHeight = 56.dp

/** Narrower than Material's 24 dp default, so a label with a size in it has room. */
private val SheetPillPadding = PaddingValues(horizontal = 16.dp)

private val SheetPillIcon = 18.dp

private val SheetPillIconGap = 8.dp

/** `labelLarge`'s own size; the label never grows past it. */
private val SheetPillFontMax = 14.sp

private val SheetPillFontMin = 11.sp

private val SheetPillFontStep = 0.5.sp

/** The sheet's one large tile; the rows' tiles are 40 dp. */
private val HeroTile = 72.dp

private val HeaderGap = 8.dp

/** Under the drag handle, which the sheet draws with a margin of its own. */
private val HeaderTop = 8.dp

private val NotesLabelGap = 8.dp

/** Larger than the 24 dp of a group: the card is alone on the sheet and has no rows to mask. */
private val NotesCorner = 28.dp

private val NotesPadding = 20.dp

/** Breathing room around the spinner, so the sheet is not a sliver while it waits. */
private val CheckingInset = 24.dp

/** A release with the shape GitHub's generated notes really have. */
private val PreviewRelease = ReleaseInfo(
    tag = "v0.2.0",
    name = "v0.2.0",
    notes = """
        <!-- Release notes generated using configuration in .github/release.yml at main -->
        ## Что нового
        - Виджет показывает домашку на завтра, когда на сегодня ничего не осталось
        - Уведомление за **10 минут** до звонка по каналу «Уроки»
        - `Chronometer` в узком виджете снова говорит, до чего он считает

        ## Исправления
        - Синхронизация за минуту до звонка больше не съедает будильник ([#42](https://github.com/lumenpearson/lessons/issues/42))
        - Напоминание о домашке приходит один раз, а не три by @lumenpearson in https://github.com/lumenpearson/lessons/pull/47

        **Full Changelog**: https://github.com/lumenpearson/lessons/compare/v0.1.0...v0.2.0
    """.trimIndent(),
    htmlUrl = "https://github.com/lumenpearson/lessons/releases/tag/v0.2.0",
    publishedAt = Instant.parse("2026-09-01T10:00:00Z"),
    isPreRelease = false,
    apkUrl = "https://github.com/lumenpearson/lessons/releases/download/v0.2.0/lessons-v0.2.0.apk",
    apkBytes = 12_990_000L,
)

@Preview(name = "UpdateSheet · available", showBackground = true)
@Composable
private fun UpdateSheetAvailablePreview() {
    LessonsTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            UpdateSheetContent(
                check = UpdateCheck.Available(PreviewRelease),
                installedVersion = "0.1.0",
                onOpenRelease = {},
                onUpdate = { _, _ -> },
                onLater = { _, _ -> },
                onRetry = {},
            )
        }
    }
}

@Preview(name = "UpdateSheet · up to date", showBackground = true)
@Composable
private fun UpdateSheetUpToDatePreview() {
    LessonsTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            UpdateSheetContent(
                check = UpdateCheck.UpToDate(PreviewRelease),
                installedVersion = "0.2.0",
                onOpenRelease = {},
                onUpdate = { _, _ -> },
                onLater = { _, _ -> },
                onRetry = {},
            )
        }
    }
}
