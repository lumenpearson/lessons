package com.lumenpearson.lessons.appicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Which components a switch and a reconcile change, and in what order.
 *
 * Over [TestCatalog] rather than the real catalog, so that this file says the
 * same whatever the owner keeps of the catalog, the default alone included:
 * the decisions need two icons besides the default, and a trimmed catalog may
 * not have them.
 */
class LauncherAliasesTest {

    private val default = TestCatalog.default
    private val first = TestCatalog.other
    private val second = TestCatalog.another

    @Test
    fun `a fresh install shows the default and nothing is written`() {
        val components = TestCatalog.launcher()
        val aliases = TestCatalog.aliases(components)

        assertEquals(default, aliases.current())
        assertEquals(default, aliases.reconcile())
        assertEquals(emptyList<List<AliasChange>>(), components.calls)
    }

    @Test
    fun `a switch enables the new icon before it disables the old one, in one write`() {
        val components = TestCatalog.launcher()

        TestCatalog.aliases(components).switchTo(first)

        // One call, so that from Android 13 it is one batch; the new one first, so
        // that below 13, where it is two calls, there is never a moment with no
        // launcher entry at all.
        assertEquals(
            listOf(listOf(AliasChange(first.alias, enabled = true), AliasChange(default.alias, enabled = false))),
            components.calls,
        )
        assertEquals(listOf(first), components.enabled())
    }

    @Test
    fun `switching to the icon in use writes nothing`() {
        val components = TestCatalog.launcher(mapOf(first.alias to true, default.alias to false))

        TestCatalog.aliases(components).switchTo(first)

        assertEquals(emptyList<List<AliasChange>>(), components.calls)
    }

    @Test
    fun `only the components whose state changes are written`() {
        val components = TestCatalog.launcher(mapOf(first.alias to true, default.alias to false))

        TestCatalog.aliases(components).switchTo(second)

        // The default is already off and is not written again: every write is a
        // launcher re-index, and Essentials rewrites all of them every time.
        assertEquals(
            listOf(listOf(AliasChange(second.alias, enabled = true), AliasChange(first.alias, enabled = false))),
            components.calls,
        )
    }

    @Test
    fun `switching back to the default leaves the default alone on`() {
        val components = TestCatalog.launcher(mapOf(first.alias to true, default.alias to false))

        TestCatalog.aliases(components).switchTo(default)

        assertEquals(listOf(default), components.enabled())
    }

    @Test
    fun `reconcile with nothing enabled turns the default on`() {
        val components = TestCatalog.launcher(mapOf(default.alias to false))

        assertEquals(default, TestCatalog.aliases(components).reconcile())
        assertEquals(listOf(listOf(AliasChange(default.alias, enabled = true))), components.calls)
    }

    @Test
    fun `an update that removed the chosen alias lands on the default`() {
        // The phone had chosen an alias this build no longer declares. Its state
        // is still on record, the default is off, and nothing in the catalog is on.
        val components = TestCatalog.launcher(
            mapOf("com.lumenpearson.lessons.launcher.removed_variant" to true, default.alias to false),
        )
        val aliases = TestCatalog.aliases(components)

        assertEquals(default, aliases.current())
        assertEquals(default, aliases.reconcile())
        assertEquals(listOf(default), components.enabled())
    }

    @Test
    fun `reconcile after a switch that died halfway keeps the new icon`() {
        // Below Android 13 the new alias is enabled first; a process killed before
        // the second call leaves it and the default both on.
        val components = TestCatalog.launcher(mapOf(first.alias to true))
        val aliases = TestCatalog.aliases(components)

        assertEquals(first, aliases.current())
        assertEquals(first, aliases.reconcile())
        assertEquals(listOf(listOf(AliasChange(default.alias, enabled = false))), components.calls)
    }

    @Test
    fun `reconcile with two chosen icons keeps the first in catalog order`() {
        val components = TestCatalog.launcher(
            mapOf(second.alias to true, first.alias to true, default.alias to false),
        )

        assertEquals(first, TestCatalog.aliases(components).reconcile())
        assertEquals(listOf(first), components.enabled())
    }

    @Test
    fun `a variant the catalog does not have is refused`() {
        val stranger = default.copy(icon = 0)

        assertThrows(IllegalArgumentException::class.java) {
            TestCatalog.aliases(TestCatalog.launcher()).switchTo(stranger)
        }
    }
}
