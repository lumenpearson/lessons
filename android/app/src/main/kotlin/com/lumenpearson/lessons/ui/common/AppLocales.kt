package com.lumenpearson.lessons.ui.common

import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.model.AppLanguage
import java.util.Locale

/**
 * The two ways this app can be in a language, and the seam between them.
 *
 * Android grew a per-app locale in API 33: [LocaleManager] stores the choice
 * outside the app, the system restarts the activity with it applied, and the
 * language survives an uninstall of nothing and a reboot of everything. Below
 * 33 there is no such thing at all — the only lever is the configuration of a
 * `Context`, which means the app has to remember the choice itself and put it
 * back on every activity that is created.
 *
 * Both paths read the same stored [AppLanguage], so the preference is one
 * field in `AppSettings` and the API level decides only how it is applied. The
 * app has no appcompat dependency, and one was not added for this: `LocaleManager`
 * is a platform service and [wrap] is three lines of framework API.
 *
 * The pre-33 path wraps the *activity's* context, which is what draws every
 * screen. It does not reach the widget or the notifications: those are built
 * from the application context in another process or at another time, and they
 * stay in the phone's own language until the phone is on 33, where the platform
 * applies the per-app locale to all of it. That is a real gap, and a smaller one
 * than shipping a picker that only half the screens obey.
 */
internal object AppLocales {

    /**
     * The stored choice, read synchronously because `attachBaseContext` cannot
     * wait for anything.
     *
     * Reads the graph, never initialises it: an activity is always created
     * after `Application.onCreate`, and initialising it from here would be
     * initialising it without the GitHub client id the application passes —
     * silently, because `Graph.init` is a no-op the second time. If the
     * container is somehow absent, the app opens in the phone's language
     * rather than not opening.
     */
    fun storedLanguage(): AppLanguage =
        runCatching { Graph.container.settingsRepository.languageBlocking() }
            .getOrDefault(AppLanguage.SYSTEM)

    /**
     * [base], in [language]. A no-op on API 33+, where the platform has already
     * applied the per-app locale to every context the app is given, and on
     * [AppLanguage.SYSTEM], which *is* the unmodified base context.
     */
    // Lint reads a configuration locale change as a sign of an app bundle with
    // per-language splits, which needs Play Core to fetch the language first.
    // This app is a single APK off a GitHub release — every locale it filters
    // for is already inside it — so there is nothing to download and no split
    // to guard against.
    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context, language: AppLanguage): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        if (language == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(language.tag)
        val configuration = Configuration(base.resources.configuration)
        // setLocales rather than setLocale: the list is what resource resolution
        // walks, so leaving a stale second entry in it would let a string the
        // chosen language is missing come back in the phone's language instead
        // of in the app's default one.
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    /**
     * Bring the running activity in line with [language], if it is not already.
     *
     * Called from the composition whenever the stored value changes, which is
     * the one place that already observes the settings flow. Both branches are
     * idempotent — they compare before they act — so the effect can re-run on
     * every recomposition without the screen flickering or, worse, recreating
     * itself in a loop.
     *
     * @param attached what [wrap] was given when this activity was attached.
     *   Only the pre-33 branch needs it: it is the difference between "the
     *   preference changed" and "the preference is what this activity is
     *   already drawn in".
     */
    fun applyTo(activity: Activity, language: AppLanguage, attached: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = activity.getSystemService(LocaleManager::class.java) ?: return
            val desired = if (language == AppLanguage.SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(language.tag)
            }
            // The system restarts the activity itself once this is set, so there
            // is nothing to recreate here — and setting it to what it already is
            // would restart the activity for nothing.
            if (manager.applicationLocales.toLanguageTags() != desired.toLanguageTags()) {
                manager.applicationLocales = desired
            }
        } else if (language != attached) {
            // Nothing else re-runs `attachBaseContext`, and that is the only
            // place the base context can still be replaced.
            activity.recreate()
        }
    }
}
