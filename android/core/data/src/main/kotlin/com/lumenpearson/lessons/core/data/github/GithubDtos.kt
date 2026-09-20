package com.lumenpearson.lessons.core.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response of `POST https://github.com/login/device/code`.
 *
 * Every field defaults, so a shape change or an error body decodes rather than
 * throws; the repository checks [deviceCode] for the empty string instead and
 * reports [error] when GitHub sent one.
 */
@Serializable
internal data class DeviceCodeDto(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("expires_in") val expiresIn: Int = 900,
    @SerialName("interval") val interval: Int = 5,
    @SerialName("error") val error: String? = null,
)

/**
 * Response of `POST https://github.com/login/oauth/access_token`, polled.
 *
 * GitHub answers HTTP 200 whether the code has been entered or not and says
 * which in [error]: `authorization_pending` and `slow_down` mean keep polling,
 * everything else means stop. So one DTO carries both halves.
 */
@Serializable
internal data class AccessTokenDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/** Response of `GET /user`; only what the settings row shows. */
@Serializable
internal data class UserDto(
    @SerialName("login") val login: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** Body of `POST /repos/{owner}/{repo}/issues`. */
@Serializable
internal data class IssueRequestDto(
    @SerialName("title") val title: String,
    @SerialName("body") val body: String,
    @SerialName("labels") val labels: List<String>,
)

/** Response of the same: the page to open once the issue exists. */
@Serializable
internal data class IssueResponseDto(
    @SerialName("html_url") val htmlUrl: String = "",
)

/**
 * Response of `GET /repos/{owner}/{repo}` — used only to find out whether a
 * fork exists yet.
 *
 * GitHub answers `POST /forks` with `202 Accepted` and creates the fork
 * afterwards, so the repository polls this until it stops answering `404`.
 * Nothing in the body is read; the status code is the whole answer, and the
 * type exists so the call site reads like the others.
 */
@Serializable
internal data class RepositoryDto(
    @SerialName("full_name") val fullName: String = "",
    @SerialName("default_branch") val defaultBranch: String = "main",
)

/** Response of `GET /repos/{owner}/{repo}/git/ref/{ref}`. */
@Serializable
internal data class RefDto(
    @SerialName("object") val target: RefTargetDto = RefTargetDto(),
)

/** The commit a ref points at. */
@Serializable
internal data class RefTargetDto(
    @SerialName("sha") val sha: String = "",
)

/** Body of `POST /repos/{owner}/{repo}/git/refs`. */
@Serializable
internal data class CreateRefRequestDto(
    @SerialName("ref") val ref: String,
    @SerialName("sha") val sha: String,
)

/**
 * Response of `GET /repos/{owner}/{repo}/contents/{path}`.
 *
 * @property content the file, base64 with line breaks in it — GitHub wraps at
 *   60 characters, and a decoder that is not told to ignore them returns
 *   nothing.
 * @property sha the blob's, which the update call has to quote back or GitHub
 *   refuses the write as a lost update.
 */
@Serializable
internal data class ContentsDto(
    @SerialName("content") val content: String = "",
    @SerialName("sha") val sha: String = "",
)

/** Body of `PUT /repos/{owner}/{repo}/contents/{path}`. */
@Serializable
internal data class UpdateContentsRequestDto(
    @SerialName("message") val message: String,
    @SerialName("content") val content: String,
    @SerialName("sha") val sha: String,
    @SerialName("branch") val branch: String,
)

/** Body of `POST /repos/{owner}/{repo}/pulls`. */
@Serializable
internal data class PullRequestRequestDto(
    @SerialName("title") val title: String,
    @SerialName("body") val body: String,
    @SerialName("head") val head: String,
    @SerialName("base") val base: String,
    @SerialName("maintainer_can_modify") val maintainerCanModify: Boolean = true,
)

/** Response of the same: the page to open once the pull request exists. */
@Serializable
internal data class PullRequestResponseDto(
    @SerialName("html_url") val htmlUrl: String = "",
)
