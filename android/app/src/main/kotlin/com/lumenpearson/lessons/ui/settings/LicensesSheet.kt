package com.lumenpearson.lessons.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EmojiSymbols
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer

/**
 * What this app is made of, and under which terms.
 *
 * A port of the "Licenses & Credits" sheet from
 * [Essentials](https://github.com/sameerasw/essentials): a centred headline,
 * one line under it, then a group of rows that each open on a tap to show
 * where the thing came from and a link to it. Essentials lists two JSON files;
 * this lists the libraries the app is built on and, first, Essentials itself —
 * the design language on this screen is the most borrowed thing in the app.
 *
 * The list is data rather than seven copies of a row, so that the licence text,
 * the chevron animation and the link handling exist once. There is no
 * `Modifier.animateContentSize` on the rows: `AnimatedVisibility` in a column
 * animates the height on its own, and the size modifier would clip the group's
 * corners during the transition the way it clips the toolbar's morph.
 */
@Composable
fun LicensesSheet(onDismiss: () -> Unit) {
    LessonsBottomSheet(onDismissRequest = onDismiss) {
        LicensesContent()
    }
}

/**
 * The sheet's body, split out so that a `@Preview` can render it without the
 * modal host: `ModalBottomSheet` lives in its own window and draws nothing in
 * the design tool.
 */
@Composable
private fun ColumnScope.LicensesContent() {
    Text(
        text = correctedString(R.string.licences_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
    )
    Text(
        text = correctedString(R.string.licences_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
    )
    Spacer(Modifier.height(8.dp))

    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        Credits.forEach { credit ->
            CreditRow(credit)
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * One project: the tile, the name, the licence, and behind the chevron a line
 * about what it does here and a link to its home.
 *
 * Not built on `GroupItem`, because a `ListItem` is one row and this is a row
 * that grows. It draws the same rectangle on the same colour with the same
 * 16 × 12 padding as `GroupRow`, so inside the group it is indistinguishable
 * from its neighbours until it opens.
 */
@Composable
private fun CreditRow(credit: Credit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) ChevronOpen else 0f,
        label = "chevron",
    )
    val view = rememberHapticView()

    Surface(
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.rowContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .clickable {
                    LessonsHaptics.press(view)
                    expanded = !expanded
                }
                .padding(horizontal = RowPaddingHorizontal, vertical = RowPaddingVertical),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                AccentIconTile(
                    icon = credit.icon,
                    tone = accentTone(credit.tone),
                    size = TileSize,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = correctedString(credit.name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = correctedString(credit.licence),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The description is on the icon rather than on the row so
                // that a screen reader reads "Essentials, Лицензия MIT,
                // Развернуть" as one node — the row is the target, the
                // chevron only says what the tap will do.
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = correctedString(
                        if (expanded) R.string.licences_collapse else R.string.licences_expand,
                    ),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(ChevronSize)
                        .rotate(rotation),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = RowPaddingVertical),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = correctedString(credit.note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ProjectLink(url = credit.url)
                }
            }
        }
    }
}

/**
 * The link out, as the address itself with the scheme cut off.
 *
 * The address is the label because it is the one thing on the row the reader
 * might want to type somewhere else, and "github.com/Kotlin" says more about
 * where you are going than "открыть" would.
 *
 * `openUri` throws when nothing on the device can open a browser — a locked
 * school phone, a work profile without one — and the sheet is not worth the
 * crash, so the failure is swallowed and the press haptic is the only answer.
 */
@Composable
private fun ProjectLink(url: String) {
    val view = rememberHapticView()
    val uriHandler = LocalUriHandler.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LinkIconGap),
        modifier = Modifier
            .clip(LessonsShapeTokens.Pill)
            .clickable(role = Role.Button) {
                LessonsHaptics.press(view)
                runCatching { uriHandler.openUri(url) }
            }
            .padding(horizontal = LinkPadding, vertical = LinkPadding),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(LinkIconSize),
        )
        Text(
            text = url.removePrefix(HttpsScheme),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One entry in the list.
 *
 * Everything a person reads is a string resource rather than a literal, the
 * same rule every other label in the app follows; the URL is data and stays a
 * string.
 *
 * @param tone which accent slot the tile takes, so that the rows differ by hue
 *   the way every group in the app does.
 */
private data class Credit(
    @StringRes val name: Int,
    @StringRes val licence: Int,
    val url: String,
    val icon: ImageVector,
    val tone: Int,
    @StringRes val note: Int,
)

/**
 * Essentials first: it is the reason this screen looks the way it does. The
 * rest is the order in which a reader meets them — the screens, the widget,
 * the network, the language, the storage, the glyphs.
 */
private val Credits = listOf(
    Credit(
        name = R.string.credit_essentials,
        licence = R.string.licence_mit,
        url = "https://github.com/sameerasw/essentials",
        icon = Icons.Rounded.Palette,
        tone = 0,
        note = R.string.credit_essentials_note,
    ),
    Credit(
        name = R.string.credit_compose,
        licence = R.string.licence_apache,
        url = "https://developer.android.com/jetpack/compose",
        icon = Icons.Rounded.Layers,
        tone = 1,
        note = R.string.credit_compose_note,
    ),
    Credit(
        name = R.string.credit_glance,
        licence = R.string.licence_apache,
        url = "https://developer.android.com/jetpack/androidx/releases/glance",
        icon = Icons.Rounded.Widgets,
        tone = 2,
        note = R.string.credit_glance_note,
    ),
    Credit(
        name = R.string.credit_square,
        licence = R.string.licence_apache,
        url = "https://square.github.io/retrofit/",
        icon = Icons.Rounded.Cloud,
        tone = 3,
        note = R.string.credit_square_note,
    ),
    Credit(
        name = R.string.credit_kotlinx,
        licence = R.string.licence_apache,
        url = "https://github.com/Kotlin",
        icon = Icons.Rounded.Code,
        tone = 4,
        note = R.string.credit_kotlinx_note,
    ),
    Credit(
        name = R.string.credit_jetpack_storage,
        licence = R.string.licence_apache,
        url = "https://developer.android.com/jetpack/androidx/releases/room",
        icon = Icons.Rounded.Storage,
        tone = 5,
        note = R.string.credit_jetpack_storage_note,
    ),
    // Nine rows over six hues, so three of them wrap. Each lands as far as the
    // list allows from the row it comes to share a hue with: slot 6 meets
    // Essentials at the top, slot 7 meets Compose one below it, slot 8 meets
    // Glance.
    Credit(
        name = R.string.credit_material_symbols,
        licence = R.string.licence_apache,
        url = "https://fonts.google.com/icons",
        icon = Icons.Rounded.EmojiSymbols,
        tone = 6,
        note = R.string.credit_material_symbols_note,
    ),
    // The glyphs, then the letters they are set beside. Its licence travels
    // in the APK as well as being named here: the OFL asks for the notice to
    // accompany every copy of the font, and a row on a sheet is not a copy of
    // anything — see core/designsystem assets/licenses/.
    Credit(
        name = R.string.credit_google_sans,
        licence = R.string.licence_ofl,
        // What the file itself points at: entries 11 and 12 of its `name`
        // table both read https://design.google. Google Sans Flex has no
        // specimen page on fonts.google.com to link instead, and the Code
        // family that does have one is a different typeface.
        url = "https://design.google",
        icon = Icons.Rounded.TextFields,
        tone = 7,
        note = R.string.credit_google_sans_note,
    ),
    // The second app this one borrowed from, and the only other one. Its
    // licence travels in the APK for the same reason the font's does: what was
    // taken is source rather than a dependency, and Apache 2.0 asks whoever
    // passes the work on to pass the licence with it — see
    // app/src/main/assets/notices/.
    Credit(
        name = R.string.credit_gms_flags,
        licence = R.string.licence_apache,
        url = "https://github.com/polodarb/GMS-Flags-Reborn",
        icon = Icons.Rounded.Animation,
        tone = 8,
        note = R.string.credit_gms_flags_note,
    ),
)

/** Larger than a settings row's 40 dp: Essentials draws these at 56. */
private val TileSize = 56.dp

/** `GroupRow`'s own padding, so a credit row lines up with any other row. */
private val RowPaddingHorizontal = 16.dp

private val RowPaddingVertical = 12.dp

private val RowGap = 14.dp

private val ChevronSize = 24.dp

/** A half turn: the "more" arrow becomes the "less" arrow. */
private const val ChevronOpen = 180f

private val LinkIconSize = 18.dp

private val LinkIconGap = 6.dp

private val LinkPadding = 4.dp

private const val HttpsScheme = "https://"

@Preview(name = "Licences", showBackground = true)
@Composable
private fun LicensesSheetPreview() {
    LessonsTheme {
        // The sheet's own container colour, so the rows contrast as they do
        // inside the real sheet rather than against the page.
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LicensesContent()
            }
        }
    }
}
