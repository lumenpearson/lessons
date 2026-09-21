package com.lumenpearson.lessons.core.designsystem.theme

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled typeface can draw this app, and carries nothing it does not need.
 *
 * Both halves were learned the same way, from one file that failed both.
 *
 * **Google Sans Flex has no Cyrillic.** Not a subset of it — none: its coverage
 * on Google Fonts is latin, latin-ext, vietnamese, math, symbols and five
 * scripts nobody here writes, and zero code points in U+0400–U+04FF. This app's
 * product language is Russian. So for as long as it was bundled, every Russian
 * word came from the device's fallback face while the digits and Latin beside
 * it came from the bundled one — two typefaces in one row, at different
 * x-heights — and the «системный шрифт» setting was close to a no-op for the
 * only language on the screen. Nothing failed, nothing was logged, and it had
 * been true since the design system was taken from an English app. That is what
 * the alphabet test below is for, and it is the one guard here that would have
 * caught it.
 *
 * **It also carried six variation axes where the app moves one.** An axis costs
 * one set of outline deltas per glyph in `gvar`, which was 3.41 MB of a 3.81 MB
 * file, so the build grew a task that froze the four nobody asked for. Onest
 * carries exactly `wght`, which the app varies, so there is nothing left to
 * freeze and the task is gone — but the two axis guards stay, because they are
 * free and because the way that saving comes back is somebody downloading a
 * multi-axis file and committing it without noticing.
 *
 * The axes the app asks for are read out of `Type.kt` rather than restated
 * here: a test that repeated them would be a second place to keep in step.
 */
class FontAxisTest {

    @Test
    fun `there is a font to guard in the first place`() {
        assertTrue(
            "No font resource found under any module's res/font. If the bundled " +
                "typeface was deliberately dropped, delete this test with it — a guard " +
                "over nothing passes for ever.",
            fonts.isNotEmpty(),
        )
    }

    /**
     * The guard that was missing while the app shipped a font it could not be
     * written in.
     *
     * Russian is not a nice-to-have here: `values/` is the source and
     * `values-en/` is the translation, so a bundled face without Cyrillic
     * cannot draw the default build of this app at all. The sample is the
     * alphabet plus «ё», which Russian keyboards produce and which font
     * subsets routinely drop on its own.
     */
    @Test
    fun `the font can draw the language the product is written in`() {
        val missing = fonts.flatMap { font ->
            val covered = codePointsOf(font)
            RUSSIAN.filterNot { it.code in covered }.map { "${font.name} cannot draw '$it'" }
        }

        assertTrue(
            "A bundled typeface that cannot draw Cyrillic is not a missing screen — " +
                "Android falls back per run of text, so the Russian comes out of the " +
                "device's own face and the digits beside it out of this one, in the same " +
                "row, and nothing is logged. Either bundle a face that covers Russian or " +
                "stop bundling one:\n" + missing.take(12).joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun `the font declares every axis the app asks it to vary`() {
        val lost = fonts.flatMap { font ->
            (requestedAxes - axesOf(font)).map { "${font.name} does not declare '$it'" }
        }

        assertTrue(
            "An axis the font does not declare is not an error at runtime — Android " +
                "ignores it and draws the default, so the screen looks subtly wrong " +
                "and nothing is logged. Either stop asking for it in Type.kt, or bundle " +
                "a face that has it:\n" + lost.joinToString("\n"),
            lost.isEmpty(),
        )
    }

    @Test
    fun `the font carries no axis the app never varies`() {
        val spare = fonts.flatMap { font ->
            (axesOf(font) - requestedAxes).map { "${font.name} still carries '$it'" }
        }

        assertTrue(
            "Every axis costs one set of outline deltas per glyph in `gvar`, which is " +
                "where nine tenths of an uninstanced variable font goes — the face this " +
                "module used to ship was 3.81 MB with six axes against 193 KB for the " +
                "one it ships now. If a multi-axis file was downloaded, either freeze " +
                "what the app does not move (`fonttools varLib.instancer`, and git has " +
                "the build task that used to do it) or use the axis in Type.kt and this " +
                "test will accept it:\n" + spare.joinToString("\n"),
            spare.isEmpty(),
        )
    }

    /**
     * The axes the design system actually sets, read out of its own source.
     *
     * `FontVariation.weight(...)` is `wght` spelled through Compose's helper;
     * `FontVariation.Setting("TAG", …)` is any other axis written out. Both
     * shapes are read, because the second is how a new axis would arrive.
     */
    private val requestedAxes: Set<String> by lazy {
        moduleDirectories.asSequence()
            .flatMap { File(it, "src/main/kotlin").walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                SETTING.findAll(text).map { it.groupValues[1] } +
                    if (text.contains("FontVariation.weight(")) sequenceOf("wght") else emptySequence()
            }
            .toSet()
    }

    /** Every font that ships, in every module — a face added to `:widget` needs both guards too. */
    private val fonts: List<File> by lazy {
        moduleDirectories
            .flatMap { File(it, "src/main/res/font").listFiles().orEmpty().toList() }
            .filter { it.extension.lowercase() in setOf("ttf", "otf") }
            .sortedBy { it.name }
    }

    /**
     * The axis tags an OpenType file declares, out of its `fvar` table.
     *
     * Hand-rolled for the reason `FontLicenceTest` gives about the `name`
     * table: a font library on the test classpath to read one table of one
     * file is the more expensive half. A file with no `fvar` is not variable
     * and declares no axes, which is a legitimate answer rather than a failure.
     */
    private fun axesOf(font: File): Set<String> = RandomAccessFile(font, "r").use { file ->
        val fvar = tableOffset(file, "fvar") ?: return emptySet()
        file.seek(fvar + 4)
        val axesArrayOffset = file.readUnsignedShort()
        file.skipBytes(2)
        val axisCount = file.readUnsignedShort()
        val axisSize = file.readUnsignedShort()
        (0 until axisCount).map { index ->
            file.seek(fvar + axesArrayOffset + index.toLong() * axisSize)
            ByteArray(4).also(file::readFully).decodeToString()
        }.toSet()
    }

    /**
     * Every code point the file maps, out of its `cmap`.
     *
     * Formats 4 and 12 only. They are what a modern font uses — 4 for the
     * Basic Multilingual Plane, 12 where it reaches past it — and Cyrillic is
     * inside the BMP, so a font that covers Russian in neither is not a
     * parsing gap this test should paper over.
     */
    private fun codePointsOf(font: File): Set<Int> = RandomAccessFile(font, "r").use { file ->
        val cmap = tableOffset(file, "cmap") ?: return emptySet()
        file.seek(cmap + 2)
        val subtables = file.readUnsignedShort()
        val offsets = (0 until subtables).map {
            file.skipBytes(4)
            cmap + (file.readInt().toLong() and 0xFFFFFFFFL)
        }

        buildSet {
            for (offset in offsets) {
                file.seek(offset)
                when (file.readUnsignedShort()) {
                    4 -> addAll(readFormat4(file, offset))
                    12 -> addAll(readFormat12(file, offset))
                }
            }
        }
    }

    private fun readFormat4(file: RandomAccessFile, offset: Long): Set<Int> {
        file.seek(offset + 6)
        val segments = file.readUnsignedShort() / 2
        file.seek(offset + 14)
        val ends = List(segments) { file.readUnsignedShort() }
        file.skipBytes(2)
        val starts = List(segments) { file.readUnsignedShort() }
        // `idDelta` and `idRangeOffset` decide which glyph a code point maps
        // to, and this test asks only whether it maps at all — a segment ends
        // at 0xFFFF as the format's terminator, which is not coverage.
        return buildSet {
            for (index in 0 until segments) {
                if (starts[index] > ends[index] || starts[index] == 0xFFFF) continue
                addAll(starts[index]..ends[index])
            }
        }
    }

    private fun readFormat12(file: RandomAccessFile, offset: Long): Set<Int> {
        file.seek(offset + 12)
        val groups = file.readInt()
        return buildSet {
            repeat(groups) {
                val start = file.readInt()
                val end = file.readInt()
                file.skipBytes(4)
                if (start <= end && end - start < MAX_GROUP) addAll(start..end)
            }
        }
    }

    /** Where a table lives in the file, out of the twelve-byte offset table. */
    private fun tableOffset(file: RandomAccessFile, tag: String): Long? {
        file.seek(4)
        val tables = file.readUnsignedShort()
        repeat(tables) { index ->
            file.seek(12L + index * 16L)
            val found = ByteArray(4).also(file::readFully).decodeToString()
            file.skipBytes(4)
            val offset = file.readInt().toLong() and 0xFFFFFFFFL
            if (found == tag) return offset
        }
        return null
    }

    /** Every module, found from wherever the runner started; see `FontLicenceTest`. */
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

    private companion object {
        /** `FontVariation.Setting("ROND", 100f)` — the tag is the first argument. */
        val SETTING = Regex("""FontVariation\.Setting\(\s*"([A-Za-z0-9]{4})"""")

        /** Both cases, plus «ё», which subsets drop on its own. */
        val RUSSIAN = ("АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ" +
            "абвгдеёжзийклмнопрстуфхцчшщъыьэюя").toList()

        /** A malformed group must not be walked; no real font maps a run this long. */
        const val MAX_GROUP = 0x110000
    }
}
