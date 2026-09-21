package com.lumenpearson.lessons.core.designsystem.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the font licences.
 *
 * The SIL Open Font License asks one thing of anyone who redistributes a font:
 * the copyright notice and the licence must accompany every copy. This project
 * satisfies that by packaging the text as an asset, so it rides inside the APK
 * beside the typeface — but nothing held the two together. Move the notice,
 * rename it, or swap the `.ttf` for a different face, and the build stays
 * green while the APK ships a font under a licence it no longer carries.
 *
 * Nobody would notice. That is the whole reason this test exists: a licence
 * file is never read by the code, never rendered on a screen and never covered
 * by anything else, so its absence looks exactly like its presence.
 *
 * The claim is not taken from a comment or a document, either of which can be
 * wrong — and both were, for months, calling this font MIT. It is taken from
 * the font: an OpenType file carries its copyright and its licence in its own
 * `name` table, and this test parses that table and checks the notice against
 * it. Replace the typeface with one under different terms and the test fails,
 * because the notice beside it no longer says what the new file says.
 *
 * Every module is read, not only this one, for the same reason
 * `ResourceTranslationTest` reads them all: a font added to `:widget` tomorrow
 * needs the guard on the day it arrives, not on the day somebody remembers it
 * exists.
 *
 * What it cannot check is that the asset is actually packaged — that is aapt's
 * business, and a JVM test never sees an APK. Verified by hand on the release
 * build: `assets/licenses/onest_OFL.txt`, beside the packaged font.
 */
class FontLicenceTest {

    /**
     * A licence is not a link.
     *
     * The OFL says "this license" must accompany the copy, meaning the text.
     * A notice that only names the licence and points at a URL fails that the
     * day the URL moves, so the file has to be big enough to be the licence
     * rather than a reference to it. OFL 1.1 is about 4 KB; half of that is a
     * floor no real licence text falls under and no stub reaches.
     */
    private val licenceTextFloor = 2000

    @Test
    fun `every bundled font has a notice carrying its own copyright`() {
        val orphans = fonts.filter { font ->
            val copyright = font.names[COPYRIGHT]
            copyright != null && notices.none { it.text.contains(copyright) }
        }
        assertTrue(
            "No notice under assets/licenses/ carries the copyright these fonts declare: " +
                orphans.joinToString { "${it.file.name} (${it.names[COPYRIGHT]})" } +
                ". The OFL requires the copyright and the licence to accompany every copy " +
                "of the font, and an APK that ships one without the other does not.",
            orphans.isEmpty(),
        )
    }

    /**
     * The notice has to repeat what the file itself says, not what somebody
     * believed when they copied it in.
     *
     * Compared on the first sentence of the font's own licence entry, because
     * the rest of it is a pointer to the FAQ and nothing is gained by pinning
     * a URL.
     */
    @Test
    fun `the notice states the licence the font declares`() {
        val mismatched = fonts.filter { font ->
            val declared = font.names[LICENCE]?.substringBefore(". ")?.trim()
            declared != null && notices.none { it.text.contains(declared) }
        }
        assertTrue(
            "These fonts declare a licence no notice repeats: " +
                mismatched.joinToString { "${it.file.name} — ${it.names[LICENCE]}" } +
                ". Either the typeface was replaced and the notice beside it was not, " +
                "or the notice was written from something other than the file.",
            mismatched.isEmpty(),
        )
    }

    @Test
    fun `a notice is the licence text and not a reference to it`() {
        val stubs = notices.filter { it.text.length < licenceTextFloor }
        assertTrue(
            "Too short to be a licence: " +
                stubs.joinToString { "${it.file.name} (${it.text.length} chars)" } +
                ". The licence has to travel with the font, and a link does not travel.",
            stubs.isEmpty(),
        )
    }

    /**
     * The other direction, and the one that catches a tidy-up: a notice left
     * behind after the font it belonged to was removed is not harmless, it is
     * a claim about something the APK no longer contains.
     */
    @Test
    fun `no notice is left behind without the font it belongs to`() {
        val copyrights = fonts.mapNotNull { it.names[COPYRIGHT] }
        val stranded = notices.filter { notice -> copyrights.none { notice.text.contains(it) } }
        assertTrue(
            "Under assets/licenses/ with no bundled font declaring their copyright: " +
                stranded.joinToString { it.file.name },
            stranded.isEmpty(),
        )
    }

    @Test
    fun `there is a font to guard in the first place`() {
        assertTrue(
            "No font resource found in any module. If the bundled typeface was " +
                "deliberately dropped, delete this test with it — a guard over nothing " +
                "passes for ever and says nothing.",
            fonts.isNotEmpty(),
        )
    }

    private class BundledFont(val file: File, val names: Map<Int, String>)

    private class Notice(val file: File, val text: String)

    private val fonts: List<BundledFont> by lazy {
        moduleDirectories
            .flatMap { File(it, "src/main/res/font").listFiles().orEmpty().toList() }
            .filter { it.extension.lowercase() in setOf("ttf", "otf") }
            .sortedBy { it.name }
            .map { BundledFont(it, nameTable(it)) }
    }

    private val notices: List<Notice> by lazy {
        moduleDirectories
            .flatMap { File(it, "src/main/assets/licenses").listFiles().orEmpty().toList() }
            .filter { it.isFile }
            .sortedBy { it.name }
            .map { Notice(it, it.readText()) }
    }

    /**
     * Every module, found from wherever the runner started.
     *
     * Lifted from `ResourceTranslationTest` rather than invented again: Gradle
     * runs unit tests with the module directory as the working directory and an
     * IDE sometimes runs them from the root, so the walk is upwards for the
     * directory holding `settings.gradle.kts`.
     */
    private val moduleDirectories: List<File> by lazy {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val root = directory
                return@lazy root.listFiles().orEmpty()
                    .flatMap { child -> listOf(child) + child.listFiles().orEmpty().toList() }
                    .filter { File(it, "src/main").isDirectory }
                    .sortedBy { it.absolutePath }
            }
            directory = directory.parentFile
        }
        error("Could not find the Gradle root from ${File("").absolutePath}")
    }

    /**
     * The `name` table of an OpenType file, by name id.
     *
     * Hand-rolled because the alternative is a font library on the test
     * classpath for one table of one file. The format is fixed and old: an
     * offset table of twelve bytes, then sixteen bytes per table record, and
     * the `name` table's own records are twelve bytes each pointing into a
     * string pool at the end.
     *
     * Windows records (platform 3) are UTF-16BE and Macintosh ones (platform 1)
     * are a single-byte encoding that agrees with Latin-1 over the ASCII a
     * licence notice is written in. The first record for an id wins, which is
     * enough: a font that disagrees with itself about its own copyright is not
     * a case worth reading two ways.
     */
    private fun nameTable(file: File): Map<Int, String> {
        val bytes = file.readBytes()
        fun u8(at: Int) = bytes[at].toInt() and 0xFF
        fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
        fun u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

        val tableCount = u16(4)
        var nameStart = -1
        for (index in 0 until tableCount) {
            val record = 12 + index * 16
            val tag = String(bytes, record, 4, Charsets.US_ASCII)
            if (tag == "name") {
                nameStart = u32(record + 8).toInt()
                break
            }
        }
        if (nameStart < 0) return emptyMap()

        val count = u16(nameStart + 2)
        val pool = nameStart + u16(nameStart + 4)
        return buildMap {
            for (index in 0 until count) {
                val record = nameStart + 6 + index * 12
                val platform = u16(record)
                val nameId = u16(record + 6)
                val length = u16(record + 8)
                val offset = u16(record + 10)
                if (containsKey(nameId)) continue
                val raw = bytes.copyOfRange(pool + offset, pool + offset + length)
                val charset = if (platform == 3) Charsets.UTF_16BE else Charsets.ISO_8859_1
                put(nameId, String(raw, charset).trim())
            }
        }
    }

    private companion object {
        /** `name` table ids, from the OpenType specification. */
        const val COPYRIGHT = 0
        const val LICENCE = 13
    }
}
