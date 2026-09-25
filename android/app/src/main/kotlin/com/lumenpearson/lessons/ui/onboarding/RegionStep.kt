package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.catalog.MatchVia
import com.lumenpearson.lessons.core.data.upstream.DiarySchoolSearch
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.diary.asText
import com.lumenpearson.lessons.ui.diary.localized
import com.lumenpearson.lessons.ui.diary.offersRetry
import java.text.Collator

/**
 * «Ваш регион»: all eighty-nine, searched as they are typed.
 *
 * A region's name, an alias, a city or the subject code finds it in the
 * bundled catalog at once, with no request. A school's name is asked of the
 * server's directory once the typing pauses, and the answer is the regions a
 * school of that name may be in — or, for a name every region has («школа
 * № 5»), a request to pick the region instead. Whatever the directory says or
 * fails to say, the list below still works: every failure ends in «выберите
 * регион из списка».
 */
@Composable
internal fun RegionStep(
    viewModel: OnboardingViewModel,
    onBack: (() -> Unit)?,
) {
    val flow by viewModel.flow.collectAsStateWithLifecycle()
    val search by viewModel.region.state.collectAsStateWithLifecycle()
    val selected = flow?.choices?.region
    val russian = Locale.current.language == "ru"

    // A blank query lists every region, and a reader looks for a name in the
    // alphabet they read it in, not in the constitution's order. A query
    // keeps the search's own ranking — the best match first.
    val rows = remember(search.rows, search.query, russian) {
        if (search.query.isBlank()) {
            val collator = Collator.getInstance(if (russian) java.util.Locale.forLanguageTag("ru") else java.util.Locale.ENGLISH)
            search.rows.sortedWith(compareBy(collator) { if (russian) it.nameRu else it.nameEn })
        } else {
            search.rows
        }
    }

    ListStepScaffold(
        actions = {
            OnboardingActions(
                label = correctedString(R.string.onboarding_action_continue),
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                onBack = onBack,
                enabled = selected != null,
                onClick = viewModel::proceedFromRegion,
            )
        },
    ) {
        item(key = "title") {
            OnboardingTitle(
                title = correctedString(R.string.onboarding_region_title),
                subtitle = correctedString(R.string.onboarding_region_subtitle),
            )
        }
        item(key = "search") {
            SearchBox(
                value = search.query,
                onValueChange = viewModel.region::type,
                label = correctedString(R.string.onboarding_region_search_label),
                clearLabel = correctedString(R.string.onboarding_region_clear),
                onSearch = viewModel.region::submit,
            )
        }

        schoolAnswer(search = search, selected = selected, onPick = viewModel::pickRegion)

        if (rows.isEmpty() && search.query.isNotBlank() && !search.searching) {
            item(key = "regions-empty") {
                EmptyState(
                    title = correctedString(R.string.onboarding_region_empty_title),
                    description = correctedString(R.string.onboarding_region_empty_text),
                    icon = Icons.Rounded.Map,
                )
            }
        } else if (rows.isNotEmpty()) {
            item(key = "regions") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(
                        title = correctedString(
                            if (search.query.isBlank()) R.string.onboarding_region_all else R.string.onboarding_region_found,
                        ),
                    )
                    RoundedCardContainer {
                        rows.forEach { row ->
                            ChoiceRow(
                                title = (if (russian) row.nameRu else row.nameEn).ifBlank { row.nameRu },
                                subtitle = listOfNotNull(matchedLine(row), offerLine(row.offer)).joinToString("\n"),
                                icon = Icons.Rounded.Map,
                                tone = accentTone(0),
                                selected = row.key == selected,
                                onClick = { viewModel.pickRegion(row.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The directory's part of the list: where a school of that name may be, or why it cannot say. */
private fun androidx.compose.foundation.lazy.LazyListScope.schoolAnswer(
    search: RegionSearchUi,
    selected: String?,
    onPick: (key: String, hint: String?) -> Unit,
) {
    if (search.searching) {
        item(key = "school-searching") { NoteCard(correctedString(R.string.onboarding_region_school_searching)) }
        return
    }
    when (val answer = search.school) {
        SchoolAnswer.NotAsked -> Unit
        SchoolAnswer.Generic -> item(key = "school-generic") {
            NoteCard(correctedString(R.string.onboarding_region_school_generic))
        }
        is SchoolAnswer.Failed -> item(key = "school-failed") {
            NoteCard(
                when (answer.reason) {
                    DirectoryFailure.WAIT -> answer.minutes
                        ?.let { pluralStringResource(R.plurals.onboarding_region_school_wait, it, it) }
                        ?: correctedString(R.string.onboarding_region_school_busy)
                    DirectoryFailure.NO_SERVER -> correctedString(R.string.onboarding_region_school_no_server)
                    DirectoryFailure.OFFLINE -> correctedString(R.string.onboarding_region_school_offline)
                    DirectoryFailure.OFF -> correctedString(R.string.onboarding_region_school_off)
                },
            )
        }
        is SchoolAnswer.Found -> {
            // A region the catalog cannot place cannot be picked — there is no
            // diary to offer for it — so it is left out rather than drawn dead.
            val placed = answer.hits.filter { it.regionKey != null }
            if (placed.isEmpty()) {
                item(key = "school-empty") { NoteCard(correctedString(R.string.onboarding_region_school_empty)) }
            } else {
                item(key = "school-hits") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = correctedString(R.string.onboarding_region_school_section, search.query.trim()))
                        RoundedCardContainer {
                            placed.forEach { hit ->
                                val key = hit.regionKey ?: return@forEach
                                ChoiceRow(
                                    title = localized(hit.nameRu, hit.nameEn) ?: hit.label.orEmpty(),
                                    subtitle = correctedString(
                                        R.string.onboarding_region_school_group,
                                        pluralStringResource(R.plurals.onboarding_region_school_count, hit.schools, hit.schools),
                                        hit.examples.take(ExamplesShown).joinToString("; "),
                                    ),
                                    icon = Icons.Rounded.School,
                                    tone = accentTone(3),
                                    selected = key == selected,
                                    onClick = { onPick(key, search.query.trim()) },
                                )
                            }
                        }
                    }
                }
                if (answer.truncated) {
                    item(key = "school-truncated") {
                        NoteCard(correctedString(R.string.onboarding_region_school_truncated))
                    }
                }
            }
        }
    }
}

/** Why a region is in the results when its name is not what matched. */
@Composable
private fun matchedLine(row: RegionRowUi): String? {
    val matched = row.matched ?: return null
    return when (row.via) {
        MatchVia.CITY -> correctedString(R.string.onboarding_region_by_city, matched)
        MatchVia.ALIAS -> correctedString(R.string.onboarding_region_by_alias, matched)
        MatchVia.CODE -> correctedString(R.string.onboarding_region_by_code, matched)
        else -> null
    }
}

/** What can be done in a region, in a few words. */
@Composable
private fun offerLine(offer: RegionOffer): String {
    val system = localized(offer.systemRu, offer.systemEn).orEmpty()
    return when (offer.kind) {
        OfferKind.SIGN_IN -> correctedString(R.string.onboarding_region_sign_in, system)
        OfferKind.GOSUSLUGI -> correctedString(R.string.onboarding_region_gosuslugi, system)
        OfferKind.SITE -> correctedString(R.string.onboarding_region_site, system)
        OfferKind.UNSUPPORTED -> correctedString(R.string.onboarding_region_unsupported, system)
        OfferKind.PAPER -> correctedString(R.string.onboarding_region_paper)
    }
}

/** How many of a region's matching school names are listed under it. */
private const val ExamplesShown = 3

/**
 * «Ваша школа»: the region's own «Сетевой город» list, searched on the
 * region's server straight from the phone — the server the password goes to
 * next, so a failure here is the honest early warning. Shown only where the
 * signable diary needs a school (`regionPlanOf`).
 */
@Composable
internal fun SchoolStep(
    viewModel: OnboardingViewModel,
    onBack: (() -> Unit)?,
) {
    val flow by viewModel.flow.collectAsStateWithLifecycle()
    val search by viewModel.school.state.collectAsStateWithLifecycle()
    val page by viewModel.provider.collectAsStateWithLifecycle()
    val picked = flow?.choices?.school
    val signable = page?.rows?.firstOrNull {
        it.kind == ProviderRowKind.SIGN_IN || it.kind == ProviderRowKind.NEEDS_SCHOOL
    }

    ListStepScaffold(
        actions = {
            OnboardingActions(
                label = correctedString(R.string.onboarding_action_continue),
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                onBack = onBack,
                enabled = picked != null,
                onClick = viewModel::proceedFromSchool,
            )
        },
    ) {
        item(key = "title") {
            OnboardingTitle(
                title = correctedString(R.string.onboarding_school_title),
                subtitle = page?.let { current ->
                    correctedString(
                        R.string.onboarding_school_subtitle,
                        localized(current.regionRu, current.regionEn).orEmpty(),
                        localized(signable?.nameRu, signable?.nameEn).orEmpty(),
                    )
                },
            )
        }
        item(key = "search") {
            SearchBox(
                value = search.query,
                onValueChange = viewModel.school::type,
                label = correctedString(R.string.onboarding_school_search_label),
                clearLabel = correctedString(R.string.onboarding_region_clear),
                onSearch = viewModel.school::retry,
            )
        }
        when {
            search.searching -> item(key = "loading") { SkeletonGroup(rows = 3) }

            search.problem != null -> item(key = "problem") {
                val problem = search.problem ?: return@item
                EmptyState(
                    title = correctedString(R.string.diary_failure_title),
                    description = problem.asText(),
                    actionLabel = correctedString(R.string.onboarding_action_retry).takeIf { problem.offersRetry },
                    onActionClick = viewModel.school::retry.takeIf { problem.offersRetry },
                )
            }

            search.query.trim().length < DiarySchoolSearch.MIN_QUERY_LENGTH -> item(key = "hint") {
                NoteCard(
                    pluralStringResource(
                        R.plurals.onboarding_school_min_chars,
                        DiarySchoolSearch.MIN_QUERY_LENGTH,
                        DiarySchoolSearch.MIN_QUERY_LENGTH,
                    ),
                )
            }

            search.searched && search.rows.isEmpty() -> item(key = "empty") {
                NoteCard(correctedString(R.string.onboarding_school_empty))
            }

            else -> item(key = "schools") {
                RoundedCardContainer {
                    search.rows.forEach { row ->
                        ChoiceRow(
                            title = row.name,
                            subtitle = row.address,
                            icon = Icons.Rounded.Place,
                            tone = accentTone(1),
                            selected = picked?.id == row.id,
                            onClick = { viewModel.pickSchool(row) },
                        )
                    }
                }
            }
        }
        item(key = "missing") {
            TextButton(onClick = viewModel::skipSchool, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = correctedString(R.string.onboarding_school_missing),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
