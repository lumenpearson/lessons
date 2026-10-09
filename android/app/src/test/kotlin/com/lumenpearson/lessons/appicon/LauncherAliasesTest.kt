package com.lumenpearson.lessons.appicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Which components a switch and a reconcile change, and in what order.
 *
 * The variants are picked by position rather than by name, so that this file
 * survives the owner taking most of the sixty-four out.
 */
class LauncherAliasesTest {

    private val default = AppIconCatalog.default
    private val others = AppIconCatalog.variants - default
    private val first = others[0]
    private val second = others[1]

    @Test
    fun `a fresh install shows the default and nothing is written`() {
        val components = FakeLauncherComponents()
        val aliases = LauncherAliases(components)

        assertEquals(default, aliases.current())
        assertEquals(default, aliases.reconcile())
        assertEquals(emptyList<List<AliasChange>>(), components.calls)
    }

    @Test
    fun `a switch enables the new icon before it disables the old one, in one write`() {
        val components = FakeLauncherComponents()

        LauncherAliases(components).switchTo(first)

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
        val components = FakeLauncherComponents(mapOf(first.alias to true, default.alias to false))

        LauncherAliases(components).switchTo(first)

        assertEquals(emptyList<List<AliasChange>>(), components.calls)
    }

    @Test
    fun `only the components whose state changes are written`() {
        val components = FakeLauncherComponents(mapOf(first.alias to true, default.alias to false))

        LauncherAliases(components).switchTo(second)

        // The default is already off and is not written again: every write is a
        // launcher re-index, and Essentials rewrites all of them every time.
        assertEquals(
            listOf(listOf(AliasChange(second.alias, enabled = true), AliasChange(first.alias, enabled = false))),
            components.calls,
        )
    }

    @Test
    fun `switching back to the default leaves the default alone on`() {
        val components = FakeLauncherComponents(mapOf(first.alias to true, default.alias to false))

        LauncherAliases(components).switchTo(default)

        assertEquals(listOf(default), components.enabled())
    }

    @Test
    fun `reconcile with nothing enabled turns the default on`() {
        val components = FakeLauncherComponents(mapOf(default.alias to false))

        assertEquals(default, LauncherAliases(components).reconcile())
        assertEquals(listOf(listOf(AliasChange(default.alias, enabled = true))), components.calls)
    }

    @Test
    fun `an update that removed the chosen alias lands on the default`() {
        // The phone had chosen an alias this build no longer declares. Its state
        // is still on record, the default is off, and nothing in the catalog is on.
        val components = FakeLauncherComponents(
            mapOf("com.lumenpearson.lessons.launcher.removed_variant" to true, default.alias to false),
        )
        val aliases = LauncherAliases(components)

        assertEquals(default, aliases.current())
        assertEquals(default, aliases.reconcile())
        assertEquals(listOf(default), components.enabled())
    }

    @Test
    fun `reconcile after a switch that died halfway keeps the new icon`() {
        // Below Android 13 the new alias is enabled first; a process killed before
        // the second call leaves it and the default both on.
        val components = FakeLauncherComponents(mapOf(first.alias to true))
        val aliases = LauncherAliases(components)

        assertEquals(first, aliases.current())
        assertEquals(first, aliases.reconcile())
        assertEquals(listOf(listOf(AliasChange(default.alias, enabled = false))), components.calls)
    }

    @Test
    fun `reconcile with two chosen icons keeps the first in catalog order`() {
        val components = FakeLauncherComponents(
            mapOf(second.alias to true, first.alias to true, default.alias to false),
        )

        assertEquals(first, LauncherAliases(components).reconcile())
        assertEquals(listOf(first), components.enabled())
    }

    @Test
    fun `a variant the catalog does not have is refused`() {
        val stranger = default.copy(icon = 0)

        assertThrows(IllegalArgumentException::class.java) {
            LauncherAliases(FakeLauncherComponents()).switchTo(stranger)
        }
    }
}
