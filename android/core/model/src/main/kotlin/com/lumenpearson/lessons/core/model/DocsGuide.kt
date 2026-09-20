package com.lumenpearson.lessons.core.model

/**
 * The guide the app draws, as the screen receives it.
 *
 * The text is no longer a resource. It is written as Markdown in `docs/app/`,
 * fetched from the repository, and parsed into these types — which is why they
 * live here rather than in `:app`: this module is pure JVM, so the parser's
 * tests run without a device, and it is the one module
 * `compose-stability.conf` promises the Compose compiler is stable. A page that
 * arrived over the network and was parsed into an unstable type would be
 * compared by identity, and the screen would keep the copy it already had.
 *
 * The blocks are the same four shapes the guide has always been drawn in. What
 * changed is where they come from: a `##` heading starts a page, a paragraph is
 * a paragraph, `-` is a list of peers, `1.` with a bold lead is a numbered step,
 * and `>` is the aside a skimmer must not skim past.
 */
data class DocsGuide(
    /** The `#` line: a name for the document, not for any of its pages. */
    val title: String,
    /** `ru` or `en` — which of the two files this was parsed from. */
    val language: String,
    val pages: List<DocsGuidePage>,
)

/**
 * One page: what the toolbar shows, and what the page says.
 *
 * @param id the stable name in the metadata comment, in both languages. It is
 *   what the icon is chosen by and what a remembered position refers to, so it
 *   must not be translated — `DocsGuideParityTest` holds the two files to the
 *   same ids in the same order.
 * @param label the short name the floating toolbar carries; a bar gives it
 *   80 dp, while [title] gets a line of its own.
 */
data class DocsGuidePage(
    val id: String,
    val title: String,
    val label: String,
    val summary: String,
    val blocks: List<DocsBlock>,
)

/** One piece of a page. */
sealed interface DocsBlock {

    /** A run of prose. The default, and what most of the guide is. */
    data class Paragraph(val spans: List<DocsSpan>) : DocsBlock

    /**
     * Several things of the same kind, each worth finding on its own — the four
     * notification triggers, the four roles, the eight management pages.
     */
    data class Points(val items: List<DocsLine>) : DocsBlock

    /**
     * An aside the reader needs but did not ask for: a caveat, a version
     * difference, a promise about a password.
     */
    data class Note(val spans: List<DocsSpan>) : DocsBlock

    /**
     * One numbered stage of something done in order.
     *
     * The number is the one the Markdown carries rather than the block's
     * position, because the list is also where paragraphs and notes live: a step
     * that counted itself would start again from one the moment a sentence was
     * added between two steps.
     */
    data class Step(
        val number: Int,
        val title: List<DocsSpan>,
        val text: List<DocsSpan>,
    ) : DocsBlock
}

/**
 * One line of a list.
 *
 * A named type rather than `List<List<DocsSpan>>`, because the nested one is
 * unreadable at every call site and says nothing about what the inner list is.
 */
data class DocsLine(val spans: List<DocsSpan>)

/**
 * A run of text that is drawn one way.
 *
 * The guide is prose with three kinds of mark in it — bold, code and links —
 * and a renderer that took the whole paragraph as one string would draw the
 * asterisks. Splitting at the marks is what lets the screen build an
 * `AnnotatedString` without knowing anything about Markdown.
 *
 * @param link the address a `[text](address)` carries, or `null` for ordinary
 *   text. A link with nothing to open is not a link: the parser drops the
 *   brackets and keeps the words.
 */
data class DocsSpan(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** Where the copy on screen came from. */
enum class DocsOrigin {
    /** The files built into the APK. Every install starts here. */
    BUNDLED,

    /** A copy fetched earlier and kept on this phone. */
    STORED,

    /** Fetched from the repository just now. */
    NETWORK,
}

/**
 * Which documentation this is, as the banner states it.
 *
 * Separate from [DocsGuide] because it is about the copy rather than about the
 * text: the same guide can arrive from three places, and the one thing a reader
 * looking at a stale page needs is which of them it was.
 *
 * @param appVersion the version of the app this documentation was written for.
 *   It is stated whether or not it matches, because a guide describing a
 *   different build is the explanation for a screen that is not where the page
 *   says it is.
 */
data class DocsRelease(
    val version: Int,
    val updated: String,
    val appVersion: String,
    val origin: DocsOrigin,
)
