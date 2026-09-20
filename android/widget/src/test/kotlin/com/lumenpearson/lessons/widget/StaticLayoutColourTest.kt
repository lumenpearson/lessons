package com.lumenpearson.lessons.widget

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The two layouts the launcher inflates for us have to carry their own colours.
 *
 * Everything else the widget draws goes through `GlanceTheme`, which resolves
 * against the system palette at render time. These two do not: `initialLayout`
 * is what the home screen shows between the drop and the first render — most of
 * a minute on a cold process — and `previewLayout` is what the widget picker
 * draws, and neither ever runs a line of our code.
 *
 * They are inflated under the stub theme in `res/values/themes.xml`, and that
 * theme is `Theme.DeviceDefault`, which is the **dark** one — `DayNight`
 * arrived in API 29 and this module ships to 26, which is exactly what the
 * comment in that file says. Below 29 the theme is therefore dark while
 * `res/values/colors.xml` hands out the light surface, because `values-night`
 * keys off a night mode those versions have no switch for. A text view that
 * inherits `textColorPrimary` under that pairing is white on `#F4F1EC`: the
 * «Загружаем расписание…» that exists to stop the widget looking broken on its
 * first appearance is drawn invisibly, on every Android 8 and 9 phone, which
 * leaves the blank rectangle the layout was written to prevent.
 *
 * So the rule is that nothing in these two inherits a colour, and it is checked
 * here rather than trusted: the preview's three text views name one each, and
 * the loading layout's did not — one file out of two, which is what an
 * invariant nobody can see looks like.
 */
class StaticLayoutColourTest {

    private val res = File("src/main/res")

    /** `android:initialLayout` and `android:previewLayout`, as files. */
    private val launcherInflated: List<File> by lazy {
        val provider = parse(File(res, "xml/lessons_widget_info.xml"))
        listOf("android:initialLayout", "android:previewLayout")
            .map { provider.getAttribute(it) }
            .filter { it.isNotEmpty() }
            .map { File(res, "layout/${it.removePrefix("@layout/")}.xml") }
    }

    @Test
    fun `the provider still names two layouts for the launcher to draw`() {
        // The walk below is only as wide as this list: a provider that stopped
        // naming either would make every assertion here pass in silence.
        assertEquals(2, launcherInflated.size)
        launcherInflated.forEach { assertTrue("${it.path} is missing", it.isFile) }
    }

    @Test
    fun `nothing the launcher inflates for us inherits its text colour`() {
        val offenders = launcherInflated.flatMap { layout ->
            textElementsOf(layout)
                .filter { it.getAttribute("android:textColor").isEmpty() }
                .map { "${layout.name}: <${it.tagName} android:text=\"${it.getAttribute("android:text")}\">" }
        }

        assertTrue(
            "These are inflated by the launcher under Theme.DeviceDefault, which " +
                "is dark below API 29, over the light surface in res/values/colors.xml. " +
                "An inherited textColorPrimary is white on #F4F1EC — name a colour from " +
                "the pair that has a values-night twin instead:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun textElementsOf(layout: File): List<Element> {
        val found = mutableListOf<Element>()
        fun walk(node: Node) {
            if (node is Element && node.tagName.substringAfterLast('.').endsWith("TextView")) {
                found += node
            }
            val children = node.childNodes
            for (index in 0 until children.length) walk(children.item(index))
        }
        walk(parse(layout))
        return found
    }

    /** Namespace-unaware on purpose, so an attribute is read as it is written. */
    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
}
