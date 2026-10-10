package com.lumenpearson.lessons.appicon

import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What the JVM test cannot see: that each catalog line's `R.mipmap` id is the
 * resource its style and palette name, and that it loads.
 *
 * Sixteen lines of `AppIconVariant(AMOLED, MYATA, R.mipmap.ic_launcher_amoled_myata)`
 * are sixteen chances to pair a tile's name with another tile's picture, and
 * the compiler is satisfied by any id.
 */
@RunWith(RobolectricTestRunner::class)
class AppIconResourcesTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `each entry's icon is the resource its name says`() {
        for (variant in AppIconCatalog.variants) {
            assertEquals(variant.key, "mipmap", context.resources.getResourceTypeName(variant.icon))
            assertEquals(variant.key, variant.resourceName, context.resources.getResourceEntryName(variant.icon))
        }
    }

    @Test
    fun `each entry's icon loads as an adaptive icon with both layers`() {
        for (variant in AppIconCatalog.variants) {
            val icon = context.getDrawable(variant.icon)
            assertTrue("${variant.key} is ${icon?.javaClass}", icon is AdaptiveIconDrawable)
            val adaptive = icon as AdaptiveIconDrawable
            assertNotNull(variant.key, adaptive.background)
            assertNotNull(variant.key, adaptive.foreground)
        }
    }
}
