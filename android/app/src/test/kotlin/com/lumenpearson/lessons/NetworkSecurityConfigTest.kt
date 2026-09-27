package com.lumenpearson.lessons

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The release build talks to its server over https only; the debug build keeps
 * cleartext for development (#202).
 *
 * The owner's decision of 27 September 2026, and it lives in two files of one
 * name: `src/main/res/xml/network_security_config.xml` is what a release build
 * — and any build type without a copy of its own — is given, and
 * `src/debug/res/xml/` overrides it for the debug build. Nothing in Gradle
 * chooses between them, so nothing but this notices if the override is
 * deleted (the debug build would silently refuse every LAN server a developer
 * points it at) or if the release file drifts back to permitting cleartext
 * (every class bearer on a school's Wi-Fi in the clear again, with no failure
 * anywhere).
 *
 * What this cannot say is that a phone reads the files the way it is written
 * here; it was never run on a device. The app asks the platform the same
 * question it asks here (`CleartextPolicy` in :core:data), so a device that
 * disagreed would at least disagree consistently.
 */
class NetworkSecurityConfigTest {

    private val release = parse("src/main/res/xml/network_security_config.xml")
    private val debug = parse("src/debug/res/xml/network_security_config.xml")

    @Test
    fun `a release build refuses cleartext except to the phone itself`() {
        val base = release.single("base-config")
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))

        val permitted = release.children("domain-config")
            .filter { it.getAttribute("cleartextTrafficPermitted") == "true" }
        assertEquals("one exception, not a list that grows", 1, permitted.size)
        val domains = permitted.single().children("domain")
        assertEquals(setOf("localhost", "127.0.0.1"), domains.map { it.textContent.trim() }.toSet())
        for (domain in domains) {
            assertFalse(
                "${domain.textContent} with its subdomains is not the phone itself",
                domain.getAttribute("includeSubdomains") == "true",
            )
        }
        assertTrue("no debug-overrides in the release file", release.children("debug-overrides").isEmpty())
    }

    @Test
    fun `a debug build keeps cleartext to every host`() {
        assertEquals("true", debug.single("base-config").getAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun `neither build trusts a certificate the user installed`() {
        for ((name, config) in listOf("release" to release, "debug" to debug)) {
            val anchors = config.single("base-config").children("trust-anchors").single().children("certificates")
            assertEquals(
                "$name trusts the system's CAs and nothing else",
                listOf("system"),
                anchors.map { it.getAttribute("src") },
            )
        }
    }

    @Test
    fun `the manifest points at the file both builds name`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:networkSecurityConfig="@xml/network_security_config""""))
        assertFalse(
            "usesCleartextTraffic is ignored beside a configuration file, and would only tell a reader the opposite",
            manifest.contains("usesCleartextTraffic"),
        )
    }

    // -------------------------------------------------------------------------

    private fun parse(path: String): Element {
        val file = File(path)
        assertTrue("$path is missing — the walk went wrong, or the file was deleted", file.isFile)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
    }

    private fun Element.single(tag: String): Element = children(tag).single()
}
