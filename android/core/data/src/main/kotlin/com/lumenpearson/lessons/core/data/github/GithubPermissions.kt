package com.lumenpearson.lessons.core.data.github

import com.lumenpearson.lessons.core.data.developer.RepositoryPermissions
import com.lumenpearson.lessons.core.data.repository.PermissionsAnswer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * What the signed-in account may do in this project's repository: the
 * developer mode's gate.
 *
 * Reads the token from [GithubPreferences] on every call and clears it on a
 * `401`, as the other three do; any other refusal decides nothing (see
 * [repositoryPermissions]).
 */
internal class GithubPermissions(
    private val preferences: GithubPreferences,
    userAgent: Lazy<String>,
) {

    private val userAgent: String by userAgent

    /**
     * The signed-in account's rights here, from `GET /repos/{owner}/{repo}`.
     *
     * A 401 forgets the token, as every other call does; any other refusal is
     * reported and decides nothing, because a GitHub that is down for an hour
     * must not take a standing verdict away (see `DeveloperModeImpl.verify`).
     */
    suspend fun repositoryPermissions(): PermissionsAnswer = withContext(Dispatchers.IO) {
        val token = preferences.token()
        val login = preferences.account.first()?.login
        if (token == null || login == null) {
            PermissionsAnswer.SignedOut
        } else {
            try {
                askPermissions(token, login)
            } catch (failure: IOException) {
                PermissionsAnswer.Failed(failure::class.java.simpleName)
            } catch (failure: SerializationException) {
                PermissionsAnswer.Failed(failure::class.java.simpleName)
            }
        }
    }

    private suspend fun askPermissions(token: String, login: String): PermissionsAnswer {
        val request = GithubApi.apiRequest(GithubApi.repoUrl(""), userAgent)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val (code, body) = GithubApi.client.newCall(request).execute().use { response ->
            response.code to if (response.isSuccessful) response.body.string() else ""
        }
        return when {
            code == HTTP_UNAUTHORISED -> {
                preferences.clear()
                PermissionsAnswer.SignedOut
            }
            body.isEmpty() -> PermissionsAnswer.Failed("HTTP $code")
            else -> {
                val granted = GithubApi.json.decodeFromString(RepositoryAccessDto.serializer(), body).permissions
                PermissionsAnswer.Known(
                    login = login,
                    permissions = RepositoryPermissions(
                        admin = granted?.admin == true,
                        maintain = granted?.maintain == true,
                        push = granted?.push == true,
                    ),
                )
            }
        }
    }
}
