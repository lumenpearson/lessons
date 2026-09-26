package com.lumenpearson.lessons

import java.io.File
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legal address the build is given, and the wire that gives it.
 *
 * `BuildPropertyReachTest` already fails when `LESSONS_LEGAL_BASE_URL` is read
 * by the build and set by no workflow. What it cannot see is the rest of this
 * property's contract, each half of which would be invisible until somebody
 * tapped the first link of a fresh install: that the workflow passes the value
 * its own «Resolve legal documents» step worked out rather than some other
 * expression, that the default a local build falls back to is an https address
 * that ends where the four files are, and that the folder the APK bundles is
 * the folder the address names.
 */
class LegalBuildPropertyTest {

    private val root: File by lazy {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return@lazy directory.parentFile ?: directory
            directory = directory.parentFile
        }
        error("Could not find the Gradle root from ${File("").absolutePath}")
    }

    private val buildScript by lazy { File(root, "android/app/build.gradle.kts").readText() }
    private val workflow by lazy { File(root, ".github/workflows/apk.yml").readText() }

    @Test
    fun `the build reads the property under both of its names`() {
        assertTrue(
            buildScript.contains("""signingSecret("LESSONS_LEGAL_BASE_URL", "lessons.legal.baseUrl")"""),
        )
        assertTrue(buildScript.contains("""buildConfigField("String", "LEGAL_BASE_URL""""))
    }

    @Test
    fun `the default is an https address of this repository's docs legal`() {
        val default = Regex("""val defaultLegalBaseUrl = "([^"]+)"""").find(buildScript)?.groupValues?.get(1)
        assertTrue("app/build.gradle.kts no longer names its default legal address", default != null)
        val uri = URI(checkNotNull(default))
        assertEquals("https", uri.scheme)
        // blob/HEAD rather than a branch: GitHub resolves HEAD to the default
        // branch, and an installed APK keeps its link long after a named branch
        // is deleted.
        assertTrue("the default must follow the default branch: $default", "/blob/HEAD/" in uri.path)
        val folder = uri.path.substringAfter("/blob/HEAD/")
        assertEquals("docs/legal", folder)
    }

    @Test
    fun `a value that is not https is refused by the build, not kept`() {
        // The check itself runs in Gradle, which a unit test cannot start; what
        // this holds is that the refusal is there and is a failure rather than
        // a fallback to the default.
        assertTrue(buildScript.contains("""uri.scheme == "https""""))
        val start = buildScript.indexOf("val legalBaseUrl")
        assertTrue("legalBaseUrl is not declared", start >= 0)
        val block = buildScript.substring(start, buildScript.indexOf("\n\n", start))
        assertTrue("an unusable address must stop the build: $block", "throw GradleException" in block)
    }

    @Test
    fun `the workflow passes what its resolve step worked out`() {
        assertTrue("apk.yml has no step with id: legal", Regex("""(?m)^\s+id: legal\s*$""").containsMatchIn(workflow))
        assertTrue(
            "the build step must pass steps.legal.outputs.url, or the fork's own address never reaches the APK",
            Regex("""(?m)^\s+LESSONS_LEGAL_BASE_URL: \$\{\{ steps\.legal\.outputs\.url }}\s*$""")
                .containsMatchIn(workflow),
        )
        // The step runs before anything is compiled, so a bad variable fails in
        // seconds rather than at the end of the build.
        assertTrue(
            "the legal step must come before the build",
            workflow.indexOf("id: legal") < workflow.indexOf("LESSONS_LEGAL_BASE_URL: \${{"),
        )
    }

    @Test
    fun `the folder the APK bundles is the folder the address names`() {
        assertTrue(
            "app/build.gradle.kts must mount docs/legal as an asset folder, or an " +
                "offline reader is told the document could not be opened",
            buildScript.contains("""assets.srcDirs(rootProject.layout.projectDirectory.dir("../docs/legal"))"""),
        )
        val folder = File(root, "docs/legal")
        listOf("terms.ru.md", "terms.en.md", "privacy.ru.md", "privacy.en.md", "legal.json").forEach { name ->
            assertTrue("docs/legal/$name is missing; the address and the APK both name it", File(folder, name).isFile)
        }
    }
}
