package com.lumenpearson.lessons.ui.onboarding

import com.lumenpearson.lessons.core.data.repository.AppSettings
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * What the first run and its neighbours say about the app, held against what
 * the app is.
 *
 * Read out of the source tree, as `ResourceTranslationTest` does: the question
 * is about the words written, and nothing at run time would notice them being
 * wrong — the screens draw a false sentence as happily as a true one.
 */
class OnboardingPromisesTest {

    private val modules = listOf("app", "core/data", "core/designsystem", "widget")

    /** Every `<string>` and `<plurals>` item in every module and language, as name to text. */
    private val shipped: List<Pair<String, String>> by lazy {
        modules.flatMap { module ->
            File("../$module/src/main/res").listFiles { file -> file.isDirectory && file.name.startsWith("values") }
                .orEmpty()
                .flatMap { folder -> folder.listFiles { file -> file.name.startsWith("strings") }.orEmpty().toList() }
                .flatMap { file ->
                    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    val strings = document.getElementsByTagName("string")
                    val items = document.getElementsByTagName("item")
                    (0 until strings.length).map { strings.item(it) as Element }.map {
                        "${file.parentFile.name}/${it.getAttribute("name")}" to it.textContent
                    } + (0 until items.length).map { items.item(it) as Element }.map {
                        "${file.parentFile.name}/plural" to it.textContent
                    }
                }
        }
    }

    /**
     * #154. The APK carries no server address — deliberately, no address is
     * right for a second user — and three places said otherwise: the join
     * screen's «По умолчанию — сервер класса», the sheet's «Меняйте только если
     * школа использует свой сервер», and the settings row's «По умолчанию». A
     * pupil who believed them pressed «Подключиться» on a blank address.
     */
    @Test
    fun `no string promises a server the app does not have`() {
        assertEquals("the premise: no address is built in", "", AppSettings.DEFAULT_BASE_URL)
        assertTrue("the strings were not found from ${File("").absolutePath}", shipped.size > 500)

        val promises = listOf(
            "По умолчанию — сервер",
            "только если школа использует свой сервер",
            "прошит в сборке",
            "Default — the class server",
            "only if the school runs its own server",
            "built into this APK",
        )
        val offenders = shipped.filter { (_, text) -> promises.any { it in text } }
        assertTrue("These promise a default server: $offenders", offenders.isEmpty())

        val unset = shipped.filter { (name, _) ->
            name.endsWith("/join_server_unset") || name.endsWith("/settings_server_url_unset")
        }
        assertEquals("both languages, both rows", 4, unset.size)
        for ((name, text) in unset) {
            assertTrue("$name: $text", "умолчани" !in text.lowercase() && "default" !in text.lowercase())
        }
    }

    /**
     * #157. The first run is not four screens or five any more and the class
     * code is not six characters; the comments that counted either were a
     * wrong map for the next change, which is what CLAUDE.md sends a reader to
     * them for.
     */
    @Test
    fun `no comment counts the first run's screens or calls the class code six characters`() {
        val stale = listOf(
            Regex("""\b(four|five) screens\b"""),
            Regex("""\bone of four\b"""),
            Regex("""six-character (class )?code (from|is)"""),
            Regex("""six-character class code"""),
        )
        val sources = File("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .toList()
        assertTrue("the sources were not found from ${File("").absolutePath}", sources.size > 50)
        val offenders = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.path}:${index + 1}: ${line.trim()}".takeIf { stale.any { it.containsMatchIn(line) } }
            }
        }
        assertTrue("Stale counts:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    /**
     * The owner's line under the first screen's button — accepting the terms
     * and the privacy policy — is drawn always, whatever the build says about
     * where its documents are: with no address it opens the bundled copies.
     */
    @Test
    fun `the welcome step always draws the legal line`() {
        val source = File("src/main/kotlin/com/lumenpearson/lessons/ui/onboarding/OnboardingScreen.kt").readText()
        val welcome = source.substringAfter("private fun WelcomeStep(").substringBefore("\n}\n")
        assertTrue("WelcomeStep has no legal footer", "footer = { LegalAcceptanceLine() }" in welcome)
        assertTrue("the line must not depend on the build", "BuildConfig" !in welcome)
    }
}
