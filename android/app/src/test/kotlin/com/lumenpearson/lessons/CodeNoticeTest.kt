package com.lumenpearson.lessons

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the notices for code this app borrowed.
 *
 * `FontLicenceTest` in `:core:designsystem` does this for typefaces, and the
 * reasoning is the same one: a licence file is never read by the code, never
 * drawn on a screen and never covered by any other test, so its absence looks
 * exactly like its presence. The difference is only what it accompanies —
 * `assets/licenses/` is the fonts, `assets/notices/` is source that was adapted
 * from somebody else's project.
 *
 * There is one such notice today, for
 * [GMS Flags Reborn](https://github.com/polodarb/GMS-Flags-Reborn): the
 * first-run screens' morphing shape comes from there, under Apache 2.0, whose
 * section 4(a) asks whoever passes the work on to pass the licence with it. A
 * row on the «Лицензии» sheet names it; a row is not a copy of the licence, so
 * the text rides in the APK as well.
 *
 * What this cannot check is that the asset is actually packaged — that is
 * aapt's business and a JVM test never opens an APK — nor that the notice
 * matches what was in fact copied, which no machine can decide. It checks the
 * two things that do rot silently: that the file is still there, and that it is
 * the licence rather than a link to it.
 */
class CodeNoticeTest {

    /**
     * Long enough to be a licence.
     *
     * Apache 2.0 is about 11 KB. The floor is far below that on purpose: it is
     * there to catch a file replaced by a URL or emptied by a bad merge, not to
     * pin a particular licence's length.
     */
    private val licenceTextFloor = 2000

    private val noticeDirectory = File("src/main/assets/notices")

    private val notices: List<File> by lazy {
        noticeDirectory.takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.isFile }
            .orEmpty()
            .sortedBy { it.name }
    }

    @Test
    fun `the notice for borrowed code is still there`() {
        assertTrue(
            "No file under ${noticeDirectory.path}. Source adapted from another project is " +
                "credited on the licences sheet, and the licence itself has to travel with " +
                "the app — if the borrowed code was removed, remove this test with it rather " +
                "than leaving a guard over nothing.",
            notices.isNotEmpty(),
        )
    }

    @Test
    fun `a notice is the licence text and not a reference to it`() {
        val stubs = notices.filter { it.readText().length < licenceTextFloor }
        assertTrue(
            "Too short to be a licence: " +
                stubs.joinToString { "${it.name} (${it.readText().length} chars)" } +
                ". A link does not travel with the APK; the text does.",
            stubs.isEmpty(),
        )
    }

    @Test
    fun `each notice names the licence it carries and where the code came from`() {
        val vague = notices.filter { file ->
            val text = file.readText()
            !text.contains("Apache License") || !text.contains("github.com")
        }
        assertTrue(
            "These notices do not both name a licence and say where the code came from: " +
                vague.joinToString { it.name } +
                ". A licence text with no attribution does not say what it is attached to.",
            vague.isEmpty(),
        )
    }
}
