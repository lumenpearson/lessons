package com.lumenpearson.lessons.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
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
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.data.repository.ServerStatus

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
fun AboutCard(serverStatus: ServerStatus, modifier: Modifier = Modifier) {
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
                text = correctedString(R.string.about_name_and_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )

            Text(
                text = correctedString(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            AppMark()

            Text(
                text = correctedString(R.string.about_developer_line),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )

            Badges(serverStatus = serverStatus)

            LinkPills()

            Facts()

            DesignCredit()

            Text(
                text = correctedString(R.string.about_closing_line),
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
    val label = correctedString(R.string.about_design_credit_link)
    val credit = buildAnnotatedString {
        append(correctedString(R.string.about_design_credit_before))
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
        append(correctedString(R.string.about_design_credit_after))
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
 * The links, one to a row and the full width of the card.
 *
 * Two to a row is what this was, and on a 411 dp phone it gave each pill about
 * 130 dp — «Исходный код» came out as three stacked lines and «Essentials» was
 * broken into «Essent / ials», a word split in half inside a button. Half of
 * that was the card being padded twice (see [AboutCard]'s use in the settings
 * list), and half was two pills sharing a row that was already narrow.
 *
 * Full width fixes both and costs one row of height for one extra link. It also
 * removes the thing the old shape needed a spacer to fake: with one pill per
 * row every pill is the same width by construction, rather than by an empty
 * `Box` standing in for a missing one.
 */
@Composable
private fun LinkPills() {
    Column(
        verticalArrangement = Arrangement.spacedBy(PillGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Links.forEach { link ->
            LinkPill(link = link, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * What this build is and what it is talking to, as a row of chips.
 *
 * Two questions that are asked together and answered nowhere else in the app.
 * «Какая это сборка» cannot be answered by the version alone — every CI build
 * of a branch carries the same `versionName` — so the repository, the ref and
 * the commit are what actually identify an APK on a phone against a pull
 * request. And «сервер жив?» has never had an answer here at all: every screen
 * reports an unreachable server, a mistyped address and a half-applied
 * migration as the same «не удалось обновить».
 *
 * A [FlowRow] rather than a fixed grid because the chips are a variable set:
 * a local build has no commit, a build before this change has none of them, and
 * a server that was never configured contributes one chip instead of three.
 */
@Composable
private fun Badges(serverStatus: ServerStatus) {
    val build = remember { BuildProvenance.current() }
    val context = LocalContext.current
    val view = rememberHapticView()

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BadgeGap, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(BadgeGap),
    ) {
        ServerBadges(serverStatus)

        if (build.isLocal) {
            // Said out loud rather than left blank. A page with no build chips
            // at all reads as one that forgot to draw them; «собрано вручную»
            // is a fact, and it is the one that explains why there is no commit
            // to compare against anything.
            PillChip(text = correctedString(R.string.about_badge_local_build))
        }

        if (build.repository.isNotBlank()) {
            PillChip(
                text = correctedString(R.string.about_badge_repository, build.repository),
                icon = Icons.Rounded.Code,
                onClick = build.repositoryUrl?.let { url -> { open(context, view, url) } },
            )
        }
        if (build.ref.isNotBlank()) {
            PillChip(text = correctedString(R.string.about_badge_ref, build.ref))
        }
        if (build.commit.isNotBlank()) {
            // The chip worth being tappable: it opens the exact diff this APK
            // was built from, which is the question somebody holding a phone
            // and a pull request is actually asking.
            PillChip(
                text = correctedString(R.string.about_badge_commit, build.shortCommit),
                onClick = build.commitUrl?.let { url -> { open(context, view, url) } },
            )
        }
        if (build.number.isNotBlank()) {
            PillChip(text = correctedString(R.string.about_badge_run, build.number))
        }
        if (build.builtAt.isNotBlank()) {
            PillChip(text = correctedString(R.string.about_badge_built_at, build.builtAt))
        }
        if (BuildConfig.DEBUG) {
            PillChip(
                text = correctedString(R.string.about_badge_debug),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * The server chips: how it answered, and — when it answered — what it is.
 *
 * `degraded` is the state worth colouring. It means the API is up and its
 * database is at a different Alembic revision from the code in front of it,
 * which is the window between a merge deploying itself and the migration being
 * applied by hand. Nothing else in the app can see that state: `/health` is
 * green throughout it, and what fails is every read of whatever gained a
 * column.
 */
@Composable
private fun FlowRowScope.ServerBadges(status: ServerStatus) {
    val scheme = MaterialTheme.colorScheme
    when (status) {
        ServerStatus.Checking ->
            PillChip(text = correctedString(R.string.about_badge_server_checking))

        ServerStatus.NotConfigured ->
            PillChip(text = correctedString(R.string.about_badge_server_none))

        ServerStatus.Unreachable -> PillChip(
            text = correctedString(R.string.about_badge_server_unreachable),
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
        )

        is ServerStatus.Ok -> {
            PillChip(
                text = correctedString(R.string.about_badge_server_ok),
                containerColor = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
            )
            PillChip(text = correctedString(R.string.about_badge_api, status.apiVersion))
            status.schema?.let { schema ->
                PillChip(text = correctedString(R.string.about_badge_schema, schema))
            }
        }

        is ServerStatus.Degraded -> {
            PillChip(
                text = correctedString(R.string.about_badge_server_degraded),
                containerColor = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
            )
            status.schema?.let { schema ->
                PillChip(text = correctedString(R.string.about_badge_schema, schema))
            }
        }
    }
}

/**
 * A few true things about the app that are not otherwise visible from it.
 *
 * Every one of them is a decision somebody reading this page would otherwise
 * have to take on trust or discover by accident — that it works with no
 * network, that the bot is the only way to write a timetable, that the clock is
 * the school's rather than the phone's. They are deliberately about behaviour
 * rather than about the build: a fact that stops being true is a fact this
 * project has changed its mind about, and it should be edited here in the same
 * batch as everywhere else.
 */
@Composable
private fun Facts() {
    Column(
        verticalArrangement = Arrangement.spacedBy(FactGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = correctedString(R.string.about_facts_title),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Facts.forEach { fact ->
            Text(
                text = correctedString(fact),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Opens a URL, guarded the way [LinkPill] guards its own. */
private fun open(context: android.content.Context, view: android.view.View, url: String) {
    LessonsHaptics.press(view)
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        LessonsHaptics.press(view)
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
            text = correctedString(link.label),
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

/** Gap between two badges, in both directions of the flow. */
private val BadgeGap = 6.dp

/** Gap between two lines of the facts block; tighter than [BlockGap]. */
private val FactGap = 6.dp

/** The facts block, in the order they are drawn. */
private val Facts = listOf(
    R.string.about_fact_offline,
    R.string.about_fact_bot,
    R.string.about_fact_windows,
    R.string.about_fact_clock,
    R.string.about_fact_fonts,
    R.string.about_fact_widget,
)

private val PillIcon = 18.dp

private val PillIconGap = 8.dp

