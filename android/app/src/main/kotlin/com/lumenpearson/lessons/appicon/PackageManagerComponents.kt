package com.lumenpearson.lessons.appicon

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ComponentEnabledSetting
import android.os.Build

/**
 * [LauncherComponents] over PackageManager.
 *
 * Every write carries DONT_KILL_APP: the switch is made from inside the app,
 * and without it the system ends the process that asked. From Android 13 the
 * whole list goes in one `setComponentEnabledSettings` call, which is one
 * launcher re-index instead of one per alias; below it, one call each, in the
 * order [LauncherAliases] gave them.
 *
 * @param defaultAlias the one alias the manifest enables, which is what a
 *   component never set reads as. `AppIconCatalogTest` holds the manifest to it.
 * @param sdkInt [Build.VERSION.SDK_INT], except in a test of the older path.
 */
class PackageManagerComponents(
    context: Context,
    private val defaultAlias: String = AppIconCatalog.default.alias,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : LauncherComponents {

    private val packageManager: PackageManager = context.packageManager
    private val packageName: String = context.packageName

    override fun isEnabled(alias: String): Boolean =
        when (packageManager.getComponentEnabledSetting(component(alias))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias == defaultAlias
            else -> false
        }

    // The version check is on sdkInt, which lint cannot follow back to SDK_INT.
    @SuppressLint("NewApi")
    override fun apply(changes: List<AliasChange>) {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(
                changes.map { ComponentEnabledSetting(component(it.alias), stateOf(it), PackageManager.DONT_KILL_APP) },
            )
        } else {
            changes.forEach {
                packageManager.setComponentEnabledSetting(
                    component(it.alias),
                    stateOf(it),
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    private fun component(alias: String) = ComponentName(packageName, alias)

    private fun stateOf(change: AliasChange): Int =
        if (change.enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
}
