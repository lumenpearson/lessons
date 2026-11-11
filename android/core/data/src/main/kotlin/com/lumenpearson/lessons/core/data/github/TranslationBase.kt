package com.lumenpearson.lessons.core.data.github

import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_OK
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class GithubRead(val code: Int, val body: String)
internal data class TranslationBase(val branch: String, val sha: String)

internal suspend fun translationBase(
    project: GithubProject,
    read: suspend (String) -> GithubRead,
): TranslationBase? {
    val integration = read(refUrl(project, "dev"))
    // A transient failure must not silently send work to production. Only an
    // absent integration ref in another configured repository permits fallback.
    return when {
        integration.code == HTTP_OK -> baseAt("dev", integration)
        integration.code == HTTP_NOT_FOUND &&
            !project.fullName.equals(GithubProject.UPSTREAM.fullName, ignoreCase = true) -> defaultBase(project, read)
        else -> null
    }
}

private suspend fun defaultBase(project: GithubProject, read: suspend (String) -> GithubRead): TranslationBase? {
    val metadata = read(GithubApi.repoUrl(project, ""))
    return if (metadata.code == HTTP_OK) {
        val branch = GithubApi.json.decodeFromString(RepositoryDto.serializer(), metadata.body).defaultBranch
        if (branch.isBlank() || branch == "dev") null else baseAt(branch, read(refUrl(project, branch)))
    } else {
        null
    }
}

private fun refUrl(project: GithubProject, branch: String): String =
    GithubApi.repoUrl(project, "/git/ref").toHttpUrl().newBuilder()
        .addPathSegments("heads/$branch").build().toString()

private fun baseAt(branch: String, response: GithubRead): TranslationBase? {
    if (response.code != HTTP_OK) return null
    val sha = GithubApi.json.decodeFromString(RefDto.serializer(), response.body).target.sha
    return sha.takeIf { it.isNotBlank() }?.let { TranslationBase(branch, it) }
}

internal fun TranslationBase.pullRequestBody(
    project: GithubProject,
    head: String,
    branch: String,
    applied: Int,
    login: String,
): PullRequestRequestDto = PullRequestRequestDto(
    title = "Corrections to the app's strings, sent from the app",
    body = "$applied correction(s) to the app's strings, sent from the app by @$login.",
    head = if (head == project.owner) branch else "$head:$branch",
    base = this.branch,
)
