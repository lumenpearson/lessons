package com.lumenpearson.lessons.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.lumenpearson.lessons.R

/**
 * The permissions this app has to ask for, and what stops working without each.
 *
 * A port of Essentials' permissions feature, cut down to the three things this
 * app actually needs. The reference carries twenty-two — call logs, contacts,
 * wallpapers, accessibility — because it is a system toolbox; a school diary
 * needs to be able to buzz, to buzz *on time*, and to still be alive at the
 * moment it is supposed to. All three are revocable after install, which is the
 * case this screen exists for: nothing here is asked for at first run, and a
 * permission taken away in system settings six weeks later otherwise shows up
 * only as notifications quietly not arriving.
 *
 * Granted-ness is asked of the system every time rather than cached, because
 * the whole point is that it changes outside the app.
 */
enum class AppPermission(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
) {

    /**
     * Post notifications at all.
     *
     * Two questions, not one: from Android 13 there is a runtime permission,
     * and on every version the user can switch the app's notifications off in
     * system settings. Either answer being no means nothing is delivered, so
     * both are asked.
     */
    NOTIFICATIONS(
        titleRes = R.string.permission_notifications,
        descriptionRes = R.string.permission_notifications_description,
        icon = Icons.Rounded.NotificationsActive,
    ) {
        override fun isGranted(context: Context): Boolean {
            val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        override fun intents(context: Context): List<Intent> = listOf(
            // The package goes in an extra here, not in the data URI. Getting
            // that the wrong way round is the single most common way this lands
            // on the wrong settings page.
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    },

    /**
     * Alarms that fire at the minute they were set for.
     *
     * Revocable from Android 12. Without it the alert chain still runs, but
     * inexactly — and "через 10 минут урок" delivered at some point in the next
     * half hour is worse than nothing, because it is wrong rather than absent.
     */
    EXACT_ALARMS(
        titleRes = R.string.permission_exact_alarms,
        descriptionRes = R.string.permission_exact_alarms_description,
        icon = Icons.Rounded.Alarm,
    ) {
        override fun isRelevant(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        override fun isGranted(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService<AlarmManager>()?.canScheduleExactAlarms() != false

        override fun intents(context: Context): List<Intent> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData("package:${context.packageName}".toUri()),
                )
            } else {
                emptyList()
            }
    },

    /**
     * Exemption from battery optimisation.
     *
     * The alert chain is one alarm at a time, each arming the next; Doze can
     * hold the whole chain until the phone is picked up, by which time the
     * lesson it was about has started. The one genuinely optional entry here —
     * the app works without it, it is simply less punctual.
     */
    BACKGROUND(
        titleRes = R.string.permission_background,
        descriptionRes = R.string.permission_background_description,
        icon = Icons.Rounded.Battery5Bar,
    ) {
        override fun isGranted(context: Context): Boolean =
            context.getSystemService<PowerManager>()
                ?.isIgnoringBatteryOptimizations(context.packageName) != false

        // The system list rather than the direct
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog: that one throws
        // unless REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is declared, and that
        // permission is one Google Play polices hard. A school diary is not
        // worth arguing with a review team over, so the user takes one extra
        // tap in a list instead.
        override fun intents(context: Context): List<Intent> =
            listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    },
    ;

    /** Whether this build's Android version has the thing at all. */
    open fun isRelevant(): Boolean = true

    /** Asked of the system, never cached: it changes while the app is not looking. */
    abstract fun isGranted(context: Context): Boolean

    /** Where to send the user, best first. */
    protected abstract fun intents(context: Context): List<Intent>

    /**
     * Opens the best system page that will actually open.
     *
     * The chain matters more than any single entry. OEM builds drop whole
     * settings screens, work profiles hide others, and the reference's habit of
     * retrying the same action without its data URI is not a fallback at all —
     * if the first throws because nothing handles the action, so does the
     * second, uncaught. Every candidate is tried in turn and the app's own
     * details page ends the list, because that one exists everywhere.
     */
    fun open(context: Context) {
        val candidates = intents(context) + listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${context.packageName}".toUri()),
            Intent(Settings.ACTION_SETTINGS),
        )

        candidates.forEach { intent ->
            val opened = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }
    }

    companion object {

        /** The ones worth showing on this device, in the order they are asked. */
        fun relevant(): List<AppPermission> = entries.filter { it.isRelevant() }

        /** How many of them are missing right now. */
        fun missingCount(context: Context): Int = relevant().count { !it.isGranted(context) }
    }
}
