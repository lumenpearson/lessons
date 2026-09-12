package com.lumenpearson.lessons.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.lumenpearson.lessons.BuildConfig
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView

/**
 * The card at the very bottom of the about page: what this is, and who it is by.
 *
 * A port of Essentials' `AboutSection`, arranged as its own screenshot has it —
 * one card, everything centred, the links as pills two to a row.
 *
 * Rebuilt rather than copied, because the reference has a row of defects worth
 * not inheriting. Its nine link buttons are nine copies of the same fifteen
 * lines differing only in icon, label and URL, so only the one that happens to
 * open a mail client guards `ActivityNotFoundException` — on a device with no
 * browser the other eight take the app down. Here a link is a [AboutLink] and
 * there is one button, so the guard and the haptic exist once and cover
 * everything. The reference also reads its own version over a `PackageManager`
 * binder call on every recomposition, and prints a literal `null` when that
 * call returns one, which it is typed to do; `BuildConfig` is the same fact
 * without the round trip.
 */
@Composable
fun AboutCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCorner),
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BlockGap),
            modifier = Modifier.padding(horizontal = CardPadding, vertical = CardPaddingTall),
        ) {
            Text(
                text = stringResource(R.string.about_name_and_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            AppMark()

            Text(
                text = stringResource(R.string.about_developer_line),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )

            LinkPills()

            DesignCredit()

            Text(
                text = stringResource(R.string.about_closing_line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The launcher icon, drawn the way a launcher draws it.
 *
 * An adaptive icon is a 108 dp canvas of which only the middle 72 dp is ever
 * shown; painting the mipmap directly would letterbox the whole canvas and the
 * mark would sit small in a field of its own background. So the foreground is
 * drawn at the reciprocal of that fraction and clipped to the box, which is the
 * same crop the home screen applies.
 */
@Composable
private fun AppMark() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MarkSize)
            .clip(RoundedCornerShape(MarkCorner))
            .background(colorResource(R.color.ic_launcher_background)),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(MarkSize * AdaptiveCanvasRatio),
        )
    }
}

/** Where this came from, with the source repository as a link in the sentence. */
@Composable
private fun DesignCredit() {
    val label = stringResource(R.string.about_design_credit_link)
    val credit = buildAnnotatedString {
        append(stringResource(R.string.about_design_credit_before))
        withLink(
            LinkAnnotation.Url(
                url = EssentialsUrl,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            ),
        ) {
            append(label)
        }
        append(stringResource(R.string.about_design_credit_after))
    }

    // No onClick and no Intent: LinkAnnotation.Url is opened by the platform's
    // own UriHandler, which is also what makes the link reachable by a screen
    // reader as a link rather than as a word that happens to be underlined.
    Text(
        text = credit,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** One outward link: an icon, a label, and somewhere to go. */
private data class AboutLink(
    val icon: ImageVector,
    @StringRes val label: Int,
    val url: String,
)

private const val ProjectUrl = "https://github.com/lumenpearson/lessons"
private const val EssentialsUrl = "https://github.com/sameerasw/essentials"

private val Links = listOf(
    AboutLink(Icons.Rounded.Code, R.string.about_link_source, ProjectUrl),
    AboutLink(Icons.Rounded.Palette, R.string.about_link_essentials, EssentialsUrl),
)

/**
 * The links, two to a row and equal width.
 *
 * Equal width is why this is a `Row` of weighted children rather than the
 * reference's `FlowRow`: flowing sizes each pill to its own label, so a short
 * label beside a long one reads as two different kinds of control rather than
 * as two of the same kind.
 */
@Composable
private fun LinkPills() {
    Column(
        verticalArrangement = Arrangement.spacedBy(PillGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Links.chunked(PillsPerRow).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(PillGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { link ->
                    LinkPill(link = link, modifier = Modifier.weight(1f))
                }
                // Keeps a lone pill on an odd last row the same width as the
                // ones above it instead of letting it stretch across.
                repeat(PillsPerRow - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LinkPill(link: AboutLink, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = rememberHapticView()

    OutlinedButton(
        onClick = {
            LessonsHaptics.press(view)
            val intent = Intent(Intent.ACTION_VIEW, link.url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // A phone with no browser at all is rare; a work profile or a
            // locked-down school device that cannot open one is not, and the
            // reference crashes on both.
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                LessonsHaptics.press(view)
            }
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = link.icon,
            contentDescription = null,
            modifier = Modifier.size(PillIcon),
        )
        Spacer(Modifier.width(PillIconGap))
        Text(
            text = stringResource(link.label),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Radius of the card. The page's other cards use the same one. */
private val CardCorner = 24.dp

private val CardPadding = 20.dp

/** Taller than it is wide: the block is a column of centred lines, not a row. */
private val CardPaddingTall = 28.dp

private val BlockGap = 12.dp

private val MarkSize = 96.dp

private val MarkCorner = 24.dp

/**
 * 108 dp of adaptive canvas over the 72 dp a launcher actually shows.
 *
 * @see AppMark
 */
private const val AdaptiveCanvasRatio = 108f / 72f

private val PillGap = 8.dp

private val PillIcon = 18.dp

private val PillIconGap = 8.dp

private const val PillsPerRow = 2
