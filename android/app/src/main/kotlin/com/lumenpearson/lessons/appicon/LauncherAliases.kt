package com.lumenpearson.lessons.appicon

/**
 * What the switch needs of the platform: one question and one write.
 *
 * A port rather than PackageManager itself, so that every decision below is
 * tested on the JVM, and the platform adapter is left with nothing to decide.
 */
interface LauncherComponents {

    /** Whether [alias] is enabled now, with the manifest's own `android:enabled` for one never set. */
    fun isEnabled(alias: String): Boolean

    /** Sets every change, as one call where the platform allows it and in the order given where not. */
    fun apply(changes: List<AliasChange>)
}

/** One alias turned on or off. */
data class AliasChange(val alias: String, val enabled: Boolean)

/**
 * Which launcher alias is on, and how to make exactly one of them be.
 *
 * The component states are the record of the reader's choice, not a
 * preference beside them (docs/specs/2026-10-09-app-icons-design.md, «How the
 * icon changes»). Auto Backup restores a preference onto a new phone and does
 * not restore component states, so a stored choice is a page that names an
 * icon the home screen does not show.
 */
class LauncherAliases(
    private val components: LauncherComponents,
    private val variants: List<AppIconVariant> = AppIconCatalog.variants,
    private val default: AppIconVariant = AppIconCatalog.default,
) {

    /** The icon the launcher shows, which is the one [reconcile] would keep. */
    fun current(): AppIconVariant = keeper(enabled())

    /** Makes [target] the one launcher entry, writing only what changes. */
    fun switchTo(target: AppIconVariant) {
        require(target in variants) { "${target.key} is not in the catalog" }
        settle(target, enabled())
    }

    /**
     * Brings the launcher back to exactly one entry, after an update or a
     * process start, and says which.
     *
     * None enabled is an update that took the chosen alias away, and it gets
     * the default. Several is a switch that died between its two calls below
     * Android 13. It keeps the first in catalog order that is not the default,
     * which for a switch away from the default is the new icon. For a switch
     * back to the default, or between two other icons when the one being left
     * comes first in the catalog, it keeps the icon being left, so the
     * reader's last choice is undone; the launcher still ends with exactly one
     * entry, and the window for it is the milliseconds between two calls.
     */
    fun reconcile(): AppIconVariant {
        val enabled = enabled()
        val keep = keeper(enabled)
        settle(keep, enabled)
        return keep
    }

    private fun enabled(): List<AppIconVariant> = variants.filter { components.isEnabled(it.alias) }

    private fun keeper(enabled: List<AppIconVariant>): AppIconVariant =
        enabled.firstOrNull { it != default } ?: default

    /** [keep] on first and the rest off after, so that no moment has no entry; one write, or none. */
    private fun settle(keep: AppIconVariant, enabled: List<AppIconVariant>) {
        val changes = buildList {
            if (keep !in enabled) add(AliasChange(keep.alias, enabled = true))
            enabled.filter { it != keep }.forEach { add(AliasChange(it.alias, enabled = false)) }
        }
        if (changes.isNotEmpty()) components.apply(changes)
    }
}
