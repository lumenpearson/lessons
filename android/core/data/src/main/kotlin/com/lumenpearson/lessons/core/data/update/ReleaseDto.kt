package com.lumenpearson.lessons.core.data.update

import com.lumenpearson.lessons.core.data.repository.ReleaseInfo
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of `GET /repos/{owner}/{repo}/releases`: the fields the sheet
 * needs and nothing else.
 *
 * Everything defaults, so one odd release cannot fail the whole list; a
 * release with no usable tag is dropped by the version parse instead.
 */
@Serializable
internal data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("assets") val assets: List<ReleaseAssetDto> = emptyList(),
)

@Serializable
internal data class ReleaseAssetDto(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    @SerialName("size") val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

/**
 * The APK to offer, if the release carries one.
 *
 * The workflow attaches one file, but a release assembled by hand may carry a
 * debug build next to it, and the one named "release" is the one a user should
 * get; failing that, any not named "debug"; failing that, whatever is there
 * beats nothing.
 */
private fun ReleaseDto.apkAsset(): ReleaseAssetDto? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    return apks.firstOrNull { it.name.contains("release", ignoreCase = true) }
        ?: apks.firstOrNull { !it.name.contains("debug", ignoreCase = true) }
        ?: apks.firstOrNull()
}

internal fun ReleaseDto.toReleaseInfo(): ReleaseInfo {
    val apk = apkAsset()
    return ReleaseInfo(
        tag = tagName.trim(),
        // The workflow titles a release with its tag; a blank title on a
        // hand-made one is shown the same way rather than as nothing.
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: tagName.trim(),
        notes = body.orEmpty(),
        htmlUrl = htmlUrl,
        publishedAt = publishedAt?.let { raw ->
            try {
                Instant.parse(raw)
            } catch (_: DateTimeParseException) {
                null
            }
        },
        isPreRelease = prerelease,
        apkUrl = apk?.browserDownloadUrl?.takeIf { it.isNotBlank() },
        apkBytes = apk?.size,
    )
}
