package com.lumenpearson.lessons.core.data.legal

import android.content.Context
import android.util.Log
import com.lumenpearson.lessons.core.data.docs.DocsMarkdown
import com.lumenpearson.lessons.core.data.docs.docsLanguage
import com.lumenpearson.lessons.core.data.github.GithubApi
import com.lumenpearson.lessons.core.model.DocsGuide
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * The two texts a reader accepts by continuing past the first screen.
 *
 * @property slug the file's stem in `docs/legal/` and at the build's legal
 *   address — `terms.ru.md`, `privacy.en.md`. It is a file name rather than a
 *   label, so it is never translated.
 */
enum class LegalDocument(val slug: String) {
    TERMS("terms"),
    PRIVACY("privacy"),
}

/**
 * Which edition of the texts this APK carries, and from when it applies.
 *
 * Shown above the bundled copy because that copy may be older than the one at
 * the link: an installed APK keeps the edition it was built with for as long as
 * nobody updates it, and a reader comparing the two needs a number to compare.
 */
data class LegalEdition(val edition: Int, val effective: LocalDate)

/** A bundled document, parsed, with the edition it belongs to when the manifest could say. */
data class LegalText(val guide: DocsGuide, val edition: LegalEdition?)

/**
 * The address of [document] in [language] under the build's legal folder, or
 * `null` when there is none worth opening.
 *
 * `null` for a blank folder and for anything but https. The Gradle build
 * already refuses a non-https value, so the second check only matters to a
 * caller that got [base] from somewhere else — and a policy opened over plain
 * HTTP is a policy anybody on the café's network can rewrite before it is read.
 */
fun legalUrl(base: String, document: LegalDocument, language: String): String? {
    val folder = base.trim().trimEnd('/')
    if (!folder.startsWith("https://") || folder.length <= "https://".length) return null
    return "$folder/${document.slug}.${docsLanguage(language)}.md"
}

/**
 * The shape of `docs/legal/legal.json`.
 *
 * Its own name rather than `manifest.json` because `docs/app/` and `docs/legal/`
 * both land at the root of the APK's assets, and `DocsStore` opens
 * `manifest.json` there for the guide: two files of one name in one folder is a
 * build that ships whichever the merger happened to keep.
 */
@Serializable
internal data class LegalManifest(
    val edition: Int = 0,
    val effective: String = "",
    val files: Map<String, Map<String, String>> = emptyMap(),
)

/** `null` when [text] is not the manifest, or names no usable edition or date. */
internal fun parseLegalManifest(text: String?): LegalManifest? {
    if (text.isNullOrBlank()) return null
    return runCatching { GithubApi.json.decodeFromString<LegalManifest>(text) }.getOrNull()
}

/**
 * The edition a manifest names, or `null` when it does not name a real one.
 *
 * A zero edition or an unreadable date is dropped rather than drawn, because the
 * line it would produce — «Редакция 0 от …» — is a claim about a document that
 * nobody made.
 */
internal fun LegalManifest.toEdition(): LegalEdition? {
    if (edition < 1) return null
    val date = runCatching { LocalDate.parse(effective) }.getOrNull() ?: return null
    return LegalEdition(edition, date)
}

/**
 * The file [document] is in for [language]: the manifest's word when it has one,
 * the convention when it does not.
 *
 * The convention is the same one [legalUrl] builds its address from, so the
 * bundled copy and the published one cannot be two different files by accident.
 */
internal fun legalFileName(manifest: LegalManifest?, document: LegalDocument, language: String): String {
    val lang = docsLanguage(language)
    return manifest?.files?.get(document.slug)?.get(lang)?.takeIf { it.isNotBlank() }
        ?: "${document.slug}.$lang.md"
}

/**
 * Reads a bundled legal text through [open], which answers a file's contents
 * by name or `null`.
 *
 * `null` when the file is missing or parses to no sections: a sheet titled
 * «Политика конфиденциальности» over an empty page would read as a policy that
 * says nothing, which is worse than saying the document could not be opened.
 */
internal fun readLegal(open: (String) -> String?, document: LegalDocument, language: String): LegalText? {
    val manifest = parseLegalManifest(open(MANIFEST))
    val lang = docsLanguage(language)
    val markdown = open(legalFileName(manifest, document, lang))?.takeIf { it.isNotBlank() } ?: return null
    val guide = DocsMarkdown.parse(markdown, lang)
    if (guide.pages.isEmpty()) return null
    return LegalText(guide = guide, edition = manifest?.toEdition())
}

/**
 * The terms and the privacy policy as the APK carries them.
 *
 * An object with no state, like `DocsStore.bundledGuide` which it mirrors: an
 * asset is read whole, once per opening of a sheet, and there is nothing to
 * cache between two readers that a second read would not answer as quickly.
 * Not a member of the container for the same reason — nothing here outlives a
 * call.
 *
 * The files are `docs/legal/` itself, mounted as an asset folder by `:app`'s
 * build, so there is one copy of each text in the repository and the APK's is
 * that copy.
 */
object BundledLegal {

    /** Blocking; call it off the main thread. */
    fun read(context: Context, document: LegalDocument, language: String): LegalText? {
        val assets = context.applicationContext.assets
        return readLegal(
            open = { name ->
                runCatching {
                    assets.open(name).use { stream -> stream.readBytes().decodeToString() }
                }.onFailure { failure ->
                    // Not fatal and not silent: it means the build stopped
                    // shipping docs/legal/, which the reader then sees as
                    // «не удалось открыть документ» and nothing else reports.
                    Log.w(TAG, "The APK carries no $name", failure)
                }.getOrNull()
            },
            document = document,
            language = language,
        )
    }

    private const val TAG = "Lessons"
}

/** The manifest's name inside `docs/legal/` and at the asset root. */
internal const val MANIFEST = "legal.json"
