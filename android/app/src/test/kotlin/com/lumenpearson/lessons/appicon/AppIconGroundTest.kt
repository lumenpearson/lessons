package com.lumenpearson.lessons.appicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [AppIconStyle.flatGround] against the grounds android/logo's generators
 * wrote, read out of the source tree.
 *
 * `AppIconImage` leaves a flat ground out. A style marked flat whose ground
 * became a gradient would lose its look in the app without anything failing,
 * and a flat one marked otherwise would put a tile back under the mark.
 */
class AppIconGroundTest {

    private val drawables = File("src/main/res/drawable")

    @Test
    fun `a style is flat exactly when every palette's ground is one flat colour`() {
        for (style in AppIconStyle.entries) {
            for (palette in AppIconPalette.entries) {
                val name = "ic_launcher_${style.key}_${palette.key}_background"
                // A ground the generator could only draw as a picture (glass, blur) is a .webp in the
                // density folders, never a vector, and a picture is never a flat colour.
                val vector = File(drawables, "$name.xml")
                val flat = vector.isFile && isFlatFill(vector.readText())
                assertEquals("$name: flatGround", style.flatGround, flat)
            }
        }
    }

    @Test
    fun `the plates the app leaves out are the ones that are flat`() {
        assertEquals(
            setOf(AppIconStyle.CLASSIC, AppIconStyle.AMOLED),
            AppIconStyle.entries.filter { it.flatGround }.toSet(),
        )
        assertTrue(drawables.isDirectory)
    }

    /**
     * Whether a generated ground vector is one flat colour: the plate a
     * launcher needs, and nothing of the style's own.
     *
     * One shape, filled by a colour attribute. A gradient ground also has one
     * path, but its fill is an `<aapt:attr name="android:fillColor">` holding a
     * `<gradient>`, never the attribute itself. Two shapes of one colour are no
     * plate either, so the count is asked as well.
     */
    private fun isFlatFill(xml: String): Boolean =
        Regex("<path\\b").findAll(xml).count() == 1 &&
            "android:fillColor=\"#" in xml &&
            "<gradient" !in xml
}
