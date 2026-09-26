package com.lumenpearson.lessons.widget

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every layout this module ships is inflated by the launcher as RemoteViews, and
 * RemoteViews inflates a short list of classes and nothing else (#167).
 *
 * The list is the classes the platform marks `@RemoteView`; anything else is
 * refused at inflation with «Class not allowed to be inflated», and the launcher
 * then draws «Couldn't add widget.» in place of the whole layout. The preview
 * did exactly that on every phone from API 31 up, because of one plain `<View>`
 * drawing a progress bar — and nothing on the JVM could see it, because nothing
 * here inflates these files: Glance builds the live widget in code, and the two
 * XML layouts are read only by a launcher.
 *
 * So the tags are read out of the source tree and compared with the list. It
 * cannot say whether a layout *looks* right — a launcher on a device can — only
 * whether a launcher will inflate it at all.
 */
class RemoteViewsLayoutTest {

    private val res = File("src/main/res")

    @Test
    fun `every widget layout uses only classes RemoteViews will inflate`() {
        val layouts = res.listFiles { dir -> dir.isDirectory && dir.name.startsWith("layout") }
            .orEmpty()
            .flatMap { it.listFiles { file -> file.extension == "xml" }.orEmpty().toList() }
        assertTrue(
            "No layouts under ${res.absolutePath} — the walk went wrong, not the layouts",
            layouts.isNotEmpty(),
        )

        val refused = layouts.flatMap { file ->
            tagsOf(file).filterNot { it in Inflatable }.map { "${file.parentFile?.name}/${file.name}: <$it>" }
        }
        assertTrue(
            "RemoteViews refuses these, and the launcher draws «Couldn't add widget.» " +
                "instead of the layout: $refused",
            refused.isEmpty(),
        )
    }

    @Test
    fun `the preview is among the layouts read`() {
        // The file whose one bad tag started this; if it moves, the test above
        // must still be reading it rather than passing over an empty folder.
        assertTrue(File(res, "layout/widget_preview.xml").isFile)
    }

    private fun tagsOf(file: File): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val all = document.getElementsByTagName("*")
        return (0 until all.length).map { (all.item(it) as Element).tagName }
    }

    private companion object {
        /**
         * `@RemoteView` classes, by the short name a layout uses. From API 31 the
         * compound buttons join the list; this module's `minSdk` is 26, and a
         * preview is read only from 31, but the initial layout is read from 26,
         * so the older list is the one both are held to.
         */
        val Inflatable = setOf(
            "FrameLayout",
            "LinearLayout",
            "RelativeLayout",
            "GridLayout",
            "AnalogClock",
            "Button",
            "Chronometer",
            "ImageButton",
            "ImageView",
            "ProgressBar",
            "TextView",
            "ViewFlipper",
            "ListView",
            "GridView",
            "StackView",
            "AdapterViewFlipper",
            "ViewStub",
        )
    }
}
