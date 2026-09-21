package com.lumenpearson.lessons.core.designsystem.theme

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled typeface carries the variation axes this app varies, and no others.
 *
 * `google_sans_flex.ttf` ships from upstream with six axes — `opsz`, `wdth`,
 * `wght`, `GRAD`, `ROND` and `slnt` — and a variable font pays for an axis in
 * its `gvar` table, which holds one set of outline deltas per axis per glyph.
 * At six axes that table was **3411 KB of a 3811 KB file**, while the outlines
 * themselves (`glyf`) were 31 KB. The app touches two of the six: it varies
 * `wght` across 400, 500 and 700, and sets `ROND` to 100. The other four never
 * move off their defaults on any screen.
 *
 * So the file in this repository is instanced: those four are frozen at their
 * defaults, which cost 92% of it — 3.81 MB to 0.29 MB, all 657 glyphs kept —
 * and about two megabytes of the APK, where the font had been 40% of
 * everything shipped.
 *
 * **`ROND` is kept as an axis rather than baked in at 100**, and the difference
 * is 0.02 MB: freezing it too gives 0.27 MB. It is worth the twenty kilobytes
 * because otherwise the app would go on asking for an axis that no longer
 * exists — harmless while the font happens to be instanced at exactly 100, and
 * silently a different shape the day somebody re-downloads the six-axis file.
 * An axis the code sets is an axis the font should declare, and that is what
 * the first test below says.
 *
 * **That saving is one careless copy away from coming back**, because the
 * obvious way to update the typeface is to download it again, and the upstream
 * file is the six-axis one. Equally, the obvious way to reach for a rounder or
 * a narrower cut is to write the axis into [Type.kt] and find it silently
 * ignored, because the font no longer declares it and Android does not complain
 * about an axis it cannot find. Both directions are held here.
 *
 * To re-instance after an update, with `fonttools` installed:
 *
 * ```
 * fonttools varLib.instancer google_sans_flex.ttf \
 *   opsz=18 wdth=100 GRAD=0 slnt=0 -o google_sans_flex.ttf
 * ```
 *
 * It leaves the `name` table untouched, which is what `FontLicenceTest` reads,
 * so the copyright and the OFL text still travel with the file. The OFL permits
 * the modification: this font declares no Reserved Font Name.
 */
class FontAxisTest {

    @Test
    fun `there is a font to guard in the first place`() {
        assertTrue(
            "No font resource found. If the bundled typeface was dropped, delete " +
                "this test with it — a guard over nothing passes for ever.",
            fonts.isNotEmpty(),
        )
    }

    @Test
    fun `the font declares every axis the app asks it to vary`() {
        val missing = fonts.flatMap { font ->
            (requestedAxes - axesOf(font)).map { "${font.name} does not declare '$it'" }
        }

        assertTrue(
            "An axis the font does not declare is not an error at runtime — Android " +
                "ignores it and draws the default, so the screen looks subtly wrong " +
                "and nothing is logged. Either stop asking for it, or re-instance the " +
                "font keeping it (see this class's note):\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun `the font carries no axis the app never varies`() {
        val spare = fonts.flatMap { font ->
            (axesOf(font) - requestedAxes).map { "${font.name} still carries '$it'" }
        }

        assertTrue(
            "Every axis costs one set of outline deltas per glyph in `gvar`, which is " +
                "where nine tenths of an uninstanced variable font goes. This one was " +
                "3.81 MB with six axes and is 0.29 MB with two. If the typeface was " +
                "re-downloaded, instance it again (see this class's note); if the axis " +
                "is wanted, use it in Type.kt and this test will accept it:\n" +
                spare.joinToString("\n"),
            spare.isEmpty(),
        )
    }

    /**
     * The axes the design system actually sets, read out of its own source.
     *
     * Both places, because they are set in two: `Type.kt` builds
     * `FontVariation.Settings` for Compose, and the font-family XML under
     * `res/font` carries `fontVariationSettings` for anything resolved
     * through the resource instead. A
     * check that read only one of them would pass while the other asked for an
     * axis that is gone.
     */
    private val requestedAxes: Set<String> by lazy {
        val fromKotlin = sourceFiles("src/main/kotlin", "kt").flatMap { file ->
            val text = file.readText()
            SETTING.findAll(text).map { it.groupValues[1] } +
                if (text.contains("FontVariation.weight(")) sequenceOf("wght") else emptySequence()
        }
        val fromXml = sourceFiles("src/main/res/font", "xml").flatMap { file ->
            QUOTED_AXIS.findAll(file.readText()).map { it.groupValues[1] }
        }
        (fromKotlin + fromXml).toSet()
    }

    private val fonts: List<File> by lazy {
        moduleDirectories
            .flatMap { File(it, "src/main/res/font").listFiles().orEmpty().toList() }
            .filter { it.extension.lowercase() in setOf("ttf", "otf") }
            .sortedBy { it.name }
    }

    private fun sourceFiles(under: String, extension: String): Sequence<File> =
        moduleDirectories.asSequence()
            .flatMap { File(it, under).walkTopDown() }
            .filter { it.isFile && it.extension.lowercase() == extension }

    /**
     * The axis tags an OpenType file declares, out of its `fvar` table.
     *
     * Hand-rolled for the reason `FontLicenceTest` gives about the `name`
     * table: a font library on the test classpath to read one table of one
     * file is the more expensive half. The format is fixed — twelve bytes of
     * offset table, sixteen per table record, and `fvar`'s own header says
     * where its axis records start and how big each one is.
     */
    private fun axesOf(font: File): Set<String> = RandomAccessFile(font, "r").use { file ->
        file.seek(4)
        val tables = file.readUnsignedShort()
        var fvar = -1L
        repeat(tables) { index ->
            file.seek(12L + index * 16L)
            val tag = ByteArray(4).also(file::readFully).decodeToString()
            file.skipBytes(4)
            val offset = file.readInt().toLong() and 0xFFFFFFFFL
            if (tag == "fvar") fvar = offset
        }
        if (fvar < 0) return emptySet()

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

        /** `fontVariationSettings="'ROND' 100"`, after XML unescaping or before it. */
        val QUOTED_AXIS = Regex("""(?:'|&apos;)([A-Za-z0-9]{4})(?:'|&apos;)\s""")
    }
}
