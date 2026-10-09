package com.lumenpearson.lessons.appicon

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The adapter against Robolectric's PackageManager: what an alias never set
 * reads as, and that both ways of writing land the same states.
 *
 * Robolectric runs SDK 34 here, so the path below Android 13 is taken by
 * handing the adapter an older [Build.VERSION.SDK_INT], not by emulating one.
 *
 * Over the real catalog, unlike the tests of the decisions: what is under
 * test is the adapter against the components this build's manifest declares.
 */
@RunWith(RobolectricTestRunner::class)
class PackageManagerComponentsTest {

    private val context = RuntimeEnvironment.getApplication()
    private val default = AppIconCatalog.default

    private fun stateOf(alias: String): Int =
        context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, alias))

    @Test
    fun `an alias never set reads as the manifest declares it`() {
        val components = PackageManagerComponents(context)

        assertTrue(components.isEnabled(default.alias))
        for (variant in AppIconCatalog.variants - default) {
            assertFalse(variant.key, components.isEnabled(variant.alias))
        }
    }

    @Test
    fun `from Android 13 the changes go as one batch`() = switchesOn(Build.VERSION_CODES.TIRAMISU)

    @Test
    fun `below Android 13 they go one call each`() = switchesOn(Build.VERSION_CODES.S_V2)

    private fun switchesOn(sdkInt: Int) {
        val other = secondAlias()
        val components = PackageManagerComponents(context, sdkInt = sdkInt)

        components.apply(listOf(AliasChange(other.alias, enabled = true), AliasChange(default.alias, enabled = false)))

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, stateOf(other.alias))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, stateOf(default.alias))
        assertTrue(components.isEnabled(other.alias))
        assertFalse(components.isEnabled(default.alias))
    }

    /**
     * A second alias the catalog still has, or the test is skipped. A switch
     * needs two components the manifest declares; one invented here would test
     * a name no phone has, since a real PackageManager refuses a component the
     * package does not declare. With only the default kept there is no switch
     * to make, and nothing for this adapter to get wrong in making one.
     */
    private fun secondAlias(): AppIconVariant {
        val other = AppIconCatalog.variants.firstOrNull { it != default }
        assumeTrue("only the default is left, so there is nothing to switch to", other != null)
        return checkNotNull(other)
    }
}
