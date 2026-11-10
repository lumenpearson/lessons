package com.lumenpearson.lessons.core.model

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.io.ByteArrayInputStream
import java.nio.file.attribute.BasicFileAttributes
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

// The independent format/ratchet cases belong beside the one reader they hold.
@Suppress("TooManyFunctions")
class DetektBaselineBudgetTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a rewritten baseline cannot absorb another finding`() {
        val problems = budgetProblems(baseline("Old", "New"), budget(1, "Old"))
        assertTrue(problems.toString(), problems.any { "grew" in it })
        assertTrue(problems.toString(), problems.any { "New" in it })
    }

    @Test
    fun `removing a finding also lowers the committed limit`() {
        assertTrue(budgetProblems(baseline(), budget(1, "Old")).any { "Lower" in it })
        assertEquals(emptyList<String>(), budgetProblems(baseline(), budget(0)))
    }

    @Test
    fun `a move needs a reviewed snapshot without raising its limit`() {
        assertTrue(budgetProblems(baseline("New.kt"), budget(1, "Old.kt")).any { "New.kt" in it })
        assertEquals(emptyList<String>(), budgetProblems(baseline("New.kt"), budget(1, "New.kt")))
    }

    @Test
    fun `manual suppressions still consume the same budget`() {
        val current = record(null, listOf("Old"), listOf("ManualNew"))
        assertTrue(budgetProblems(current, budget(1, "Old")).any { "ManualNew" in it })
    }

    @Test
    fun `making an entry permanently manual needs an explicit snapshot refresh`() {
        val manual = record(null, emptyList(), listOf("Old"))
        assertTrue(budgetProblems(manual, budget(1, "Old")).any { "ManuallySuppressedIssues" in it })
        assertEquals(emptyList<String>(), budgetProblems(manual, record(1, emptyList(), listOf("Old"))))
    }

    @Test
    fun `root text cannot disappear from an otherwise matching record`() {
        val invalid = baseline().replace("<SmellBaseline>", "<SmellBaseline>broken")
        assertTrue(budgetProblems(invalid, budget(0)).any { "Unexpected text" in it })
        val invalidBudget = budget(0).replace("<CurrentIssues/>", "broken<CurrentIssues/>")
            .replace("<CurrentIssues>", "broken<CurrentIssues>")
        assertTrue(budgetProblems(baseline(), invalidBudget).any { "Unexpected text" in it })
    }

    @Test
    fun `group text cannot disappear beside a valid ID`() {
        val invalid = baseline("Old").replace("</ID>", "</ID>unwrapped")
        assertTrue(budgetProblems(invalid, budget(1, "Old")).any { "Unexpected text" in it })
        val invalidBudget = budget(1, "Old").replace("</ID>", "</ID>unwrapped")
        assertTrue(budgetProblems(baseline("Old"), invalidBudget).any { "Unexpected text" in it })
    }

    @Test
    fun `a UTF8 byte order mark keeps the accepted identity`() {
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        assertEquals(
            emptyList<String>(),
            budgetProblems(bom + baseline("Old").toByteArray(), budget(1, "Old").toByteArray()),
        )
    }

    @Test
    fun `an encoding declaration cannot compare a different identity as accepted`() {
        val current = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + baseline("Rule:é")
        val problems = budgetProblems(current.toByteArray(), budget(1, "Rule:é").toByteArray())
        assertTrue(problems.toString(), problems.any { "Ã©" in it })
    }

    @Test
    fun `a correctly encoded declared identity matches UTF8 policy`() {
        val current = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + baseline("Rule:é")
        assertEquals(
            emptyList<String>(),
            budgetProblems(current.toByteArray(Charsets.ISO_8859_1), budget(1, "Rule:é").toByteArray()),
        )
    }

    @Test
    fun `an empty baseline has an explicit zero budget`() {
        assertEquals(emptyList<String>(), budgetProblems(baseline(), budget(0)))
    }

    @Test
    fun `a budget cannot claim more entries than its snapshot`() {
        assertTrue(budgetProblems(baseline("Old"), budget(2, "Old")).isNotEmpty())
        assertTrue(budgetProblems(baseline(), budget(-1)).isNotEmpty())
    }

    @Test
    fun `duplicate and malformed records are refused`() {
        val malformed = listOf(
            "<Wrong/>",
            baseline("Old", "Old"),
            "<SmellBaseline><CurrentIssues/><Other/></SmellBaseline>",
            baseline(""),
        )
        malformed.forEach { current ->
            assertTrue(current, budgetProblems(current, budget(1, "Old")).isNotEmpty())
        }
    }

    @Test
    fun `XML escaping does not change a finding identity`() {
        val id = "Rule:File.kt:fun x<T>() & value"
        assertEquals(emptyList<String>(), budgetProblems(baseline(id), budget(1, id)))
    }

    @Test
    fun `a future nested module is discovered without a module list`() {
        val root = temporary.newFolder("android")
        pair(root)
        File(root, "feature/nested/$BaselineName").writeText(baseline("Old", "NestedNew"))
        val problems = treeProblems(root)
        assertTrue(problems.toString(), problems.any { "NestedNew" in it && "nested" in it })
        File(root, "feature/nested/$BaselineName").writeText(baseline("Old"))
        assertEquals(emptyList<String>(), treeProblems(root))
    }

    @Test
    fun `a Windows junction is pruned before any external record is parsed`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = temporary.newFolder("android")
        pair(root)
        val outside = temporary.newFolder("outside-junction")
        File(outside, BaselineName).writeText("<invalid/>")
        File(outside, BudgetName).writeText(budget(0))
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", File(root, "junction").path, outside.path)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
        assertEquals(emptyList<String>(), treeProblems(root))
    }

    @Test
    fun `a missing budget or baseline cannot hide its counterpart`() {
        val root = temporary.newFolder("android")
        File(root, "feature/nested").mkdirs()
        File(root, "feature/nested/$BaselineName").writeText(baseline("Old"))
        assertTrue(treeProblems(root).any { BudgetName in it })
        val other = temporary.newFolder("other")
        File(other, BudgetName).writeText(budget(0))
        assertTrue(treeProblems(other).any { BaselineName in it })
    }

    @Test
    fun `a link cannot make a private target a baseline record`() {
        val root = temporary.newFolder("android")
        File(root, BaselineName).writeText(baseline("Old"))
        val outside = temporary.newFile("outside.xml").apply { writeText(budget(1, "Old")) }
        try {
            Files.createSymbolicLink(File(root, BudgetName).toPath(), outside.toPath())
        } catch (error: IOException) {
            assumeNoException(error)
        }
        assertTrue(treeProblems(root).any { "regular" in it })
    }

    @Test
    fun `XML cannot load an external entity`() {
        val xml = """
            <!DOCTYPE SmellBaseline [<!ENTITY external SYSTEM "file:///not-a-real-budget-file">]>
            <SmellBaseline><CurrentIssues><ID>&external;</ID></CurrentIssues>
            <ManuallySuppressedIssues/></SmellBaseline>
        """.trimIndent()
        assertTrue(budgetProblems(xml, budget(1, "Old")).any { "DOCTYPE" in it })
    }

    @Test
    fun `every current baseline fits its committed budget`() {
        assertEquals(emptyList<String>(), treeProblems(androidRoot()))
    }

    private fun baseline(vararg ids: String) = record(null, ids.toList())

    private fun budget(maximum: Int, vararg ids: String) = record(maximum, ids.toList())

    private fun record(maximum: Int?, current: List<String>, manual: List<String> = emptyList()): String {
        val attribute = maximum?.let { " maxEntries=\"$it\"" }.orEmpty()
        fun ids(values: List<String>) = values.joinToString("") {
            val escaped = it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            "<ID>$escaped</ID>"
        }
        return "<SmellBaseline$attribute><CurrentIssues>${ids(current)}</CurrentIssues>" +
            "<ManuallySuppressedIssues>${ids(manual)}</ManuallySuppressedIssues></SmellBaseline>"
    }

    private fun pair(root: File) {
        val module = File(root, "feature/nested").apply { mkdirs() }
        File(module, BaselineName).writeText(baseline("Old"))
        File(module, BudgetName).writeText(budget(1, "Old"))
    }

    private fun androidRoot(): File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .firstNotNullOfOrNull { here ->
            here.takeIf { File(it, "settings.gradle.kts").isFile }
                ?: File(here, "android").takeIf { File(it, "settings.gradle.kts").isFile }
        } ?: error("Cannot find the Android build root")

    private fun budgetProblems(baseline: String, budget: String): List<String> =
        budgetProblems(baseline.toByteArray(), budget.toByteArray())

    private fun budgetProblems(baseline: ByteArray, budget: ByteArray): List<String> = try {
        val current = parseRecord(baseline, false)
        val accepted = parseRecord(budget, true)
        require(accepted.maximum == accepted.ids.size) { "maxEntries must equal the accepted snapshot size" }
        val added = current.ids - accepted.ids
        val removed = accepted.ids - current.ids
        when {
            current.ids.size > accepted.ids.size -> listOf("Baseline grew. Added IDs: $added")
            current.ids.size < accepted.ids.size -> listOf(
                "Lower maxEntries to ${current.ids.size} and refresh the snapshot. " +
                    "Removed IDs: $removed; added IDs: $added",
            )
            current.ids != accepted.ids -> listOf(
                "Review and refresh the snapshot without raising maxEntries. Added IDs: $added; removed IDs: $removed",
            )
            else -> emptyList()
        }
    } catch (error: Exception) {
        listOf("Invalid baseline/budget: ${error.message}")
    }

    private data class Entry(val group: String, val id: String)

    private data class Snapshot(val ids: Set<Entry>, val maximum: Int?)

    private fun parseRecord(xml: ByteArray, budget: Boolean): Snapshot {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val builder = factory.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(error: SAXParseException): Nothing = throw error
                override fun fatalError(error: SAXParseException): Nothing = throw error
            })
        }
        val root = ByteArrayInputStream(xml).use { builder.parse(it).documentElement }
        require(root.tagName == "SmellBaseline") { "Expected SmellBaseline root" }
        val maximum = if (budget) root.getAttribute("maxEntries").toIntOrNull() else null
        require(root.attributes.length == if (budget) 1 else 0) { "Unexpected root attributes" }
        require(!budget || maximum != null && maximum >= 0) { "Expected nonnegative maxEntries" }
        val groups = children(root)
        require(groups.map { it.tagName }.sorted() == listOf("CurrentIssues", "ManuallySuppressedIssues")) {
            "Expected exactly one CurrentIssues and ManuallySuppressedIssues"
        }
        val ids = groups.flatMap { group ->
            require(group.attributes.length == 0) { "Unexpected group attributes" }
            children(group).map { id ->
                require(id.tagName == "ID" && id.attributes.length == 0 && children(id, allowText = true).isEmpty()) {
                    "Expected ID leaves"
                }
                val value = id.textContent.also { require(it.isNotBlank()) { "Empty ID" } }
                Entry(group.tagName, value)
            }
        }
        require(ids.size == ids.map { it.id }.toSet().size) { "Duplicate ID" }
        return Snapshot(ids.toSet(), maximum)
    }

    private fun children(element: Element, allowText: Boolean = false): List<Element> {
        val nodes = (0 until element.childNodes.length).map { element.childNodes.item(it) }
        require(allowText || nodes.filter { it.nodeType in setOf(Node.TEXT_NODE, Node.CDATA_SECTION_NODE) }
            .all { it.nodeValue.isBlank() }) { "Unexpected text in ${element.tagName}" }
        return nodes.filterIsInstance<Element>()
    }

    private fun treeProblems(root: File): List<String> {
        val realRoot = root.toPath().toRealPath()
        val records = root.walkTopDown().onEnter {
            val attributes = Files.readAttributes(
                it.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
            )
            it.name !in setOf("build", ".gradle") && !attributes.isSymbolicLink && !attributes.isOther
        }.filter { it.name in setOf(BaselineName, BudgetName) }.toList()
        return records.map { it.parentFile }.distinct().sortedBy { it.path }.flatMap { module ->
            val baseline = File(module, BaselineName)
            val budget = File(module, BudgetName)
            val invalid = listOf(baseline, budget).filter {
                !Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    !it.toPath().toRealPath().startsWith(realRoot)
            }
            val problems = if (invalid.isNotEmpty()) {
                invalid.map { "Expected a regular file: ${it.name}" }
            } else {
                budgetProblems(baseline.readBytes(), budget.readBytes())
            }
            problems.map { "${module.relativeTo(root)}: $it" }
        }
    }

    private companion object {
        const val BaselineName = "detekt-baseline.xml"
        const val BudgetName = "detekt-baseline-budget.xml"
    }
}
