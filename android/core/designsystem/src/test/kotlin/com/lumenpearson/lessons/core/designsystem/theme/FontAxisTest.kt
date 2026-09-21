package com.lumenpearson.lessons.core.designsystem.theme

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typeface is compressed by the build, and this is what the build promised.
 *
 * `fonts/google_sans_flex.ttf` is the file as it was downloaded: six variation
 * axes — `opsz`, `wdth`, `wght`, `GRAD`, `ROND` and `slnt` — and 3.81 MB, of
 * which 3.41 MB is `gvar`, one set of outline deltas per axis per glyph,
 * against 31 KB of outlines. The app touches two of the six: it varies `wght`
 * across 400, 500 and 700, and sets `ROND` to 100. The other four never move
 * off their defaults on any screen.
 *
 * So the font in `res/` is not committed. `instance<Variant>Font` freezes the
 * axes `-Plessons.font.axes` does not name and writes the result into the
 * variant's generated resources, which is what ships.
 *
 * Three settings, and each is a different promise. The release APK is measured
 * on this commit, all three built from the same tree:
 *
 * | `lessons.font.axes` | font | APK | needs |
 * |---|---|---|---|
 * | `wght,ROND` (default) | 0.29 MB, both axes the app moves | 3.60 MB | Python with fonttools |
 * | `wght` | 0.27 MB, `ROND` frozen at the 100 the app asks for | 3.58 MB | the same |
 * | `all` | 3.81 MB, the file as it came | 5.71 MB | nothing |
 *
 * The third column is why the second row is not the default: fifteen kilobytes
 * of APK against an axis the app would go on asking for and never get.
 *
 * **An axis the app asks for and the font does not declare is not an error at
 * runtime.** Android draws the default and logs nothing, so the screen is
 * subtly wrong and no build fails. That is the direction this class guards
 * first: whatever the setting, every axis the app varies is either declared by
 * the font that ships or frozen at exactly the value the app asks for. The
 * second guard is the other way round — a font carrying an axis the build did
 * not ask to keep means the compression did not happen, which is what a
 * re-downloaded typeface used to cost silently.
 *
 * The values are read out of the sources, not restated here: the axes the app
 * asks for come from `Type.kt` and `res/font/google_sans_flex_round.xml`, and
 * the value a frozen axis is frozen at comes from `build.gradle.kts`. A test
 * that repeated any of the three would be a fourth place to keep in step.
 */
class FontAxisTest {

    @Test
    fun `there is a font to guard in the first place`() {
        assertTrue(
            "No font resource found. `instance<Variant>Font` writes it, and Gradle " +
                "hands this test the directory; running the test another way is the " +
                "usual reason it is missing. If the bundled typeface was dropped, " +
                "delete this test with it — a guard over nothing passes for ever.",
            shippedFonts.isNotEmpty(),
        )
    }

    @Test
    fun `the font declares every axis the app asks to vary, or was frozen at its value`() {
        val lost = shippedFonts.flatMap { font ->
            val declared = axesOf(font)
            requestedAxes.keys
                .filter { it !in declared && it !in frozenAxisValues }
                .map { "${font.name} neither declares '$it' nor freezes it" }
        }

        assertTrue(
            "An axis the font does not declare is not an error at runtime — Android " +
                "ignores it and draws the default, so the screen looks subtly wrong " +
                "and nothing is logged. Either stop asking for it in Type.kt, keep it " +
                "in `lessons.font.axes`, or freeze it at the app's value in " +
                "`fontAxisPins`:\n" + lost.joinToString("\n"),
            lost.isEmpty(),
        )
    }

    @Test
    fun `the font carries no axis the build did not keep`() {
        val expected = if (keptAxes == KEEP_EVERYTHING) axesOf(sourceFont) else keptAxes.split(",").toSet()
        val spare = shippedFonts.flatMap { font ->
            (axesOf(font) - expected).map { "${font.name} still carries '$it'" }
        }

        assertTrue(
            "Every axis costs one set of outline deltas per glyph in `gvar`, which is " +
                "where nine tenths of an uninstanced variable font goes: 3.81 MB with " +
                "six axes, 0.29 MB with two. Built with `lessons.font.axes=$keptAxes`, " +
                "so these should be gone and are not — which is what it looks like when " +
                "the instancing step silently did not run:\n" + spare.joinToString("\n"),
            spare.isEmpty(),
        )
    }

    /**
     * The setting is what actually happened to the file, not a label on it.
     *
     * `all` exists for a machine that cannot install fonttools, and its whole
     * promise is that the build still produces a correct app: the file that
     * ships is the file that was downloaded, to the byte. Every other setting
     * promises the opposite — that something was taken out — and a build where
     * the instancing quietly turned into a copy weighs four megabytes and
     * passes every other test in this class, because a six-axis font declares
     * the two axes the app asks for.
     */
    @Test
    fun `the setting says what the build did to the file`() {
        val shipped = shippedFonts.single()
        if (keptAxes == KEEP_EVERYTHING) {
            assertEquals(
                "`lessons.font.axes=all` copies the source rather than instancing it.",
                sourceFont.length(),
                shipped.length(),
            )
            assertTrue(sourceFont.readBytes().contentEquals(shipped.readBytes()))
            return
        }

        assertTrue(
            "Built with `lessons.font.axes=$keptAxes`, so ${shipped.name} should be " +
                "smaller than the ${sourceFont.length()} bytes it was instanced from, " +
                "and it is ${shipped.length()}.",
            shipped.length() < sourceFont.length(),
        )
    }

    /**
     * A frozen axis is frozen at the value the app sets, not at the font's.
     *
     * `ROND` is 0 in the file and 100 everywhere the app asks for it, so
     * freezing it at the font's default would un-round «GoogleSansFlexRounded»
     * on every screen that uses it — correctly, silently, and wrongly. The
     * build names the value; this checks that it is still the one the app
     * asks for.
     */
    @Test
    fun `a frozen axis is frozen at the value the app sets it to`() {
        val disagreements = frozenAxisValues.mapNotNull { (axis, frozenAt) ->
            val asked = requestedAxes[axis] ?: return@mapNotNull null
            "the app sets '$axis' to $asked and the build freezes it at $frozenAt"
                .takeIf { asked != frozenAt }
        }

        assertTrue(
            "`fontAxisPins` in core/designsystem/build.gradle.kts is the value an axis " +
                "keeps once it stops being an axis, and it has to be the one Type.kt and " +
                "google_sans_flex_round.xml ask for:\n" + disagreements.joinToString("\n"),
            disagreements.isEmpty(),
        )
    }

    /** Which axes the build was told to keep; Gradle passes the property through. */
    private val keptAxes: String = System.getProperty("lessons.font.axes") ?: DEFAULT_AXES

    /**
     * The axes the design system sets, and the constant each is set to.
     *
     * Both places, because they are set in two: `Type.kt` builds
     * `FontVariation.Settings` for Compose, and the font-family XML carries
     * `fontVariationSettings` for anything resolved through the resource
     * instead. A check that read only one of them would pass while the other
     * asked for an axis that is gone.
     *
     * The value is null for an axis the app varies rather than pins — `wght`
     * comes from `FontVariation.weight(...)`, which is a different number on
     * each of the five registered weights.
     */
    private val requestedAxes: Map<String, Float?> by lazy {
        val fromKotlin = sourceFiles("src/main/kotlin", "kt").flatMap { file ->
            val text = file.readText()
            SETTING.findAll(text).map { it.groupValues[1] to it.groupValues[2].toFloat() } +
                if (text.contains("FontVariation.weight(")) sequenceOf("wght" to null) else emptySequence()
        }
        val fromXml = sourceFiles("src/main/res/font", "xml").flatMap { file ->
            QUOTED_AXIS.findAll(file.readText())
                .map { it.groupValues[1] to it.groupValues[2].toFloat() }
        }
        (fromKotlin + fromXml).groupBy({ it.first }, { it.second })
            // An axis set to two different constants has no single value to
            // freeze at, and `null` is exactly how that is reported here.
            .mapValues { (_, values) -> values.distinct().singleOrNull() }
    }

    /** `fontAxisPins` out of the module's build file: axis to the value it keeps. */
    private val frozenAxisValues: Map<String, Float> by lazy {
        val declaration = moduleDirectories
            .map { File(it, "build.gradle.kts") }
            .filter { it.isFile }
            .firstNotNullOfOrNull { PINS.find(it.readText()) }
            ?: return@lazy emptyMap()
        PIN.findAll(declaration.groupValues[1])
            .associate { it.groupValues[1] to it.groupValues[2].toFloat() }
    }

    /** The file as it was downloaded, which the build reads and never rewrites. */
    private val sourceFont: File by lazy {
        moduleDirectories.map { File(it, "fonts/google_sans_flex.ttf") }.first { it.isFile }
    }

    /**
     * The fonts that end up in the APK.
     *
     * The instanced one is not in the source tree at all, so Gradle hands over
     * the directory it wrote — see `build.gradle.kts`. Every module's
     * `res/font` is read as well, because a font committed to `:widget`
     * tomorrow needs both guards on the day it arrives.
     */
    private val shippedFonts: List<File> by lazy {
        val generated = System.getProperty("lessons.font.directory")?.let { File(it, "font") }
        (moduleDirectories.map { File(it, "src/main/res/font") } + listOfNotNull(generated))
            .flatMap { it.listFiles().orEmpty().toList() }
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
        const val KEEP_EVERYTHING = "all"

        /** Repeated from `build.gradle.kts` only for a runner that bypasses Gradle. */
        const val DEFAULT_AXES = "wght,ROND"

        /** `FontVariation.Setting("ROND", 100f)` — the tag and the value it is set to. */
        val SETTING = Regex("""FontVariation\.Setting\(\s*"([A-Za-z0-9]{4})"\s*,\s*([0-9.]+)f?""")

        /** `fontVariationSettings="'ROND' 100"`, after XML unescaping or before it. */
        val QUOTED_AXIS = Regex("""(?:'|&apos;)([A-Za-z0-9]{4})(?:'|&apos;)\s+([0-9.]+)""")

        /** `val fontAxisPins: Map<String, String> = mapOf("ROND" to "100")`. */
        val PINS = Regex("""fontAxisPins[^=]*=\s*mapOf\(([^)]*)\)""")
        val PIN = Regex(""""([A-Za-z0-9]{4})"\s+to\s+"([0-9.]+)"""")
    }
}
