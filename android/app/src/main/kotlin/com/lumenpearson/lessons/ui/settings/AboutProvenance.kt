package com.lumenpearson.lessons.ui.settings

import com.lumenpearson.lessons.BuildConfig

/**
 * What this particular APK can say about where it came from.
 *
 * All of it is passed in at build time — see the block in `app/build.gradle.kts`
 * — because a build keeps no memory of the checkout that produced it. An
 * installed APK cannot work out its own repository, branch or commit; the only
 * thing that ever knew is whatever ran the build.
 *
 * Every field is empty on a build that was not told, which is what a fresh
 * clone with no configuration produces, and every badge that reads an empty one
 * leaves itself out. That is the whole reason this is a type rather than six
 * reads of `BuildConfig` in a composable: «which of these do I actually have»
 * is a question with one answer, asked in one place.
 */
internal data class BuildProvenance(
    val repository: String,
    val ref: String,
    val commit: String,
    val number: String,
    val builtAt: String,
) {

    /** Nothing was passed in, so this was built by hand rather than by CI. */
    val isLocal: Boolean
        get() = repository.isBlank() && commit.isBlank() && number.isBlank()

    /**
     * The commit, cut to the seven characters a person reads.
     *
     * Seven is what `git log --oneline` prints and what GitHub shows, so it is
     * the form somebody will actually compare against a pull request. The full
     * forty are still what [commitUrl] uses, because a URL is not read.
     */
    val shortCommit: String
        get() = commit.take(SHORT_COMMIT)

    /** The repository's page, or null when this build was not told its name. */
    val repositoryUrl: String?
        get() = repository.takeIf { it.isNotBlank() }?.let { "https://github.com/$it" }

    /** That exact commit, which is the link worth having: it pins the diff. */
    val commitUrl: String?
        get() = if (repository.isBlank() || commit.isBlank()) {
            null
        } else {
            "https://github.com/$repository/commit/$commit"
        }

    internal companion object {

        private const val SHORT_COMMIT = 7

        /** What this build was told, as the build file wrote it. */
        fun current(): BuildProvenance = BuildProvenance(
            repository = BuildConfig.BUILD_REPOSITORY,
            ref = BuildConfig.BUILD_REF,
            commit = BuildConfig.BUILD_COMMIT,
            number = BuildConfig.BUILD_NUMBER,
            builtAt = BuildConfig.BUILD_TIME,
        )
    }
}
