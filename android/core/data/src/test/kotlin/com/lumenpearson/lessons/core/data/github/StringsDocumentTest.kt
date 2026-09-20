package com.lumenpearson.lessons.core.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The half of a translation pull request that can be checked without a network.
 *
 * Everything else in the flow is GitHub's — forking, branching, committing —
 * and none of it can go wrong quietly. This can: a patch that matches the wrong
 * element rewrites a string nobody asked about, and the pull request looks
 * plausible enough to merge.
 */
class StringsDocumentTest {

    private val document = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <!-- The settings page -->
            <string name="settings_title">Настройки</string>
            <string name="settings_titles_plural">Заголовки</string>
            <string name="ds_lesson_now">Сейчас</string>
            <string translatable="false" name="build_channel">stable</string>
            <string name="arrow">a &gt; b</string>
            <string-array name="settings_title">
                <item>not this one</item>
            </string-array>
        </resources>
    """.trimIndent()

    @Test
    fun `replaces the body of the named string and nothing else`() {
        val patched = StringsDocument.replace(document, "ds_lesson_now", "Идёт сейчас")

        assertEquals(
            document.replace(
                "<string name=\"ds_lesson_now\">Сейчас</string>",
                "<string name=\"ds_lesson_now\">Идёт сейчас</string>",
            ),
            patched,
        )
    }

    /**
     * The whole point of scanning rather than re-serialising: a reviewer should
     * see one line in the diff, not a reformatted file.
     */
    @Test
    fun `every other line survives byte for byte`() {
        val patched = StringsDocument.replace(document, "settings_title", "Параметры")!!

        val before = document.lines()
        val after = patched.lines()
        assertEquals(before.size, after.size)
        val differing = before.indices.filter { before[it] != after[it] }
        assertEquals(listOf(3), differing)
    }

    /**
     * `name="settings_title"` is contained in nothing here, but a `contains`
     * on `name="settings_title` without the closing quote would match
     * `settings_titles_plural` — and the correction would land on the wrong
     * string with no sign that it had.
     */
    @Test
    fun `a longer key that starts with this one is not matched`() {
        val patched = StringsDocument.replace(document, "settings_title", "Параметры")!!

        assertEquals("Заголовки", patched.substringAfter("settings_titles_plural\">").substringBefore("<"))
        assertEquals("Параметры", patched.substringAfter("<string name=\"settings_title\">").substringBefore("<"))
    }

    /** A `<string-array>` of the same name is a different resource. */
    @Test
    fun `a string-array of the same name is left alone`() {
        val patched = StringsDocument.replace(document, "settings_title", "Параметры")!!

        assertEquals("not this one", patched.substringAfter("<item>").substringBefore("</item>"))
    }

    /** `name` is not always the first attribute. */
    @Test
    fun `an attribute before name does not hide the element`() {
        val patched = StringsDocument.replace(document, "build_channel", "beta")

        assertEquals("beta", patched!!.substringAfter("name=\"build_channel\">").substringBefore("<"))
    }

    /**
     * A value may contain `>`, which is why the body's end is found by the
     * closing tag rather than by the next angle bracket.
     */
    @Test
    fun `a value containing an angle bracket does not cut the body short`() {
        val patched = StringsDocument.replace(document, "arrow", "b &lt; a")!!

        assertEquals("b &lt; a", patched.substringAfter("<string name=\"arrow\">").substringBefore("</string>"))
    }

    /**
     * Refusing is the right answer, not appending. The module a key belongs to
     * is worked out from its prefix; if that rule has drifted, a second
     * declaration would shadow the first, look correct and leave the original
     * string wrong.
     */
    @Test
    fun `a key this file does not declare is refused rather than added`() {
        assertNull(StringsDocument.replace(document, "widget_headline", "Заголовок"))
    }
}
