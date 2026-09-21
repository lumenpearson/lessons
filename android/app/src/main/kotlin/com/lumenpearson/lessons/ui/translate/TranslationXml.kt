package com.lumenpearson.lessons.ui.translate

/**
 * Turning a session of corrections back into something that can be pasted into
 * a `strings.xml`.
 *
 * The whole feature stands or falls here. A reader who fixes a comma and gets
 * back a fragment that `aapt` refuses — or worse, one it accepts after quietly
 * eating an apostrophe — has been given work instead of a way to help. So the
 * escaping below is deliberately exhaustive about the five things Android
 * resources treat as syntax, and deliberately silent about the one thing they
 * do not: `%`. A format specifier reaches this code exactly as it sits in the
 * file, because `Resources.getString` hands back the template and never the
 * formatted line, and `aapt` passes `%` through untouched. Escaping it to `%%`
 * here would be the round trip losing the argument.
 */
internal object TranslationXml {

    /**
     * [value] as the body of a `<string>` element, such that reading the
     * element back gives [value] again.
     *
     * Two escaping systems overlap in a resource file and both have to be
     * served. XML wants `&`, `<` and `>` as entities; the resource compiler,
     * which reads what XML leaves behind, wants a backslash before a quote, an
     * apostrophe or a backslash, and turns a bare newline or tab into a single
     * space. One pass over the characters rather than a chain of `replace`
     * calls, because a chain has an order and the order is where the bugs are:
     * escaping `&` after `<` doubles the ampersand the `&lt;` just introduced.
     *
     * Spaces survive by quoting the whole value, which is the resource
     * compiler's own mechanism for it: unquoted, it strips the edges and
     * collapses every run in the middle to one. A translation that ends in a
     * space usually ends in one on purpose, because the next thing on the line
     * is a number.
     */
    fun escape(value: String): String {
        val normalised = value.replace("\r\n", "\n").replace('\r', '\n')
        val escaped = buildString(normalised.length) {
            for (character in normalised) {
                when (character) {
                    '\\' -> append("\\\\")
                    '\'' -> append("\\'")
                    '"' -> append("\\\"")
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
        }
        // A value opening with @ or ? is read as a reference to another
        // resource, so the first character is escaped even when nothing else
        // about the value is remarkable.
        val referenceSafe = when (normalised.firstOrNull()) {
            '@', '?' -> "\\" + escaped
            else -> escaped
        }
        val whitespaceAtRisk = normalised.startsWith(' ') ||
            normalised.endsWith(' ') ||
            normalised.contains("  ")
        return if (whitespaceAtRisk) "\"" + referenceSafe + "\"" else referenceSafe
    }

    /**
     * The full path of the file a correction belongs in — module and all.
     *
     * Two things decide it, and both of them used to be guessed. The locale
     * picks the qualifier: Russian is the app's default and therefore has no
     * suffix, and a Russian correction sent to `values-ru/` would land in a
     * folder this app does not have, be applied by nobody and noticed by no
     * one.
     *
     * The **module** is the half that only started to matter when correction
     * mode reached the design system. `Resources.getResourceName` cannot
     * answer it: by the time an id exists, every module's resources have been
     * merged under the app's own package, and `ds_state_break` and
     * `settings_title` are indistinguishable. Pasted into `:app` anyway, a
     * `ds_` correction does not fix the library string — it declares a second
     * one that shadows it, which looks right, leaves the original wrong, and
     * then asks `ResourceTranslationTest` for an English twin of a string that
     * should not exist.
     *
     * So it goes by prefix, which is not a convention this invents: each
     * module's strings already carry one, all 38 of the design system's, all
     * 18 of `:core:data`'s and all 77 of the widget's, and no other module
     * uses them. `TranslationXmlTest` reads the source tree and holds that,
     * because a prefix rule nothing checks is a prefix rule until the next
     * string.
     */
    fun valuesFolder(key: String, locale: String): String =
        "android/" + moduleOf(key) + "/src/main/res/" + valuesDirectory(locale)

    /**
     * Every file [key] could be declared in, most likely first.
     *
     * The prefix picks the module and stops there: within `:app` it cannot
     * pick the file, because the prefixes overlap — `settings_` is in both
     * `strings.xml` and `strings_admin.xml`, `bug_` in both `strings.xml` and
     * `strings_github.xml`. Guessing one of them is what made 419 of `:app`'s
     * 779 strings impossible to correct: the guess was always `strings.xml`,
     * the key was not in it, and the reader was told «Не отправлено».
     *
     * So the answer is a list and the repository asks the files themselves,
     * which is the only authority there is. Every other module ships one file,
     * so for them the list is one long. `TranslationXmlTest` reads the source
     * tree and fails if a file appears in a module that is not named here.
     */
    fun candidateFiles(key: String, locale: String): List<String> {
        val folder = valuesFolder(key, locale)
        val module = moduleOf(key)
        val names = if (module == AppModule) AppStringFiles else listOf(DefaultStringFile)
        return names.map { "$folder/$it" }
    }

    /** The Gradle module whose `res/` declares [key]; see [valuesFolder]. */
    fun moduleOf(key: String): String =
        ModulePrefixes.entries.firstOrNull { key.startsWith(it.key) }?.value ?: AppModule

    /** `values`, or `values-en`; the qualifier half of [valuesFolder]. */
    fun valuesDirectory(locale: String): String =
        if (locale.isEmpty() || locale == DefaultLocale) "values" else "values-$locale"

    /**
     * Every correction as `<string>` elements, grouped by the folder they
     * belong in and indented as the files themselves are, so that the result is
     * pasted rather than retyped.
     *
     * Sorted by folder and then by key: the same session must produce the same
     * text twice, or a reviewer comparing two exports reads a diff of the order
     * the reader happened to tap things in. The default folder of each module
     * comes before its translations, because `values` is a prefix of
     * `values-en` — which is an accident of the sort, and a good one.
     */
    fun fragment(edits: List<TranslationEdit>): String =
        edits.groupBy { valuesFolder(it.key, it.locale) }
            .toSortedMap()
            .map { (folder, folderEdits) ->
                buildString {
                    append(Indent).append("<!-- ").append(folder).append("/ -->\n")
                    folderEdits.sortedBy { it.key }.forEach { edit ->
                        append(Indent)
                            .append("<string name=\"").append(edit.key).append("\">")
                            .append(escape(edit.corrected))
                            .append("</string>\n")
                    }
                }
            }
            .joinToString(separator = "\n")

    /** Matches the four spaces every `strings.xml` in this app is written with. */
    private const val Indent = "    "

    /** The language of `values/`; see [valuesFolder]. */
    private const val DefaultLocale = "ru"

    /** Where a string lives when its name starts with none of the prefixes below. */
    private const val AppModule = "app"

    /** The one file every other module ships. */
    private const val DefaultStringFile = "strings.xml"

    /**
     * `:app`'s eight, in the order they are searched.
     *
     * `strings.xml` first because it holds the most and is the only one every
     * other module has; the rest in any order, since a key is in exactly one
     * of them. Held against the real tree by `TranslationXmlTest`, which walks
     * `values/` and fails on a file this list has never heard of — the list
     * itself being wrong is exactly the failure it is here to prevent.
     */
    private val AppStringFiles = listOf(
        "strings.xml",
        "strings_admin.xml",
        "strings_diary.xml",
        "strings_docs.xml",
        "strings_github.xml",
        "strings_telegram.xml",
        "strings_translate.xml",
        "strings_updates.xml",
    )

    /**
     * Prefix to module, longest-lived first — order matters only if one prefix
     * is ever made a prefix of another, which is why they are matched in the
     * order written rather than by any rule a reader has to work out.
     */
    private val ModulePrefixes = linkedMapOf(
        "ds_" to "core/designsystem",
        "alert_" to "core/data",
        "widget_" to "widget",
    )
}
