package com.lumenpearson.lessons.ui.settings

import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.UpdateCheck
import com.lumenpearson.lessons.core.data.repository.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The update check and the release sheet: a check by hand, the quieter one at
 * launch, and the sheet both of them raise.
 *
 * Owns [releaseSheet], which the view model's `remote` combine reads beside the
 * repository's own state. Launches in [viewModelScope], the view model's, which
 * is why the parameter carries that name; it never makes a scope of its own.
 */
internal class SettingsUpdates(
    private val settingsRepository: SettingsRepository,
    private val updateRepository: UpdateRepository,
    private val viewModelScope: CoroutineScope,
) {

    val releaseSheet = MutableStateFlow(false)

    /**
     * Asks GitHub whether there is something newer, and shows the answer.
     *
     * Manual: the pre-release setting is read at the moment of the tap, so
     * flipping the switch and tapping "проверить" does what it looks like it
     * does. The sheet is raised whatever the verdict — "всё актуально" with the
     * installed release's notes is an answer too, and the one Essentials gives.
     */
    fun checkForUpdates() {
        viewModelScope.launch {
            val includePrerelease = settingsRepository.settings.first().includePrerelease
            updateRepository.check(includePrerelease)
            releaseSheet.value = true
        }
    }

    /**
     * The check the app runs on its own at launch.
     *
     * Quieter than [checkForUpdates] in every way: it only runs when the
     * setting allows, at most once a day, and it raises the sheet only for a
     * release that is new, wanted, and not one the user has already said
     * "позже" to. A failure is not shown at all — nobody asked.
     */
    fun checkForUpdatesAtLaunch() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (!settings.autoCheckUpdates) return@launch
            val last = updateRepository.lastCheckMillis() ?: 0L
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MILLIS) return@launch
            val verdict = updateRepository.check(settings.includePrerelease)
            if (verdict !is UpdateCheck.Available || !settings.notifyNewUpdates) return@launch
            if (verdict.release.tag == updateRepository.dismissedTag.value) return@launch
            releaseSheet.value = true
        }
    }

    /** Raises the release sheet over whatever the last check found. */
    fun showReleaseSheet() {
        releaseSheet.value = true
        // A sheet over "ещё не проверялось" would be a sheet about nothing.
        if (updateRepository.state.value == UpdateCheck.Idle) checkForUpdates()
    }

    fun hideReleaseSheet() {
        releaseSheet.value = false
    }

    /** "Позже": stops the automatic check from raising this tag again. */
    fun dismissUpdate(tag: String) {
        releaseSheet.value = false
        viewModelScope.launch { updateRepository.dismiss(tag) }
    }

    /**
     * Turning pre-releases on is followed by a check straight away, so the
     * switch has a visible consequence rather than waiting for the next launch.
     */
    fun enablePrereleases() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(includePrerelease = true) }
            updateRepository.check(includePrerelease = true)
            releaseSheet.value = true
        }
    }

    private companion object {
        /** Once a day is plenty for a school app; see `UpdateRepository.lastCheckMillis`. */
        private const val AUTO_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
    }
}
