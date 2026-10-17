package com.lumenpearson.lessons.core.data.github

import java.net.URLEncoder

/**
 * The repository this build belongs to: where it lists its updates, files its
 * bug reports, reads its guide, offers a translation correction and asks who
 * may open the developer mode.
 *
 * Named by the build rather than written here (#469). While the owner and the
 * name were constants, an APK built from a fork offered this repository's
 * releases as its updates — signed with another key, so the installer refused
 * them over the fork's own install, and the fork's releases were never offered
 * at all — and filed its users' bug reports in this repository's tracker, with
 * nothing in the issue saying which build had sent it. `apk.yml` passes the
 * repository it runs in as `LESSONS_BUILD_REPOSITORY`, the same value the about
 * page's badge already showed, so a fork's APK follows the fork with nothing
 * configured.
 *
 * Public because `:app` builds two addresses of its own from it: the browser's
 * «new issue» page and «Исходный код».
 */
data class GithubProject(val owner: String, val name: String) {

    /** `owner/name`, as GitHub writes a repository's full name. */
    val fullName: String
        get() = "$owner/$name"

    /** The repository's page on the site. */
    val webUrl: String
        get() = "${GithubApi.WEB_BASE}/$fullName"

    /**
     * GitHub's «new issue» page with the title and body filled in: the way to
     * file a report without signing in, since the browser has the reader's
     * session and the app never needs one.
     */
    fun newIssueUrl(title: String, body: String): String {
        fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
        return "$webUrl/issues/new?title=${enc(title)}&body=${enc(body)}"
    }

    companion object {

        /**
         * Where a build that names no repository reports: one built by hand
         * from a clone, or by a workflow that does not pass the property. This
         * is the one place the upstream's name is written, and
         * `UpstreamNameTest` holds that it stays the one.
         */
        val UPSTREAM = GithubProject(owner = "lumenpearson", name = "lessons")

        /**
         * GitHub's own rules for the two halves: an owner is letters, digits
         * and single hyphens; a repository name adds the dot and the
         * underscore. `.` and `..` are not names, and would walk the path.
         */
        private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")
        private val NAME = Regex("[A-Za-z0-9._-]+")

        /**
         * The repository a build was given as `owner/name`, or [UPSTREAM]
         * when it was given none.
         *
         * `app/build.gradle.kts` refuses a value of any other shape before
         * anything compiles, so here a malformed one can only come from a
         * caller that skipped the build; it falls back rather than throwing,
         * because this runs in `Application.onCreate`, where a throw is an app
         * that never opens.
         */
        fun fromBuild(repository: String): GithubProject {
            val parts = repository.trim().split('/')
            if (parts.size != 2) return UPSTREAM
            val (owner, name) = parts
            val valid = OWNER.matches(owner) && NAME.matches(name) && name != "." && name != ".."
            return if (valid) GithubProject(owner, name) else UPSTREAM
        }
    }
}
