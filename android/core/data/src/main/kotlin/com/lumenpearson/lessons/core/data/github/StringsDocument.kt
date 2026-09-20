package com.lumenpearson.lessons.core.data.github

/**
 * Putting one corrected string back into a `strings.xml` that already exists.
 *
 * A correction is not a new file: it is one element inside a document somebody
 * else wrote, with its own comments, its own blank lines and its own order. So
 * the element is replaced where it stands and nothing else in the file is
 * touched — not re-serialised, not re-indented, not reordered. A round trip
 * through an XML writer would produce a diff of the whole file for a changed
 * comma, which is the kind of pull request a maintainer closes unread.
 *
 * The body that goes in arrives **already escaped**, from the same
 * `TranslationXml.escape` that builds the copy-and-paste fragment. That is
 * deliberate: two escapers disagree within a month, and the one in `:app` is
 * the one with the tests and the reasoning about `%`, quotes and a leading `@`.
 * This file's job is to find the element and swap its contents, not to decide
 * what those contents should look like.
 */
internal object StringsDocument {

    /**
     * [document] with the body of `<string name="[key]">` replaced by [body].
     *
     * `null` when the key is not in this file, which is a real outcome rather
     * than an error: the module a key belongs to is worked out from its prefix,
     * and a prefix rule that has drifted should refuse loudly here instead of
     * appending a second declaration that shadows the first.
     *
     * The element is found by scanning rather than by one regular expression
     * over the whole file. `name="x"` can be preceded by other attributes, the
     * value can contain `>` (escaped `&gt;` but also a bare `>` in a value the
     * compiler accepts), and a lazy `.*?` across a 400-line file matches the
     * wrong closing tag often enough to matter. Scanning also keeps the two
     * halves of the answer — where the body starts and where it ends — next to
     * the reasoning for each.
     */
    fun replace(document: String, key: String, body: String): String? {
        val open = openingTagOf(document, key) ?: return null
        val bodyStart = document.indexOf('>', startIndex = open) + 1
        if (bodyStart <= 0) return null
        val bodyEnd = document.indexOf("</string>", startIndex = bodyStart)
        if (bodyEnd < 0) return null
        return document.substring(0, bodyStart) + body + document.substring(bodyEnd)
    }

    /**
     * Where `<string …name="[key]"…>` opens, or `null`.
     *
     * Every `<string` is considered and the attribute is matched whole, because
     * `name="settings_title"` is a prefix of nothing but is itself prefixed by
     * `name="settings_titles"` read the other way round: a `contains` on
     * `name="$key` would match a longer key that starts with this one. The
     * quote after the key is what makes it exact.
     */
    private fun openingTagOf(document: String, key: String): Int? {
        val needle = "name=\"$key\""
        var index = document.indexOf("<string")
        while (index >= 0) {
            val close = document.indexOf('>', startIndex = index)
            if (close < 0) return null
            // A `<string-array` opens with the same seven characters, and its
            // items are not what a correction means.
            val tag = document.substring(index, close)
            if (!tag.startsWith("<string-array") && tag.contains(needle)) return index
            index = document.indexOf("<string", startIndex = close)
        }
        return null
    }
}
