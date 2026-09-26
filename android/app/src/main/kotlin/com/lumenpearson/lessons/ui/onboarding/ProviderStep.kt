package com.lumenpearson.lessons.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.common.ServerUrlSheet
import com.lumenpearson.lessons.ui.common.openInBrowser
import com.lumenpearson.lessons.ui.diary.localized
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * «Электронный дневник»: the systems the region's schools use, the recommended
 * one highlighted, and «Почему?» beside it.
 *
 * Every answer on this page is the catalog's — which system is recommended and
 * why, which can be signed into, where a handoff goes — read, never re-derived
 * (K18). What the page adds is what to do about each:
 *
 *  * a system the app's own form reaches goes on to the sign-in, behind the
 *    server gate: the registration needs a server, and the page asks for its
 *    address here, where it is first needed (DESIGN decision 7);
 *  * ТОР «Моя школа» and the Госуслуги-only «Сетевой город» regions are a
 *    handoff — the browser opens the catalog's https address, and nothing comes
 *    back. The app never takes a Госуслуги sign-in, never embeds a browser and
 *    never names an app by its package;
 *  * a system the app cannot read says so, and the class code is offered.
 */
@Composable
internal fun ProviderStep(
    viewModel: OnboardingViewModel,
    onBack: (() -> Unit)?,
) {
    val page by viewModel.provider.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    var whyShown by rememberSaveable { mutableStateOf(false) }
    var handoff by rememberSaveable { mutableStateOf<Int?>(null) }
    var serverFor by rememberSaveable { mutableStateOf<Int?>(null) }
    val current = page

    fun signIn(row: ProviderRowUi) {
        if (baseUrl.isBlank()) serverFor = row.index else viewModel.signInWith(row.index)
    }

    fun act(row: ProviderRowUi) = when (row.kind) {
        ProviderRowKind.SIGN_IN -> signIn(row)
        ProviderRowKind.NEEDS_SCHOOL -> viewModel.toSchool()
        ProviderRowKind.HANDOFF, ProviderRowKind.SITE -> handoff = row.index
        ProviderRowKind.UNSUPPORTED, ProviderRowKind.PAPER -> Unit
    }

    serverFor?.let { index ->
        ServerUrlSheet(
            initialUrl = baseUrl,
            description = correctedString(R.string.onboarding_sign_in_needs_server),
            onDismiss = { serverFor = null },
            onConfirm = { url ->
                serverFor = null
                viewModel.setServer(url)
                if (url.isNotBlank()) viewModel.signInWith(index)
            },
        )
    }
    val handoffRow = current?.rows?.firstOrNull { it.index == handoff }
    if (handoffRow != null) {
        HandoffSheet(
            row = handoffRow,
            onDismiss = { handoff = null },
            onClassCode = {
                handoff = null
                viewModel.toClassCode()
            },
        )
    }
    val why = current?.why
    if (whyShown && why != null) {
        WhySheet(why = why, onDismiss = { whyShown = false })
    }

    val main = current?.main
    ListStepScaffold(
        actions = {
            OnboardingActions(
                label = correctedString(
                    when (main?.kind) {
                        ProviderRowKind.SIGN_IN -> R.string.onboarding_action_sign_in
                        ProviderRowKind.NEEDS_SCHOOL -> R.string.onboarding_action_pick_school
                        ProviderRowKind.HANDOFF -> R.string.onboarding_action_gosuslugi
                        ProviderRowKind.SITE -> R.string.onboarding_action_open_site
                        else -> R.string.onboarding_action_code
                    },
                ),
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                onBack = onBack,
                enabled = current != null,
                onClick = { if (main != null) act(main) else viewModel.toClassCode() },
            )
        },
    ) {
        item(key = "title") {
            OnboardingTitle(
                title = correctedString(R.string.onboarding_provider_title),
                subtitle = current?.let {
                    correctedString(R.string.onboarding_provider_subtitle, localized(it.regionRu, it.regionEn).orEmpty())
                },
            )
        }
        if (current != null) {
            item(key = "systems") {
                RoundedCardContainer {
                    current.rows.forEach { row ->
                        ProviderRow(
                            row = row,
                            onClick = { act(row) }.takeIf {
                                row.kind != ProviderRowKind.UNSUPPORTED && row.kind != ProviderRowKind.PAPER
                            },
                            onWhy = { whyShown = true }.takeIf { row.recommended && why != null },
                        )
                    }
                }
            }
        }
        item(key = "code") {
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = correctedString(R.string.onboarding_provider_code_section))
                RoundedCardContainer {
                    GroupLinkItem(
                        title = correctedString(R.string.onboarding_provider_code_title),
                        subtitle = correctedString(R.string.onboarding_provider_code_text),
                        icon = Icons.Rounded.Key,
                        tone = accentTone(1),
                        onClick = viewModel::toClassCode,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    row: ProviderRowUi,
    onClick: (() -> Unit)?,
    onWhy: (() -> Unit)?,
) {
    val name = localized(row.nameRu, row.nameEn).orEmpty()
    val action = correctedString(
        when (row.kind) {
            ProviderRowKind.SIGN_IN ->
                if (row.platform == PETERSBURG_PLATFORM) {
                    R.string.onboarding_provider_action_account
                } else {
                    R.string.onboarding_provider_action_sign_in
                }
            ProviderRowKind.NEEDS_SCHOOL -> R.string.onboarding_provider_action_needs_school
            ProviderRowKind.HANDOFF -> R.string.onboarding_provider_action_gosuslugi
            ProviderRowKind.SITE -> R.string.onboarding_provider_action_site
            ProviderRowKind.UNSUPPORTED -> R.string.onboarding_provider_action_unsupported
            ProviderRowKind.PAPER -> R.string.onboarding_provider_action_paper
        },
    )
    val role = roleRes(row.role)?.let { correctedString(it) }
    ChoiceRow(
        title = name,
        subtitle = role?.let { correctedString(R.string.onboarding_provider_line, it, action) } ?: action,
        icon = when (row.kind) {
            ProviderRowKind.SIGN_IN, ProviderRowKind.NEEDS_SCHOOL -> Icons.AutoMirrored.Rounded.Login
            ProviderRowKind.HANDOFF, ProviderRowKind.SITE -> Icons.AutoMirrored.Rounded.OpenInNew
            ProviderRowKind.UNSUPPORTED, ProviderRowKind.PAPER -> Icons.AutoMirrored.Rounded.MenuBook
        },
        // Neutral for a system nothing can be done with, but still drawn and
        // still readable: «почему» and the class code below say what instead.
        tone = when (row.kind) {
            ProviderRowKind.UNSUPPORTED, ProviderRowKind.PAPER -> AccentTone(
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.outline,
            )
            else -> accentTone(2)
        },
        selected = row.recommended,
        onClick = onClick,
        below = if (row.recommended) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillChip(text = correctedString(R.string.onboarding_provider_recommended))
                    if (onWhy != null) {
                        val described = correctedString(R.string.onboarding_provider_why_description, name)
                        TextButton(
                            onClick = onWhy,
                            modifier = Modifier.semantics { contentDescription = described },
                        ) {
                            Text(correctedString(R.string.onboarding_provider_why))
                        }
                    }
                }
            }
        } else {
            null
        },
    )
}

@StringRes
private fun roleRes(role: String?): Int? = when (role) {
    "primary" -> R.string.onboarding_provider_role_primary
    "secondary" -> R.string.onboarding_provider_role_secondary
    "migrating_to" -> R.string.onboarding_provider_role_moving_to
    else -> null
}

/**
 * Where a handoff goes, and what to do there — before the browser takes over,
 * because nothing comes back from it and the flow stays on this step.
 */
@Composable
private fun HandoffSheet(
    row: ProviderRowUi,
    onDismiss: () -> Unit,
    onClassCode: () -> Unit,
) {
    val context = LocalContext.current
    var failed by rememberSaveable { mutableStateOf(false) }
    val url = row.url
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = correctedString(R.string.onboarding_handoff_title, localized(row.nameRu, row.nameEn).orEmpty()),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = correctedString(
                    if (row.gosuslugi) R.string.onboarding_handoff_gosuslugi else R.string.onboarding_handoff_site,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (failed) {
                Text(
                    text = correctedString(R.string.onboarding_handoff_no_browser),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onClassCode) {
                    Text(correctedString(R.string.onboarding_provider_code_title))
                }
                if (url != null) {
                    Button(
                        onClick = {
                            // ACTION_VIEW on the catalog's https address, and
                            // nothing else: the phone decides what opens it.
                            if (openInBrowser(context, url)) onDismiss() else failed = true
                        },
                    ) {
                        Text(correctedString(R.string.onboarding_handoff_open))
                    }
                }
            }
        }
    }
}

/** «Почему?»: the catalog's reason for the recommendation, and how sure it is. */
@Composable
private fun WhySheet(why: WhyUi, onDismiss: () -> Unit) {
    val russian = Locale.current.language == "ru"
    val month = surveyMonthText(why.surveyMonth, if (russian) java.util.Locale.forLanguageTag("ru") else java.util.Locale.ENGLISH)
    val lines = whyLines(
        why = why,
        region = (if (russian) why.regionRu else why.regionEn).ifBlank { why.regionRu },
        system = (if (russian) why.systemRu else why.systemEn).ifBlank { why.systemRu },
        mainSystem = if (russian) why.mainSystemRu else why.mainSystemEn ?: why.mainSystemRu,
        month = month,
    )
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = correctedString(
            R.string.onboarding_why_title,
            (if (russian) why.systemRu else why.systemEn).ifBlank { why.systemRu },
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            lines.forEach { line ->
                Text(
                    text = correctedString(line.id, *line.args.toTypedArray()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** One paragraph of «Почему?»: a string and its arguments, so the choice of paragraphs is asked on the JVM. */
data class WhyLine(@param:StringRes val id: Int, val args: List<String> = emptyList())

/**
 * The paragraphs of «Почему?», in order: the reason (by the catalog's code),
 * how sure the survey is, the modifiers, where the password goes when the app
 * can sign in, and which survey this comes from.
 *
 * Every reason and modifier is a `when` with a literal `R.string` in each
 * branch, so the unused-string guard sees every one of them.
 */
fun whyLines(
    why: WhyUi,
    region: String,
    system: String,
    mainSystem: String?,
    month: String?,
): List<WhyLine> = buildList {
    val year = why.schoolYear ?: UnknownYear
    add(
        when (why.reason) {
            WhyReason.PRIMARY -> WhyLine(R.string.onboarding_why_primary, listOf(region, system, year))
            WhyReason.TOR_PRIMARY -> WhyLine(R.string.onboarding_why_tor_primary, listOf(region, system))
            WhyReason.FRONT_END ->
                WhyLine(R.string.onboarding_why_front_end, listOf(region, system, mainSystem ?: system))
            WhyReason.MOVING_TO -> WhyLine(R.string.onboarding_why_moving_to, listOf(region, system))
            // The generator recommends the unreachable diary itself here
            // (nothing else is the region's), so the sentence names no system
            // as the answer: it says why this one cannot be opened.
            WhyReason.UNREACHABLE -> WhyLine(R.string.onboarding_why_unreachable, listOf(region))
            WhyReason.UNSUPPORTED -> WhyLine(R.string.onboarding_why_unsupported, listOf(region, system))
            WhyReason.NO_DIARY -> WhyLine(R.string.onboarding_why_no_diary, listOf(region, year))
        },
    )
    when (why.confidence) {
        "high" -> add(WhyLine(R.string.onboarding_why_confidence_high))
        "medium" -> add(WhyLine(R.string.onboarding_why_confidence_medium))
        "low" -> add(WhyLine(R.string.onboarding_why_confidence_low))
    }
    for (modifier in WhyModifier.entries) {
        if (modifier !in why.modifiers) continue
        add(
            when (modifier) {
                WhyModifier.ESIA_ONLY -> WhyLine(R.string.onboarding_why_esia_only)
                WhyModifier.UNVERIFIED -> WhyLine(R.string.onboarding_why_unverified)
            },
        )
    }
    why.host?.let { add(WhyLine(R.string.onboarding_why_privacy, listOf(it))) }
    month?.let { add(WhyLine(R.string.onboarding_why_source, listOf(it))) }
}

/** «сентябрь 2026» out of the catalog's `2026-09`; `null` for anything else. */
fun surveyMonthText(month: String?, locale: java.util.Locale): String? =
    month?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        ?.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))

/** A school year the catalog did not state, which no sentence should print as «null». */
private const val UnknownYear = "—"

private const val PETERSBURG_PLATFORM = "petersburg"
