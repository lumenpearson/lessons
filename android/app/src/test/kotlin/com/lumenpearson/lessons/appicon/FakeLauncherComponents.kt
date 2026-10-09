package com.lumenpearson.lessons.appicon

/**
 * The launcher's component states, kept in a map.
 *
 * An alias nobody has set reads as the manifest declares it, as
 * PackageManager's COMPONENT_ENABLED_STATE_DEFAULT does: only the default
 * variant is enabled there. [failure], when set, is what the platform throws
 * at a write, before anything is written.
 */
internal class FakeLauncherComponents(
    explicit: Map<String, Boolean> = emptyMap(),
    private val failure: RuntimeException? = null,
) : LauncherComponents {

    val states: MutableMap<String, Boolean> = explicit.toMutableMap()

    /** Every write, one list per call, in the order it was asked for. */
    val calls: MutableList<List<AliasChange>> = mutableListOf()

    override fun isEnabled(alias: String): Boolean = states[alias] ?: (alias == AppIconCatalog.default.alias)

    override fun apply(changes: List<AliasChange>) {
        failure?.let { throw it }
        calls += changes
        changes.forEach { states[it.alias] = it.enabled }
    }

    /** The catalog's variants whose aliases are enabled now. */
    fun enabled(): List<AppIconVariant> = AppIconCatalog.variants.filter { isEnabled(it.alias) }
}
