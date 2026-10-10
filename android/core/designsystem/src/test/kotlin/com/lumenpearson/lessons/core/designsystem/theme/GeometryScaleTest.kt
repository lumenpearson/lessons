package com.lumenpearson.lessons.core.designsystem.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the one corner scale agreed in
 * `docs/specs/2026-10-10-ui-geometry-design.md`: every radius this app draws is
 * one of four values — 4 (`Row`), 12 (`Cell`), 28 (`Group`/`Hero`/`extraLarge`)
 * and full — read from [LessonsShapeTokens] or [LessonsShapes] by name, never
 * guessed again as a literal or re-declared under a private name. That is the
 * audit's own finding: a 24 dp corner declared seven times under names like
 * `CardCorner` and `FieldCorner`, instead of the one token that already said
 * the same thing. The floating action buttons are the one exception, and they
 * take it through Material's own FAB defaults; `theme/Shape.kt` says why.
 *
 * Modelled on `StabilityPromiseTest` (`:core:model`): it reads the source out
 * of the tree rather than the classpath, because a radius literal compiles to
 * exactly the bytecode a token read by name does — only the words tell them
 * apart. It walks up for `settings.gradle.kts` the way `FontAxisTest` and
 * `ResourceTranslationTest` do, because Gradle runs a module's tests from that
 * module's own directory and an IDE sometimes runs them from the repository
 * root instead.
 *
 * `core/designsystem/src/main/kotlin/.../theme/Shape.kt` is the one file
 * allowed to declare the scale's own literals — everywhere else is read,
 * `android/app` included, because a literal picked in a screen is exactly how
 * the audit found nine distinct radii in the first place.
 *
 * Four shapes of offender, all read as plain text, comments stripped first so
 * that a sentence quoting one in prose is not mistaken for one:
 * - a `RoundedCornerShape(` call, read as the whole balanced expression from
 *   its opening paren to its matching close — across however many lines it
 *   spans and past however many nested calls sit inside it — carrying a
 *   numeric `.dp` literal or `percent =` anywhere inside that span, or an
 *   argument that is a bare number: `RoundedCornerShape(50)` is fifty percent
 *   and `RoundedCornerShape(24f)` is 24 pixels, and neither says `.dp`;
 * - a `.dp` literal under a name of its own: a `val` whose name ends in
 *   `Corner`, wherever it is used, and any `val` bound to a `.dp` literal that
 *   a `RoundedCornerShape(…)` in the same file reads — `val CardRadius = 24.dp`
 *   is a corner by its use, whatever it is called;
 * - a bare `CircleShape` token, the spec's own third promise ("What holds
 *   it": "a `CircleShape` given to a button or a chip"). [circleShapeAllowance]
 *   is the standing list of what a `CircleShape` is allowed to be instead, each
 *   with its reason;
 * - a read by name of a `Shapes` role that is off the scale — `medium` (16),
 *   `large` (20), and Expressive's `largeIncreased`, `extraLargeIncreased` and
 *   `extraExtraLarge` (24, 32, 48): `MaterialTheme.shapes.large`,
 *   `LessonsShapes.medium`. That is how three of the off-scale corners reached
 *   the screen before the pass, because a role read by name looks like a token.
 *   Its allowance is empty. The FABs that do draw `large` take it through
 *   `FloatingActionButtonDefaults`, which this does not see and is not meant
 *   to: that is Material's component reading its own shape.
 *
 * The first shape is read as a whole call because a read one physical line at
 * a time, bounded at the first `)`, misses a call wrapped across lines
 * (`DayAccent.kt`'s own `RunPosition.shape()` writes
 * `RoundedCornerShape(topStart = …, …)` that way, today with no literal in it)
 * and a literal sitting after an earlier nested call's own close paren on the
 * same line (`RoundedCornerShape(something(), 4.dp)`).
 *
 * The paren-depth tracker counts every literal `(`/`)` character, with no
 * awareness of string-literal boundaries, so a call that put a parenthesis
 * inside a string argument — `RoundedCornerShape(label = describe("("), 4.dp)` —
 * would mis-measure its own span. Harmless for what this guard actually reads:
 * a corner is always a `Dp` or a percentage, never a `String`, in both scanned
 * trees today.
 *
 * `@Preview` code is exempt, by the simplest approach that holds for this
 * codebase's previews: once a line carries `@Preview`, every line is skipped
 * until the function it precedes opens its first brace, and then every line is
 * skipped until that brace's own close — counting `{`/`}` per line rather than
 * parsing Kotlin, which is enough for a preview body that never puts a brace
 * character inside a string literal, true of every preview here today.
 *
 * The [allowance] held every 2026-10-10 audit finding as a pending count while
 * the pass moved each onto a token. The test that checks it against the source
 * fails the day a count in it no longer matches, so a fixed site has to be
 * taken out of the list by hand rather than the list quietly going stale while
 * the count it was given kept passing. It is **empty** now; the last entry was
 * `text/Corrections.kt`'s outline, which reads [LessonsShapeTokens.Row] (4 dp)
 * because the border's deciding dimension is the text line's own height, and
 * `Cell`'s 12 dp is already a capsule on a label line.
 */
class GeometryScaleTest {

    @Test
    fun `there is source to scan in the first place`() {
        assertTrue(
            "No Kotlin source found under app/src/main/kotlin or " +
                "core/designsystem/src/main/kotlin, from ${File("").absolutePath}. " +
                "That would make every assertion below pass in silence rather than " +
                "because the geometry is actually clean.",
            scannedFiles.isNotEmpty(),
        )
    }

    /**
     * The non-vacuousness check above only asks that the two trees' files,
     * combined, are not empty — which would still pass if one of the two
     * configured trees silently resolved to nothing (a typo'd path, a module
     * that moved) while the other carried the whole count. Each tree is
     * therefore checked on its own.
     */
    @Test
    fun `every configured source tree resolved to at least one file on its own`() {
        val empty = scannedTrees.filterValues { it.isEmpty() }.keys
        assertTrue(
            "The following configured source tree(s) resolved to zero .kt files, from " +
                "${File("").absolutePath}: $empty. A non-empty combined scan would still " +
                "pass while one tree vanished on its own, so each tree is asserted " +
                "individually rather than only in aggregate.",
            empty.isEmpty(),
        )
    }

    /**
     * The guard itself: nothing outside the allowance below may be a raw
     * corner literal. A new offender in a file already listed pushes that
     * file's count past what is allowed; a new offender in any other file has
     * nothing to be measured against, so it is flagged outright.
     */
    @Test
    fun `no corner radius outside the one file that declares the scale is a raw literal`() {
        val beyondAllowance = beyond(allowance, offenders())

        assertTrue(
            "A corner radius is a raw literal rather than a token read from " +
                "theme/Shape.kt. Point it at LessonsShapeTokens/LessonsShapes, or " +
                "— if this is new work landing — add it to the allowance in the " +
                "same commit that introduces it and remove it again once it reads " +
                "a token:\n" + beyondAllowance.joinToString("\n") { "${it.file}:${it.line}: ${it.text}" },
            beyondAllowance.isEmpty(),
        )
    }

    /**
     * The half of the allowance's promise a count alone cannot keep by
     * itself: a file whose offenders were actually fixed still passes the
     * test above with room to spare, which is exactly how a stale allowance
     * entry survives a cleanup unnoticed. This fails until the entry is
     * corrected — removed if the count is now zero, lowered otherwise.
     */
    @Test
    fun `the allowance names exactly today's offenders, not more and not fewer`() {
        val stale = drift(allowance, offenders())

        assertTrue(
            "The pending allowance has drifted from the source. A fixed site must " +
                "be taken out of the list — or its count lowered — rather than left " +
                "standing at the old number, which is what keeps the list from " +
                "rotting into a blanket exemption:\n" + stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    /**
     * The guard's third promise: a `CircleShape` is either in
     * [circleShapeAllowance] — a dot, a disc, a badge, a mask preview, a row's
     * round tile, each with its own reason — or it is a raw literal standing
     * in for `LessonsShapeTokens.Pill`/`Tile`, which is what the geometry pass
     * removed eleven of.
     */
    @Test
    fun `no CircleShape outside the allowance is given to a button or a chip`() {
        val beyondAllowance = beyond(circleShapeAllowance, circleShapeOffenders())

        assertTrue(
            "A CircleShape outside circleShapeAllowance is standing in for a " +
                "Pill or a Tile. Either point it at LessonsShapeTokens.Pill/Tile, " +
                "or — if this really is a circle (a dot, a disc, a badge, a mask " +
                "preview) — add it to circleShapeAllowance with its own " +
                "reason:\n" + beyondAllowance.joinToString("\n") { "${it.file}:${it.line}: ${it.text}" },
            beyondAllowance.isEmpty(),
        )
    }

    /** The CircleShape allowance's own drift check — see the corner allowance's. */
    @Test
    fun `the CircleShape allowance names exactly today's real circles, not more and not fewer`() {
        val stale = drift(circleShapeAllowance, circleShapeOffenders())

        assertTrue(
            "The CircleShape allowance has drifted from the source:\n" + stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    /**
     * The fourth promise: no source reads a shape role off the scale by name —
     * Material's `medium` (16 dp) and `large` (20 dp), or Expressive's larger
     * three. Its allowance is empty and meant to stay so: the one corner that
     * really is `large` is the FAB exception `theme/Shape.kt` describes, and
     * it reads the FAB's own default.
     */
    @Test
    fun `no source reads a shape role off the scale by name`() {
        val beyondAllowance = beyond(roleReadAllowance, roleReadOffenders())

        assertTrue(
            "A shape role off the four-value scale is read by name. medium is 16 dp, " +
                "large is 20, and largeIncreased, extraLargeIncreased and extraExtraLarge " +
                "are 24, 32 and 48; none is one of the app's corners. Point it at " +
                "LessonsShapeTokens instead:\n" +
                beyondAllowance.joinToString("\n") { "${it.file}:${it.line}: ${it.text}" },
            beyondAllowance.isEmpty(),
        )
    }

    // --- Fixture tests for the CircleShape scanner ---

    @Test
    fun `a bare CircleShape literal is caught`() {
        val found = circleShapeOffenders(listOf(1 to "shape = CircleShape,"))
        assertTrue("A bare CircleShape should be caught. Found: $found", found.isNotEmpty())
    }

    @Test
    fun `a fully-qualified CircleShape reference is caught`() {
        // CalendarGrids.kt's own load dot writes it this way, with no import.
        val found = circleShapeOffenders(
            listOf(1 to ".clip(androidx.compose.foundation.shape.CircleShape)"),
        )
        assertTrue("A fully-qualified reference should still be caught. Found: $found", found.isNotEmpty())
    }

    /**
     * The first real run of this scanner over both trees counted one extra
     * offender in every file that imports `CircleShape` at all — the import
     * line itself, which names the type with no call after it the way
     * `RoundedCornerShape(` always has one, so nothing about the regex told
     * the import apart from a use. Excluded by name instead.
     */
    @Test
    fun `an import line is not itself an offender`() {
        val found = circleShapeOffenders(
            listOf(1 to "import androidx.compose.foundation.shape.CircleShape"),
        )
        assertTrue("An import line must not be flagged. Found: $found", found.isEmpty())
    }

    @Test
    fun `the CircleShape scanner does not strip comments itself`() {
        // circleShapeOffenders is a pure function over already comment-stripped
        // lines — it does not itself filter `//` — so a sentence of prose
        // quoting "CircleShape" is caught here too; what actually keeps a
        // comment like this one out of offenders() is nonPreviewLines calling
        // stripComments first, the same split the corner scanner relies on.
        val found = circleShapeOffenders(listOf(1 to "// a sentence that quotes CircleShape"))
        assertTrue(
            "This function does not strip comments itself, so this line is " +
                "flagged — confirming offenders() needs nonPreviewLines to run " +
                "first, not this scanner alone. Found: $found",
            found.isNotEmpty(),
        )
    }

    // --- Fixture tests for the whole-call RoundedCornerShape(...) scanner ---
    // These feed constructed (line, text) pairs straight to the scanning
    // function rather than staging a real offending file in the tree.

    @Test
    fun `a literal spread across a multi-line RoundedCornerShape call is caught`() {
        val lines = listOf(
            1 to "val shape = RoundedCornerShape(",
            2 to "    topStart = 12.dp,",
            3 to "    bottomStart = 0.dp,",
            4 to "    topEnd = 0.dp,",
            5 to "    bottomEnd = 0.dp,",
            6 to ")",
        )
        val found = roundedCornerShapeOffenders(lines)
        assertTrue(
            "A literal on a continuation line of a RoundedCornerShape(...) call that opens on a " +
                "line of its own must be caught, not only a literal on the call's own line. Found: $found",
            found.isNotEmpty(),
        )
        assertTrue(
            "The offender should be reported at the call's own opening line (1), not a continuation " +
                "line. Found: $found",
            found.single().first == 1,
        )
    }

    @Test
    fun `a literal after an earlier nested call on the same line is caught`() {
        val lines = listOf(1 to "val shape = RoundedCornerShape(something(), 4.dp)")
        val found = roundedCornerShapeOffenders(lines)
        assertTrue(
            "Bounding the scan at the first ')' misses a literal that follows an earlier nested " +
                "call's own closing paren on the same line. Found: $found",
            found.isNotEmpty(),
        )
    }

    @Test
    fun `a multi-line call built only from other shapes' corners is not flagged`() {
        val lines = listOf(
            1 to "val shape = RoundedCornerShape(",
            2 to "    topStart = round.topStart,",
            3 to "    bottomStart = round.bottomStart,",
            4 to "    topEnd = flat.topEnd,",
            5 to "    bottomEnd = flat.bottomEnd,",
            6 to ")",
        )
        assertTrue(
            "A multi-line call with no literal .dp or percent = anywhere inside it must not be " +
                "flagged, or DayAccent.kt's own RunPosition.shape() — which already writes this " +
                "shape — would fail the real guard. Found: ${roundedCornerShapeOffenders(lines)}",
            roundedCornerShapeOffenders(lines).isEmpty(),
        )
    }

    @Test
    fun `a plain single-line literal is still caught`() {
        val found = roundedCornerShapeOffenders(listOf(1 to "val shape = RoundedCornerShape(24.dp)"))
        assertTrue("A plain single-line literal regressed. Found: $found", found.isNotEmpty())
    }

    /** `RoundedCornerShape(50)` is the `percent: Int` overload, positionally. */
    @Test
    fun `a positional integer percent is caught`() {
        val found = roundedCornerShapeOffenders(listOf(1 to "val shape = RoundedCornerShape(50)"))
        assertTrue("A positional percent says neither .dp nor percent =. Found: $found", found.isNotEmpty())
    }

    @Test
    fun `a named per-corner percent is caught`() {
        val found = roundedCornerShapeOffenders(
            listOf(1 to "val shape = RoundedCornerShape(topStartPercent = 50, topEndPercent = 50)"),
        )
        assertTrue("A per-corner percent is still a literal. Found: $found", found.isNotEmpty())
    }

    @Test
    fun `a corner read from a token is not mistaken for a bare number`() {
        val found = roundedCornerShapeOffenders(
            listOf(1 to "val shape = RoundedCornerShape(LessonsShapeTokens.Cell.topStart)"),
        )
        assertTrue("A token read must not be flagged. Found: $found", found.isEmpty())
    }

    // --- Fixture tests for a .dp literal under a name of its own ---

    @Test
    fun `a dp literal named Radius and used as a corner is caught`() {
        val lines = listOf(
            1 to "private val CardRadius = 24.dp",
            2 to "",
            3 to "val shape = RoundedCornerShape(CardRadius)",
        )
        val found = namedCornerLiteralOffenders(lines)
        assertTrue("A Radius literal read as a corner must be caught. Found: $found", found.isNotEmpty())
        assertTrue(
            "It should be reported where the literal is declared (1). Found: $found",
            found.single().first == 1,
        )
    }

    @Test
    fun `a dp literal under any name is caught once a corner reads it`() {
        val lines = listOf(
            1 to "private val Soft: Dp = 20.dp",
            2 to "val shape = RoundedCornerShape(",
            3 to "    topStart = Soft,",
            4 to "    topEnd = Soft,",
            5 to "    bottomStart = 0.dp,",
            6 to "    bottomEnd = 0.dp,",
            7 to ")",
        )
        assertTrue(
            "A corner is a corner by its use, whatever its name. Found: ${namedCornerLiteralOffenders(lines)}",
            namedCornerLiteralOffenders(lines).isNotEmpty(),
        )
    }

    @Test
    fun `a dp literal named Radius that no corner reads is not flagged`() {
        val lines = listOf(
            1 to "private val BlurRadius = 12.dp",
            2 to "val blurred = Modifier.blur(BlurRadius)",
        )
        assertTrue(
            "A blur radius is not a corner. Found: ${namedCornerLiteralOffenders(lines)}",
            namedCornerLiteralOffenders(lines).isEmpty(),
        )
    }

    // --- Fixture tests for the shape-role scanner ---

    @Test
    fun `a read of the large role by name is caught`() {
        val found = roleReadOffenders(listOf(1 to "    shape = MaterialTheme.shapes.large,"))
        assertTrue("MaterialTheme.shapes.large is a 20 dp corner by another name. Found: $found", found.isNotEmpty())
    }

    @Test
    fun `a read of the medium role through the theme object is caught`() {
        val found = roleReadOffenders(listOf(1 to "    .clip(LessonsShapes.medium)"))
        assertTrue("LessonsShapes.medium is the same role, read directly. Found: $found", found.isNotEmpty())
    }

    @Test
    fun `a role read wrapped across lines is caught`() {
        val found = roleReadOffenders(
            listOf(
                1 to "    shape = MaterialTheme.shapes",
                2 to "        .medium,",
            ),
        )
        assertTrue("A chained read split over two lines is still a read. Found: $found", found.isNotEmpty())
    }

    @Test
    fun `a read of an Expressive role past large is caught`() {
        val found = roleReadOffenders(listOf(1 to "    shape = MaterialTheme.shapes.largeIncreased,"))
        assertTrue("largeIncreased is 24 dp, the radius the pass removed. Found: $found", found.isNotEmpty())
    }

    @Test
    fun `the roles on the scale and the FAB's own default are not flagged`() {
        val found = roleReadOffenders(
            listOf(
                1 to "    shape = MaterialTheme.shapes.extraLarge,",
                2 to "    shape = MaterialTheme.shapes.extraSmall,",
                3 to "    shape = FloatingActionButtonDefaults.shape,",
                4 to "    shape = LessonsShapeTokens.Group,",
            ),
        )
        assertTrue("Only medium and large are off the scale. Found: $found", found.isEmpty())
    }

    /** One offending line, by the module-relative path a human would read in a review. */
    private data class Offender(val file: String, val line: Int, val text: String)

    /** What is past each file's allowed count, and so a failure. */
    private fun beyond(allowed: Map<String, Int>, found: List<Offender>): List<Offender> =
        found.groupBy { it.file }.entries.flatMap { (file, inFile) ->
            val count = allowed[file] ?: 0
            if (inFile.size > count) inFile.drop(count) else emptyList()
        }

    /** Every allowance entry whose count is not what the source has today. */
    private fun drift(allowed: Map<String, Int>, found: List<Offender>): List<String> {
        val counted = found.groupingBy { it.file }.eachCount()
        return allowed.entries
            .filter { (file, count) -> (counted[file] ?: 0) != count }
            .map { (file, count) -> "$file: allowance says $count, the source now has ${counted[file] ?: 0}" }
    }

    private fun offenders(): List<Offender> = scannedFiles.flatMap { file ->
        val relative = file.relativeTo(root).invariantSeparatorsPath
        val kept = nonPreviewLines(file)

        val cornerVals = kept.mapNotNull { (number, line) ->
            if (cornerValLiteral.containsMatchIn(line)) {
                Offender(relative, number, "a private *Corner constant — ${line.trim()}")
            } else {
                null
            }
        }
        // A `…Corner` val that a call also reads is one offence, not two.
        val cornerValLines = cornerVals.map { it.line }.toSet()
        val namedLiterals = namedCornerLiteralOffenders(kept)
            .filterNot { (number, _) -> number in cornerValLines }
            .map { (number, text) -> Offender(relative, number, "a .dp literal read as a corner — $text") }
        val roundedCornerShapes = roundedCornerShapeOffenders(kept).map { (number, text) ->
            Offender(relative, number, "a raw RoundedCornerShape(...) — $text")
        }
        (cornerVals + namedLiterals + roundedCornerShapes).sortedBy { it.line }
    }

    /** Every `CircleShape` token across both scanned trees, one per [Offender]. */
    private fun circleShapeOffenders(): List<Offender> = scannedFiles.flatMap { file ->
        val relative = file.relativeTo(root).invariantSeparatorsPath
        circleShapeOffenders(nonPreviewLines(file)).map { (number, text) ->
            Offender(relative, number, "a CircleShape — $text")
        }
    }

    /** Every read of an off-scale shape role by name across both scanned trees. */
    private fun roleReadOffenders(): List<Offender> = scannedFiles.flatMap { file ->
        val relative = file.relativeTo(root).invariantSeparatorsPath
        roleReadOffenders(nonPreviewLines(file)).map { (number, text) ->
            Offender(relative, number, "a shape role off the scale, read by name — $text")
        }
    }

    /**
     * Every line in [lines] carrying a bare `CircleShape` token, qualified or
     * not — `CalendarGrids.kt`'s own load dot writes the fully-qualified
     * `androidx.compose.foundation.shape.CircleShape` with no import, and the
     * word boundary before `CircleShape` still matches past the dot — except
     * an `import` line itself. `RoundedCornerShape(` cannot match one of
     * those, since an import names a type with no call after it, but
     * `CircleShape` is a bare value with nothing to tell a use from its own
     * import by shape alone; the first real run of this scanner counted
     * exactly one import line per file that has one, which this excludes by
     * name rather than by a second, parallel allowance for "files that
     * import CircleShape at all".
     */
    private fun circleShapeOffenders(lines: List<Pair<Int, String>>): List<Pair<Int, String>> =
        lines.mapNotNull { (number, text) ->
            if (!text.trimStart().startsWith("import ") && circleShapeLiteral.containsMatchIn(text)) {
                number to text.trim()
            } else {
                null
            }
        }

    /**
     * Every read of an off-scale shape role in [lines], matched over the
     * joined text so that a chain broken across two lines is still one read,
     * and reported at the line the match starts on.
     */
    private fun roleReadOffenders(lines: List<Pair<Int, String>>): List<Pair<Int, String>> {
        val (joined, lineOf) = join(lines)
        return roleRead.findAll(joined)
            .map { match -> lineOf[match.range.first] to match.value.replace(Regex("""\s+"""), "") }
            .toList()
    }

    /**
     * Every `RoundedCornerShape(` call in [lines] that carries a literal: a
     * `<number>.dp` or `percent =` anywhere inside it, nested or not, or an
     * argument that is a bare number. Returns the call's own opening line and
     * a whitespace-collapsed rendering of the call.
     *
     * A pure function of [lines] rather than a file, so a fixture list of
     * `(line, text)` pairs can probe it directly — see the tests above —
     * without staging a real offending file in the tree.
     */
    private fun roundedCornerShapeOffenders(lines: List<Pair<Int, String>>): List<Pair<Int, String>> =
        cornerCalls(lines)
            .filter { call ->
                literalInsideCall.containsMatchIn(call.args) ||
                    topLevelArguments(call.args).any { bareNumber.matches(it.substringAfter('=').trim()) }
            }
            .map { call -> call.line to call.text }

    /**
     * Every `val` in [lines] bound straight to a `.dp` literal and read by a
     * `RoundedCornerShape(…)` call in the same [lines], reported where it is
     * declared. A literal moved one line up under a name is the same literal,
     * and the name is no evidence either way: `CardRadius` is a corner, and so
     * is anything a corner call reads.
     */
    private fun namedCornerLiteralOffenders(lines: List<Pair<Int, String>>): List<Pair<Int, String>> {
        val declared = lines.mapNotNull { (number, text) ->
            dpLiteralVal.find(text)?.let { match -> Triple(match.groupValues[1], number, text.trim()) }
        }
        if (declared.isEmpty()) return emptyList()
        val calls = cornerCalls(lines)
        return declared
            .filter { (name, _, _) ->
                val read = Regex("""\b${Regex.escape(name)}\b""")
                calls.any { read.containsMatchIn(it.args) }
            }
            .map { (_, number, text) -> number to text }
    }

    /** One `RoundedCornerShape(…)` call: where it opens, what is inside it, and the whole of it. */
    private data class CornerCall(val line: Int, val args: String, val text: String)

    /**
     * Every `RoundedCornerShape(` call in [lines], read as the whole balanced
     * parenthesised expression it opens — joining continuation lines and
     * tracking paren depth past any nested call — rather than one physical
     * line bounded at the first `)`.
     */
    private fun cornerCalls(lines: List<Pair<Int, String>>): List<CornerCall> {
        if (lines.isEmpty()) return emptyList()
        val (joined, lineOf) = join(lines)

        val found = mutableListOf<CornerCall>()
        for (match in callOpen.findAll(joined)) {
            val openParen = match.range.last
            var depth = 1
            var index = openParen + 1
            while (index < joined.length && depth > 0) {
                when (joined[index]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                index++
            }
            // A call with no matching close before the end of the scanned
            // text is not balanced — malformed input, not a real call — and
            // is skipped rather than treated as spanning the rest of the file.
            if (depth > 0) continue

            val closeParen = index - 1
            found += CornerCall(
                line = lineOf[match.range.first],
                args = joined.substring(openParen + 1, closeParen),
                text = joined.substring(match.range.first, closeParen + 1)
                    .replace(Regex("""\s+"""), " ")
                    .trim(),
            )
        }
        return found
    }

    /**
     * [lines] as one string, so that an expression spanning several of them
     * can be read as one, plus a parallel array mapping every character back
     * to the *original* line number it came from — which a plain count of
     * newlines in the joined text could not do, because `nonPreviewLines`
     * already removed whole preview bodies, leaving gaps in the numbering.
     */
    private fun join(lines: List<Pair<Int, String>>): Pair<String, List<Int>> {
        val source = StringBuilder()
        val lineOf = ArrayList<Int>()
        for ((number, text) in lines) {
            repeat(text.length) { lineOf.add(number) }
            source.append(text)
            lineOf.add(number)
            source.append('\n')
        }
        return source.toString() to lineOf
    }

    /** [args] split at its own top-level commas, past any nested call's. */
    private fun topLevelArguments(args: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        args.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    parts += args.substring(start, index)
                    start = index + 1
                }
            }
        }
        parts += args.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * [file]'s lines, numbered from 1, comments stripped, with every line of
     * an `@Preview` function's body removed — see the class doc for the exact
     * approach, which is brace-counting rather than parsing.
     */
    private fun nonPreviewLines(file: File): List<Pair<Int, String>> {
        val lines = stripComments(file.readLines()).mapIndexed { index, line -> (index + 1) to line }
        val kept = mutableListOf<Pair<Int, String>>()
        var mode = Mode.SCANNING
        var depth = 0
        for ((number, line) in lines) {
            when (mode) {
                Mode.SCANNING -> {
                    kept += number to line
                    if (previewAnnotation.containsMatchIn(line)) mode = Mode.SKIPPING_HEADER
                }
                Mode.SKIPPING_HEADER -> {
                    val opens = line.count { it == '{' }
                    val closes = line.count { it == '}' }
                    if (opens > 0) {
                        depth = opens - closes
                        mode = if (depth > 0) Mode.SKIPPING_BODY else Mode.SCANNING
                    }
                    // Otherwise still an annotation line or a signature with no
                    // brace yet — stay here and keep looking.
                }
                Mode.SKIPPING_BODY -> {
                    depth += line.count { it == '{' } - line.count { it == '}' }
                    if (depth <= 0) mode = Mode.SCANNING
                }
            }
        }
        return kept
    }

    private fun stripComments(raw: List<String>): List<String> {
        var inBlock = false
        return raw.map { line ->
            val kept = StringBuilder()
            var index = 0
            while (index < line.length) {
                when {
                    inBlock && line.startsWith("*/", index) -> { inBlock = false; index += 2 }
                    inBlock -> index++
                    line.startsWith("/*", index) -> { inBlock = true; index += 2 }
                    line.startsWith("//", index) -> index = line.length
                    else -> kept.append(line[index++])
                }
            }
            kept.toString()
        }
    }

    private enum class Mode { SCANNING, SKIPPING_HEADER, SKIPPING_BODY }

    /** `android/`, found by walking up for the file that marks the Gradle root. */
    private val root: File by lazy {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return@lazy directory
            directory = directory.parentFile
        }
        error("Could not find the Gradle root from ${File("").absolutePath}")
    }

    /** The two trees the geometry pass covers, each named exactly as configured below. */
    private val treeNames = listOf("app/src/main/kotlin", "core/designsystem/src/main/kotlin")

    /**
     * Every `.kt` file under each of [treeNames], minus `theme/Shape.kt` — the
     * one file the scale is declared in rather than read from — kept per tree
     * so a tree that resolved to nothing can be told apart from one that is
     * merely small.
     */
    private val scannedTrees: Map<String, List<File>> by lazy {
        treeNames.associateWith { tree ->
            File(root, tree)
                .walkTopDown()
                .filter { candidate -> candidate.isFile && candidate.extension == "kt" }
                .filterNot { it.invariantSeparatorsPath.endsWith(shapeKtPath) }
                .toList()
        }
    }

    /**
     * Every `.kt` file the geometry pass covers, across both [treeNames] —
     * minus `theme/Shape.kt` — as a flat list for the offender scan itself,
     * which does not care which tree a file came from.
     */
    private val scannedFiles: List<File> by lazy { scannedTrees.values.flatten() }

    /** The one file the corner scale is declared in rather than read from. */
    private val shapeKtPath =
        "core/designsystem/src/main/kotlin/com/lumenpearson/lessons/core/designsystem/theme/Shape.kt"

    private companion object {
        val previewAnnotation = Regex("""@Preview\b""")

        /** The opening of a call this guard reads in full — see [cornerCalls]. */
        val callOpen = Regex("""RoundedCornerShape\(""")

        /** A numeric `.dp` literal or a bare `percent =`, anywhere inside a call's own span. */
        val literalInsideCall = Regex("""\d+(\.\d+)?\.dp|percent\s*=""")

        /**
         * An argument that is nothing but a number — `50`, `24f`, `12.5f` —
         * once any `name =` in front of it is dropped: a positional percent,
         * a per-corner `topStartPercent = 50`, or a size in pixels.
         */
        val bareNumber = Regex("""\d+(\.\d+)?[fF]?""")

        /** A `val` ending in `Corner`, of any visibility, bound to a `.dp` literal. */
        val cornerValLiteral = Regex("""\bval\s+\w*Corner\b[^=]*=\s*\d+(\.\d+)?\.dp""")

        /** A `val` of any name bound straight to a `.dp` literal; the name is group 1. */
        val dpLiteralVal = Regex("""\bval\s+(\w+)\s*(?::\s*Dp\s*)?=\s*\d+(\.\d+)?\.dp\b""")

        /**
         * A bare `CircleShape` token — see [circleShapeOffenders]. Not anchored
         * to `shape =` or `.clip(` on purpose: every real use found so far was
         * one of those two forms, but a plain word match is simpler than
         * guessing every syntax a future one might take, and
         * [circleShapeAllowance] is where the real circles are told apart from
         * everything else by hand regardless.
         */
        val circleShapeLiteral = Regex("""\bCircleShape\b""")

        /**
         * A `Shapes` role off the scale, read by name: `MaterialTheme
         * .shapes.large`, `shapes.medium` off a theme held in a local, or
         * [LessonsShapes] itself. `\s*` either side of the dot, so a chain
         * broken across lines still matches over the joined text. `small`,
         * `extraSmall` and `extraLarge` are on the scale here — 12, 4 and 28.
         */
        val roleRead = Regex(
            """\b(?:shapes|LessonsShapes)\s*\.\s*""" +
                """(?:medium|large|largeIncreased|extraLargeIncreased|extraExtraLarge)\b""",
        )

        /**
         * Empty: every 2026-10-10 audit finding reads a token now — see this
         * class's own doc for `text/Corrections.kt`, the last to move.
         */
        val allowance: Map<String, Int> = emptyMap()

        /**
         * Empty, and meant to stay so: the only corners drawn off the scale are
         * the FABs', which read Material's FAB default instead of a role.
         */
        val roleReadAllowance: Map<String, Int> = emptyMap()

        /**
         * `CircleShape` used for something that actually is round, not a
         * stand-in for `LessonsShapeTokens.Pill` on a button or a chip — a
         * standing list, not a pending one, because none of these is meant to
         * move:
         * - `ui/week/CalendarGrids.kt` (1) — the week-strip and month-grid
         *   load dot, a few dp wide.
         * - `core/designsystem/.../component/FloatingToolbar.kt` (3) — the
         *   held tab's disc, and the tab's and the action button's own
         *   notification badge, each a small filled circle.
         * - `ui/settings/AppIconRows.kt` (2) — the «Значок приложения»
         *   preview of what a circle mask does to the icon, beside the
         *   squircle one, which is a circle by definition; and the settings
         *   row's small picture of the icon in use, a round tile in a row, the
         *   same round shape every row's leading `Tile` has.
         */
        val circleShapeAllowance: Map<String, Int> = mapOf(
            "app/src/main/kotlin/com/lumenpearson/lessons/ui/week/CalendarGrids.kt" to 1,
            ("core/designsystem/src/main/kotlin/com/lumenpearson/lessons/core/designsystem/" +
                "component/FloatingToolbar.kt") to 3,
            "app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/AppIconRows.kt" to 2,
        )
    }
}
