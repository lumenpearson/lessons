package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.model.DocsGuide
import com.lumenpearson.lessons.core.model.DocsRelease
import kotlinx.coroutines.flow.StateFlow

/**
 * The guide, and where this copy of it came from.
 *
 * The two travel together everywhere, because a page on screen is only half an
 * answer: a reader looking at something that does not match the app in front of
 * them needs to know whether they are reading a stored copy and what it was
 * written for.
 */
data class DocsLibrary(
    val guide: DocsGuide,
    val release: DocsRelease,
)

/** Why the documentation could not be brought up to date. */
enum class DocsFailure {
    /** No network, or the request never reached GitHub. The common one. */
    OFFLINE,

    /**
     * GitHub answered and what came back was not a guide — a 404 from a moved
     * file, a proxy's login page, a truncated download. Told apart from
     * [OFFLINE] because the reader can do something about one of them.
     */
    UNREADABLE,
}

/**
 * What the documentation screen draws, as one value.
 *
 * @param library `null` only before the first read finishes; every install has
 *   the bundled copy, so there is no state in which there is nothing to show.
 * @param refreshing whether a fetch is in flight, which is what puts the
 *   loader on the screen.
 * @param failure the last fetch's failure, kept until one succeeds. It is not
 *   cleared by opening the screen: the copy being stale outlives the moment the
 *   fetch failed, and the banner says so for as long as it is true.
 */
data class DocsState(
    val library: DocsLibrary? = null,
    val refreshing: Boolean = false,
    val failure: DocsFailure? = null,
)

/**
 * The documentation: the copy on this phone, and the copy in the repository.
 *
 * Nothing here can leave the reader with no guide. An install carries the
 * markdown in its assets, a fetch that succeeds writes over that in the app's
 * own files, and a fetch that fails changes nothing but [DocsState.failure].
 */
interface DocsRepository {

    val state: StateFlow<DocsState>

    /**
     * Reads the best copy this phone already has — stored first, bundled
     * otherwise — without touching the network.
     *
     * @param language `ru` or `en`; anything else is read as English.
     */
    suspend fun load(language: String)

    /**
     * Asks the repository whether there is a newer guide, and takes it.
     *
     * Cheap when there is nothing to do: the manifest is a few hundred bytes
     * and is compared before any markdown is fetched.
     */
    suspend fun refresh(language: String)
}
