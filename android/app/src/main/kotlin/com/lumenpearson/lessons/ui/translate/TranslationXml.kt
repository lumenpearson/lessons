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
     * The resource folder a locale's strings live in.
     *
     * Russian is the app's default locale and therefore has no qualifier; every
     * other language is a suffixed folder. Getting this wrong would send a
     * Russian correction to `values-ru/`, a folder this app does not have,
     * where it would be applied by nobody and noticed by no one.
     */
    fun valuesFolder(locale: String): String =
        if (locale.isEmpty() || locale == DefaultLocale) "values" else "values-$locale"

    /**
     * Every correction as `<string>` elements, grouped by the folder they
     * belong in and indented as the files themselves are, so that the result is
     * pasted rather than retyped.
     *
     * Sorted by locale and then by key: the same session must produce the same
     * text twice, or a reviewer comparing two exports reads a diff of the order
     * the reader happened to tap things in.
     */
    fun fragment(edits: List<TranslationEdit>): String =
        edits.groupBy { it.locale }
            .toSortedMap()
            .map { (locale, localeEdits) ->
                buildString {
                    append(Indent).append("<!-- ").append(valuesFolder(locale)).append("/ -->\n")
                    localeEdits.sortedBy { it.key }.forEach { edit ->
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
}
