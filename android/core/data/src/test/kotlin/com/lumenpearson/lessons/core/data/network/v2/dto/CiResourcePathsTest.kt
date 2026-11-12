package com.lumenpearson.lessons.core.data.network.v2.dto

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CiResourcePathsTest {
    @Test
    fun `every shared resource selects Android when its bytes change`() {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "buf.yaml").isFile }
        val build = File(root, "android/core/data/build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/ci.yml").readText()
        val patterns = workflow.lineSequence().single { it.trim().endsWith(") android=true ;;") }
            .trim().substringBefore(')').split('|').map { pattern ->
                Regex("^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$")
            }
        val inputs = Regex("""inRepository\("([^"]+)"\)""").findAll(build)
            .map { it.groupValues[1] }.toSet()

        assertTrue("The v2 golden directory must be a declared shared resource", "server/tests/golden" in inputs)
        for (input in inputs) {
            assertTrue("$input changes must run Android", patterns.any { it.matches("$input/fixture.json") })
        }
    }
}
