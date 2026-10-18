package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManageRepository
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the class card's sheet does: the card and its join mode, the school
 * directory, and deleting the class and leaving it afterwards.
 *
 * Holds no state of its own: everything it reads and writes is
 * [ManagementPlumbing.state], so a refusal met here takes the page away from
 * every sheet at once. Its own two launches, the search and the leaving, run
 * in [viewModelScope], the view model's, which is why the parameter carries
 * that name.
 */
internal class ClassCardActions(
    private val plumbing: ManagementPlumbing,
    private val repository: ManageRepository,
    private val session: SessionRepository,
    private val viewModelScope: CoroutineScope,
) {

    fun loadClass() = plumbing.read({ it.copy(classCard = it.classCard.copy(loading = true, failure = null)) }) {
        val result = repository.classCard()
        plumbing.finish(result) { current, value, failure ->
            current.copy(classCard = Remote(value ?: current.classCard.value, false, failure))
        }
    }

    /**
     * Saves the four boxes against the card they were filled in from.
     *
     * The card comes out of the state rather than off the screen: the
     * repository needs it to send only the fields that actually changed, and
     * the state is where the form's own initial values came from.
     */
    fun saveClass(edit: ClassEdit) = plumbing.write {
        val before = plumbing.state.value.classCard.value ?: return@write Result.success(Unit)
        val result = repository.updateClass(before, edit)
        result.onSuccess { card ->
            plumbing.state.update {
                it.copy(classCard = Remote(card), notice = ManagementNotice.ClassSaved)
            }
        }
        result
    }

    /**
     * Turns personal invites on, or gives the class code back.
     *
     * Its own write and not part of [saveClass] for the reason
     * [ManageRepository.setJoinMode] is its own call: this is a row that is
     * tapped, not a form that is filled in. The answer replaces the card rather
     * than being merged into it — the server has just re-read the class, so its
     * copy is newer than the one the tap was made against.
     */
    fun setJoinMode(mode: ClassJoinMode) = plumbing.write {
        val result = repository.setJoinMode(mode)
        result.onSuccess { card ->
            plumbing.state.update {
                it.copy(
                    classCard = Remote(card),
                    // The card the server answered with, not the mode that was
                    // asked for: if those two ever differ, the screen must show
                    // what the class is rather than what was requested.
                    notice = ManagementNotice.JoinModeChanged(card.joinMode),
                )
            }
        }
        result
    }

    /**
     * Searches, once, and keeps everything it found.
     *
     * Not a `write`: nothing of ours changes, and the page's one write flag is
     * what stops two edits of the same class at once — a search that took it
     * would grey out «Сохранить» while somebody was looking for their school.
     */
    fun searchSchools(query: String) {
        if (plumbing.state.value.gone != null) return
        plumbing.state.update {
            it.copy(
                schoolSearch = it.schoolSearch.copy(
                    query = query,
                    searching = true,
                    failure = null,
                    unavailable = null,
                ),
            )
        }
        viewModelScope.launch {
            val result = repository.searchSchools(query)
            val failure = result.exceptionOrNull()?.let(ManageFailure::of)
            if (plumbing.note(failure)) return@launch
            val found = result.getOrNull()
            plumbing.state.update {
                if (it.gone != null || it.schoolSearch.query != query) {
                    // A later query is already on screen; this answer is about a
                    // search the person has moved on from.
                    it
                } else {
                    it.copy(
                        schoolSearch = it.schoolSearch.copy(
                            results = found?.items.orEmpty(),
                            page = 1,
                            total = found?.total ?: 0,
                            truncated = found?.truncated == true,
                            searching = false,
                            searched = true,
                            // A directory that is off is not a failure to draw
                            // in red: it is the sentence telling somebody to
                            // type the name, which is what the box above is for.
                            unavailable = (failure as? ManageFailure.Unavailable)
                                ?.let { off -> off.detail ?: off.message },
                            failure = failure.takeIf { f -> f !is ManageFailure.Unavailable },
                        ),
                    )
                }
            }
        }
    }

    fun showSchoolPage(page: Int) {
        plumbing.state.update {
            val search = it.schoolSearch
            it.copy(schoolSearch = search.copy(page = page.coerceIn(1, search.pages)))
        }
    }

    /** Forgets the last search — the sheet closed, or a school was chosen. */
    fun clearSchoolSearch() {
        plumbing.state.update { it.copy(schoolSearch = SchoolSearch()) }
    }

    /**
     * Deletes the class, and with it this phone's own token.
     *
     * [classDeleted] is set rather than the page being reloaded: there is
     * nothing left to load, and the next call would be a `401` that would be
     * shown as "you were signed out" — true, but a strange thing to tell
     * somebody who has just deliberately deleted their class.
     *
     * The session is *not* dropped here, so that the sheet can say what
     * happened before the app leaves; [leaveDeletedClass] does it when that
     * sheet is dismissed. Nothing depends on the user pressing the button
     * though — the token is already gone server-side, so the next sync's `401`
     * drops the session anyway.
     */
    fun deleteClass(confirmName: String) = plumbing.write {
        val result = repository.deleteClass(confirmName)
        result.onSuccess { plumbing.state.update { it.copy(classDeleted = true) } }
        result
    }

    /**
     * Leaves the class that was just deleted: token gone, cache gone.
     *
     * Without this the app kept the session and the whole cached timetable of
     * a class that no longer existed — it said «класс удалён» and then went on
     * drawing its lessons, never reached the join screen, and so never ran the
     * wipe that joining a new class does on the way in. The old class was then
     * still on screen after joining a new one.
     *
     * The class that was deleted and no other. A phone may hold several, and
     * an admin deleting one of their classes must not be signed out of the
     * ones they only attend: `signOut` would drop every token on the device,
     * and the only way back into the others is another join code from their
     * admins.
     */
    fun leaveDeletedClass() {
        if (!plumbing.state.value.classDeleted) return
        viewModelScope.launch { session.leaveActive() }
    }
}
