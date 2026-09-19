package com.lumenpearson.lessons.core.model

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on what `compose-stability.conf` promises about this module.
 *
 * That file tells the Compose compiler that everything in
 * `com.lumenpearson.lessons.core.model` is stable, because this module is
 * deliberately pure JVM — the Compose plugin is not applied here, which is why
 * its tests run in seconds — and so it carries no stability information of its
 * own. Without the promise `SchoolDay` is unstable, and one unstable field is
 * enough to make every screen state that holds a day unstable with it.
 *
 * A promise the compiler cannot verify is one a future declaration can quietly
 * turn into a lie, and the symptom is the worst kind: nothing fails to build,
 * nothing throws, a value simply changes and the screen keeps the old one,
 * because Compose was told it need not look.
 *
 * For a long time this was one regex for `var`, and a `var` is only the most
 * obvious way in. `val lessons: MutableList<Lesson>` is mutable without one. So
 * is `val slots: Array<String>`; so is a `Throwable`, which is the named reason
 * `:core:data` is kept off the promise entirely; so is a property of any type
 * from outside this module, which nothing here has promised anything about. A
 * function-typed property is the same defect in different clothes — two lambdas
 * that do the same thing are never `equals`, so what holds one is compared by
 * identity whatever the compiler makes of its stability. A `by lazy` is a field
 * written after construction. Each of those now fails here.
 *
 * It reads the source rather than the compiled classes on purpose, the same way
 * `ResourceTranslationTest` reads `values/` out of the tree: a `var` with a
 * private setter, a `lateinit`, a `by Delegates.observable` all compile to
 * different shapes and read identically here — the word is the rule.
 *
 * ## What it still cannot see
 *
 * - **A type parameter.** `data class Box<T>(val value: T)` is reported, because
 *   `T` is not a type this module promises anything about. Against the promise
 *   that is exactly right — the conf's wildcard says `Box` is stable whatever
 *   `T` turns out to be — but it is stricter than Compose, which resolves `T`
 *   per use site. The module has no generics; if it ever needs one, that is a
 *   decision to take deliberately rather than a line to loosen.
 * - **What a getter returns.** Only the declared type is read, so a `val` of a
 *   promised type whose getter hands back a live view of something mutable
 *   passes. Nothing here can do that today: the module has no dependencies to
 *   get a mutable thing from.
 * - **Kotlin that a parser would see and a regex does not.** A type spread over
 *   two lines, a local class inside a function body, a property on an anonymous
 *   object. A declaration that hides from this test is a declaration to move,
 *   not a test to work around.
 * - **`List`, `Set` and `Map`**, each of which is an interface a mutable list
 *   satisfies. That is not an oversight: it is the decision written out at the
 *   bottom of `compose-stability.conf`. They are deliberately not promised
 *   there, the Compose runtime resolves them by their element type — which is
 *   checked here like any other — and everything in this module builds them
 *   with `listOf`. Their `Mutable` twins and the concrete implementations are
 *   refused below.
 */
class StabilityPromiseTest {

    private val sources: List<File> = File("src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private val parsed: Map<File, ModuleSource> = sources.associateWith { ModuleSource(it) }

    /** Any `var`, wherever it is declared, including inside a function body. */
    private val varDeclaration = Regex("""(^|[^\w.])var\s+\w""")

    /** Every type this module declares: those are what the promise is about. */
    private val declaredHere: Set<String> = parsed.values.flatMap { it.declaredTypes }.toSet()

    @Test
    fun `the module has source to check`() {
        assertTrue(
            "No Kotlin source under src/main/kotlin. This test reads the tree " +
                "rather than the classpath, so an empty list is a silent pass.",
            sources.isNotEmpty(),
        )
        assertTrue(
            "No property was found in any of them, which means the reader below " +
                "stopped reading Kotlin rather than that the module stopped " +
                "declaring any. Everything here would then pass in silence.",
            parsed.values.sumOf { it.properties.size } > 20,
        )
    }

    @Test
    fun `nothing in the domain module is mutable`() {
        val offenders = report { source ->
            source.lines
                .filter { (_, line) -> varDeclaration.containsMatchIn(line) }
                .map { (number, line) -> number to line.trim() }
        }

        assertTrue(
            "`compose-stability.conf` promises the Compose compiler that this " +
                "whole package is stable, and a `var` makes that promise false. " +
                "Either make it a `val`, or move the type out of this module and " +
                "take the package off that file — do not leave both standing:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * The half a `var` never covered: a `val` of a type that changes underneath
     * it, or of a type nothing has promised anything about.
     */
    @Test
    fun `every property is of a type the promise covers`() {
        val offenders = report { source ->
            source.properties.mapNotNull { property ->
                val type = property.type ?: return@mapNotNull null
                reasonTypeIsNotPromised(type, source)
                    ?.let { property.number to "${property.text} — $it" }
            }
        }

        assertTrue(
            "`compose-stability.conf` promises the Compose compiler that this " +
                "whole package is stable. It can keep that promise only while " +
                "every field of every type in it is itself unchangeable, and " +
                "the compiler cannot check one of these — it was told not to " +
                "look:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * A property whose value this test cannot see through is unchecked.
     *
     * An inferred type hides what it is. A delegate — `by lazy`, `by
     * Delegates.observable` — hides where the value comes from, and for `lazy`
     * that is a field written after construction, which is a `var` with a
     * different spelling. `const` is exempt: it can only be a primitive or a
     * `String`, and it is decided at compile time.
     */
    @Test
    fun `every property declares its type and holds its own value`() {
        val offenders = report { source ->
            source.properties
                .filter { it.type == null || it.delegated }
                .map { property ->
                    val why = if (property.delegated) "a delegate" else "no declared type"
                    property.number to "${property.text} — $why"
                }
        }

        assertTrue(
            "This test reads the declared type of every property to check it " +
                "against what `compose-stability.conf` promises, so a property " +
                "that does not declare one is unchecked, and a delegated one " +
                "does not hold the value it declares. Write the type out; if it " +
                "is a delegate, it does not belong in this module:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * Nothing here may be a `Throwable`, in any position.
     *
     * A `Throwable` is mutable by design — `addSuppressed` and
     * `fillInStackTrace` are the whole point of it — and it is the named reason
     * `:core:data` is kept off this promise entirely, `ManageFailure` and
     * `DiaryFailure` being two of them. A failure type that grew here would be
     * promised stable by the wildcard, in silence.
     */
    @Test
    fun `nothing in the module is a Throwable`() {
        val offenders = report { source ->
            source.lines
                .filter { (_, line) -> throwableName.containsMatchIn(line) }
                .map { (number, line) -> number to line.trim() }
        }

        assertTrue(
            "`compose-stability.conf` names `ManageFailure` and `DiaryFailure` " +
                "as the reason `:core:data` is not promised: a `Throwable` is " +
                "mutable by design. One declared, thrown or held here would be " +
                "covered by the wildcard, and nothing would say so:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** Every file's findings as `Models.kt:66: …`, in one list. */
    private fun report(find: (ModuleSource) -> List<Pair<Int, String>>): List<String> =
        parsed.entries.flatMap { (file, source) ->
            find(source).map { (number, what) -> "${file.name}:$number: $what" }
        }

    /** Why [type] is not something this module can promise, or `null` if it is. */
    private fun reasonTypeIsNotPromised(type: String, source: ModuleSource): String? {
        if (type.contains("->") || type.startsWith("suspend ")) {
            return "a function type: two lambdas that do the same thing are " +
                "never equal, so what holds one is compared by identity"
        }
        val offender = typeName.findAll(type)
            .map { it.value }
            .firstOrNull { !isPromised(it, source) }
            ?: return null
        return when {
            offender in mutableCollections -> "`$offender` can be changed in place"
            offender.endsWith("Array") -> "an array's elements can be replaced in place"
            throwableName.containsMatchIn(offender) -> "a `Throwable` is mutable by design"
            else -> "`$offender` is promised by nothing — the conf covers " +
                "`java.time` and this module, and deliberately nothing else"
        }
    }

    private fun isPromised(name: String, source: ModuleSource): Boolean = when {
        // The conf's first line, however it is written: `java.time.LocalTime`
        // in full, or imported and then used by its short name.
        name.startsWith("java.time.") -> true
        name in source.javaTimeImports -> true
        name in declaredHere -> true
        name in kotlinValueTypes -> true
        name in readOnlyCollections -> true
        else -> false
    }

    private companion object {
        /** Final, immutable, no setters — the same reasoning as `java.time`. */
        val kotlinValueTypes = setOf(
            "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double", "Char",
            "String", "CharSequence", "Number", "Unit", "Nothing",
        )

        /** Read-only by interface; see the class comment for why they pass. */
        val readOnlyCollections = setOf("List", "Set", "Map", "Collection", "Iterable")

        val mutableCollections = setOf(
            "MutableList", "MutableSet", "MutableMap", "MutableCollection",
            "MutableIterable", "ArrayList", "HashMap", "HashSet", "LinkedHashMap",
            "LinkedHashSet", "TreeMap", "TreeSet", "SortedMap", "SortedSet",
            "ConcurrentHashMap", "StringBuilder",
        )

        val throwableName = Regex("""(^|[^\w.])(Throwable|\w*Exception|\w*Error)\b""")

        /** A type name, qualified or not; `?`, `<`, `,` and spaces end one. */
        val typeName = Regex("""[A-Za-z_][\w.]*""")
    }
}

/**
 * One source file, read as declarations rather than as lines of text.
 *
 * Comments go first, so that a sentence about a `var` is prose. The scope stack
 * is what tells a property from a local: a `val` is a property when the
 * innermost open brace belongs to a class, an interface or an object — or when
 * no brace is open at all, which is a constructor's parameter list or the top of
 * a file. A `val` inside a function body is a local, and a local decides nothing
 * about stability.
 */
private class ModuleSource(file: File) {

    /** Number and text of every line, comments removed, blank lines dropped. */
    val lines: List<Pair<Int, String>>

    /** The types this file declares, by their simple names. */
    val declaredTypes: Set<String>

    /** The simple names this file imported out of `java.time`. */
    val javaTimeImports: Set<String>

    /** Every property, in declaration order. */
    val properties: List<Property>

    init {
        lines = stripComments(file.readLines())
            .mapIndexed { index, line -> (index + 1) to line }
            .filter { (_, line) -> line.isNotBlank() }

        declaredTypes = lines.mapNotNull { (_, line) ->
            typeDeclaration.find(line)?.groupValues?.get(2)
        }.toSet()

        javaTimeImports = lines.mapNotNull { (_, line) ->
            javaTimeImport.find(line.trim())?.groupValues?.get(1)
        }.toSet()

        properties = readProperties()
    }

    /** A declared `val`: where it is, what it says, and the type it declares. */
    data class Property(
        val number: Int,
        val text: String,
        val type: String?,
        val delegated: Boolean,
    )

    private fun readProperties(): List<Property> {
        val found = mutableListOf<Property>()
        // One entry per open brace: true when that brace belongs to a class, an
        // interface or an object rather than to a function or a lambda.
        val scopes = ArrayDeque<Boolean>()
        lines.forEach { (number, line) ->
            val declaresProperties = scopes.lastOrNull() ?: true
            if (declaresProperties && !constant.containsMatchIn(line)) {
                valDeclaration.findAll(line).forEach { match ->
                    found += property(number, line, match.range.last + 1)
                }
            }
            val classLike = typeDeclaration.containsMatchIn(line) &&
                !functionWord.containsMatchIn(line)
            line.forEach { character ->
                when (character) {
                    '{' -> scopes.addLast(classLike)
                    '}' -> scopes.removeLastOrNull()
                }
            }
        }
        return found
    }

    /** What follows `val ` on [line], cut back to the declaration itself. */
    private fun property(number: Int, line: String, after: Int): Property {
        val rest = line.substring(after)
        val name = rest.takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
        val tail = rest.drop(name.length).trimStart()
        val delegated = tail.startsWith("by ") || tail.contains(" by ")
        if (!tail.startsWith(":")) {
            return Property(number, "val $name ${tail.trimEnd(',')}".trim(), null, delegated)
        }
        var type = tail.drop(1)
        typeEnders.forEach { ender -> type = type.substringBefore(ender) }
        type = type.trim().trimEnd(',').trim()
        return Property(number, "val $name: $type", type, delegated)
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

    private companion object {
        val typeDeclaration = Regex("""(^|[^\w.])(?:class|interface|object)\s+(\w+)""")
        val functionWord = Regex("""(^|[^\w.])fun\s""")
        val javaTimeImport = Regex("""^import\s+java\.time\.(?:\w+\.)*(\w+)$""")
        val valDeclaration = Regex("""(^|[^\w.])val\s""")

        /** A compile-time constant is a primitive or a `String`, and nothing else. */
        val constant = Regex("""(^|[^\w.])const\s""")

        /** Where a declared type stops: a default value, an accessor, a delegate. */
        val typeEnders = listOf(" = ", " get(", " set(", " by ")
    }
}
