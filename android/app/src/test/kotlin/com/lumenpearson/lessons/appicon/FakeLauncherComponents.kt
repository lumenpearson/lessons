package com.lumenpearson.lessons.appicon

/**
 * The launcher's component states, kept in a map.
 *
 * An alias nobody has set reads as the manifest declares it, as
 * PackageManager's COMPONENT_ENABLED_STATE_DEFAULT does: only [default] is
 * enabled there. [failure], when set, is what the platform throws at a write,
 * before anything is written.
 *
 * @param variants the catalog [enabled] reads, which is the one the code under
 *   test was handed: `TestCatalog.launcher()` passes the test catalog, and only
 *   a test of the real page leaves the real one.
 */
internal class FakeLauncherComponents(
    explicit: Map<String, Boolean> = emptyMap(),
    private val failure: RuntimeException? = null,
    private val variants: List<AppIconVariant> = AppIconCatalog.variants,
    private val default: AppIconVariant = AppIconCatalog.default,
) : LauncherComponents {

    val states: MutableMap<String, Boolean> = explicit.toMutableMap()

    /** Every write, one list per call, in the order it was asked for. */
    val calls: MutableList<List<AliasChange>> = mutableListOf()

    override fun isEnabled(alias: String): Boolean = states[alias] ?: (alias == default.alias)

    override fun apply(changes: List<AliasChange>) {
        failure?.let { throw it }
        calls += changes
        changes.forEach { states[it.alias] = it.enabled }
    }

    /** The catalog's variants whose aliases are enabled now. */
    fun enabled(): List<AppIconVariant> = variants.filter { isEnabled(it.alias) }
}
