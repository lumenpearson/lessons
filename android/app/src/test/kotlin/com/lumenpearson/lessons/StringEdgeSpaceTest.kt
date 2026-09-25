package com.lumenpearson.lessons

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * No string relies on a space at its edge, because the build throws it away.
 *
 * aapt2 trims the leading and trailing whitespace of a `<string>` unless it is
 * quoted or escaped, so a sentence assembled from pieces with spaces at their
 * joins ships with the words run together. That is how the about card said
 * «по мотивамEssentials» in Russian and "afterEssentials" in English for as
 * long as it existed (#155): the source looked right, every test that read the
 * source agreed, and only the compiled APK was wrong. The answer is one
 * template with a placeholder, which keeps the space inside the text where aapt2
 * leaves it alone; this holds that nobody reaches for the pieces again.
 *
 * Every module that ships strings, in every language folder, discovered rather
 * than listed — the way `ResourceTranslationTest` finds them.
 */
class StringEdgeSpaceTest {

    @Test
    fun `no string starts or ends with a plain space`() {
        val folders = File("..").canonicalFile.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isDirectory && it.name.matches(Regex("values(-[a-zA-Z-]+)?")) }
            .filter { it.parentFile?.name == "res" && "/src/main/" in it.invariantSeparatorsPath }
            .toList()
        assertTrue("found no values folders from ${File("..").absolutePath}", folders.size >= 4)

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val offenders = folders.flatMap { folder ->
            folder.listFiles().orEmpty().filter { it.name.endsWith(".xml") }.flatMap { file ->
                val nodes = builder.parse(file).getElementsByTagName("string")
                (0 until nodes.length).mapNotNull { index ->
                    val element = nodes.item(index) as Element
                    val text = element.textContent
                    // A quoted string keeps its spaces, so only a bare one counts.
                    val quoted = text.length >= 2 && text.startsWith('"') && text.endsWith('"')
                    val edged = text.startsWith(' ') || text.endsWith(' ')
                    if (edged && !quoted) {
                        "${folder.parentFile.parentFile.parentFile.parentFile.name}/" +
                            "${folder.name}/${file.name}: ${element.getAttribute("name")} = «$text»"
                    } else {
                        null
                    }
                }
            }
        }
        assertTrue(
            "aapt2 trims these spaces, so the words either side of the join run " +
                "together in the APK — use one template with a %1\$s placeholder:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
