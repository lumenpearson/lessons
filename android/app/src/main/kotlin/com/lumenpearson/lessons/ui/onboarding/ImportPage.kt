package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase
import com.lumenpearson.lessons.core.designsystem.component.LessonsWavyProgress
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.diary.asText
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * «Загружаем дневник»: this week and the next, the terms, the homework and the
 * marks, fetched once so the diary home opens from the phone's own copy.
 *
 * The bar moves while a request is out rather than sitting still and jumping:
 * each step says where it will leave the bar ([ImportUi.target]), and the bar
 * creeps most of the way there, slowing as it goes, until the answer puts it
 * where it really is. It never goes backwards, and with animations off it
 * shows only what is done.
 */
@Composable
internal fun ImportPage(viewModel: OnboardingViewModel) {
    val ui by viewModel.importing.state.collectAsStateWithLifecycle()
    val motion = LocalMotion.current
    val shown = remember { Animatable(0f) }

    LaunchedEffect(ui.completed, ui.target, ui.done, ui.failed, motion.enabled) {
        if (ui.completed > shown.value || !motion.enabled) shown.snapTo(ui.completed)
        val stopped = ui.done != null || ui.failed != null || ui.choosing != null
        if (motion.enabled && !stopped && ui.target > shown.value) {
            val aim = shown.value + (ui.target - shown.value) * CreepShare
            shown.animateTo(aim, tween(motion.durationMillis(CreepMillis), easing = LinearOutSlowInEasing))
        }
    }

    // The full bar is seen before the summary slides in; a manual «Дальше»
    // stays underneath for whoever is faster than the pause.
    LaunchedEffect(ui.done) {
        if (ui.done == null) return@LaunchedEffect
        delay(motion.durationMillis(DoneHoldMillis).toLong())
        viewModel.importShown()
    }

    val failed = ui.failed
    val action = importActionOf(ui)
    StepScaffold(
        actions = {
            when (action) {
                ImportAction.CONTINUE -> OnboardingActions(
                    label = correctedString(R.string.onboarding_action_continue),
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    onClick = viewModel::importShown,
                )
                ImportAction.SIGN_IN_AGAIN -> OnboardingActions(
                    label = correctedString(R.string.onboarding_import_sign_in_again),
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    onClick = viewModel::signInAgain,
                )
                ImportAction.RETRY -> OnboardingActions(
                    label = correctedString(R.string.onboarding_action_retry),
                    icon = Icons.Rounded.Refresh,
                    onClick = viewModel::retryImport,
                )
                // The way out below the card becomes the one action.
                ImportAction.START_OVER -> OnboardingActions(
                    label = correctedString(R.string.onboarding_import_restart),
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    onClick = viewModel::startOver,
                )
                ImportAction.RUNNING -> OnboardingActions(
                    label = correctedString(R.string.onboarding_import_running),
                    icon = Icons.Rounded.CloudDownload,
                    busy = true,
                    onClick = {},
                )
            }
        },
    ) {
        Spacer(Modifier.height(24.dp))
        OnboardingTitle(
            title = correctedString(R.string.onboarding_import_title),
            subtitle = correctedString(R.string.onboarding_import_subtitle),
        )
        Spacer(Modifier.height(24.dp))
        LessonsWavyProgress(
            progress = shown.value,
            description = correctedString(R.string.onboarding_import_progress, (ui.completed * 100).roundToInt()),
        )
        Spacer(Modifier.height(GroupSpacing))

        ui.choosing?.let { students ->
            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = correctedString(R.string.onboarding_import_pick_title))
                NoteCard(correctedString(R.string.onboarding_import_pick_text))
                Spacer(Modifier.height(8.dp))
                RoundedCardContainer {
                    students.forEach { student ->
                        ChoiceRow(
                            title = student.name,
                            subtitle = listOfNotNull(student.className, student.school)
                                .filter { it.isNotBlank() }
                                .joinToString(", ")
                                .ifBlank { null },
                            icon = Icons.Rounded.Person,
                            tone = accentTone(3),
                            selected = false,
                            onClick = { viewModel.pickStudent(student.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(GroupSpacing))
        }

        RoundedCardContainer {
            ImportStages.forEach { stage -> StageRow(ui = ui, stage = stage) }
        }

        if (failed != null) {
            Spacer(Modifier.height(GroupSpacing))
            NoteCard(
                if (failed.needsSignIn) {
                    correctedString(R.string.onboarding_import_expired)
                } else {
                    correctedString(R.string.onboarding_import_failed, failed.problem.asText(firstRun = true))
                },
            )
            if (action != ImportAction.START_OVER) {
                TextButton(onClick = viewModel::startOver, modifier = Modifier.fillMaxWidth()) {
                    Text(correctedString(R.string.onboarding_import_restart))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StageRow(ui: ImportUi, stage: DiaryImportPhase) {
    val state = stageStateOf(ui, stage)
    val done = ui.done
    val subtitle = when (state) {
        StageState.WAITING -> correctedString(R.string.onboarding_import_waiting)
        StageState.RUNNING -> correctedString(R.string.onboarding_import_running)
        StageState.SKIPPED -> correctedString(R.string.onboarding_import_skipped)
        StageState.FAILED -> correctedString(R.string.onboarding_import_stopped)
        StageState.DONE -> when {
            done != null && stage == DiaryImportPhase.SCHEDULE ->
                pluralStringResource(R.plurals.onboarding_import_lessons, done.lessons, done.lessons)
            done != null && stage == DiaryImportPhase.HOMEWORK ->
                pluralStringResource(R.plurals.onboarding_import_assignments, done.homework, done.homework)
            done != null && stage == DiaryImportPhase.MARKS ->
                pluralStringResource(R.plurals.diary_marks_count, done.marks, done.marks)
            else -> correctedString(R.string.onboarding_import_done)
        }
    }
    ChoiceRow(
        title = correctedString(stage.labelRes),
        subtitle = subtitle,
        tone = accentTone(stage.ordinal),
        selected = state == StageState.RUNNING,
        trailing = {
            when (state) {
                StageState.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Icon(
                    imageVector = when (state) {
                        StageState.DONE -> Icons.Rounded.Check
                        StageState.SKIPPED -> Icons.Rounded.RemoveCircleOutline
                        StageState.FAILED -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.Schedule
                    },
                    contentDescription = null,
                    tint = if (state == StageState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
    )
}

private val DiaryImportPhase.labelRes: Int
    get() = when (this) {
        DiaryImportPhase.STUDENTS, DiaryImportPhase.CHOOSE_STUDENT -> R.string.onboarding_import_phase_students
        DiaryImportPhase.PERIODS -> R.string.onboarding_import_phase_periods
        DiaryImportPhase.SCHEDULE -> R.string.onboarding_import_phase_schedule
        DiaryImportPhase.HOMEWORK -> R.string.onboarding_import_phase_homework
        DiaryImportPhase.MARKS -> R.string.onboarding_import_phase_marks
    }

/** How much of the way to a step's target the bar creeps before the answer. */
private const val CreepShare = 0.85f

/** How long the creep takes: longer than most answers, so it rarely stalls at its end. */
private const val CreepMillis = 6_000

/** How long a full bar is left on screen before the summary. */
private const val DoneHoldMillis = 700
