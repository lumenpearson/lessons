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
