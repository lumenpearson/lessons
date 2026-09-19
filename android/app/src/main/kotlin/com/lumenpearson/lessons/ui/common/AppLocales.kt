package com.lumenpearson.lessons.ui.common

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import com.lumenpearson.lessons.core.data.locale.AppLocale
import com.lumenpearson.lessons.core.data.locale.platformAppliesLocale
import com.lumenpearson.lessons.core.model.AppLanguage

/**
 * The two ways this app can be in a language, and the seam between them.
 *
 * Android grew a per-app locale in API 33: [LocaleManager] stores the choice
 * outside the app, the system restarts the activity with it applied, and the
 * language survives an uninstall of nothing and a reboot of everything. Below
 * 33 there is no such thing at all — the only lever is the configuration of a
 * `Context`, which means the app has to remember the choice itself and put it
 * back on every context it draws from.
 *
 * Both paths read the same stored [AppLanguage], so the preference is one
 * field in `AppSettings` and the API level decides only how it is applied. The
 * app has no appcompat dependency, and one was not added for this: `LocaleManager`
 * is a platform service and [wrap] is a delegation to three lines of framework
 * API.
 *
 * The pre-33 path wraps a `Context`, and there are three of them in this app
 * that draw text — the activity, the one a notification is built from, and the
 * one the widget is rendered from. The activity's is wrapped here, in
 * `MainActivity.attachBaseContext`; the other two are wrapped where they are
 * built, by [AppLocale] in `:core:data`, which is the one place all three can
 * see and therefore the one place the rule lives. What stays in the phone's
 * language below 33 is only what the app never gets to draw itself: the
 * launcher's own widget picker and the widget's initial "loading" frame, both
 * inflated from XML in the launcher's process with the launcher's
 * configuration.
 */
internal object AppLocales {

    /**
     * The language to attach this activity in, or `null` where there is nothing
     * to attach.
     *
     * `null` is the answer on API 33+, and it is the answer *without reading
     * the preference*. Both things the stored value is used for there are
     * no-ops — [wrap] hands the context straight back, and [applyTo]'s 33+
     * branch never looks at `attached` — but the read itself is a blocking
     * DataStore load on the cold-start path, ahead of the first frame. Below
     * 33 the read has to happen here and cannot be awaited: the base context is
     * fixed before the activity exists, so there is nothing yet to collect the
     * settings flow with.
     */
    fun languageToAttach(): AppLanguage? =
        if (platformAppliesLocale(Build.VERSION.SDK_INT)) null else AppLocale.storedLanguage()

    /**
     * [base], in [language]. A no-op on API 33+, where the platform has already
     * applied the per-app locale to every context the app is given, and on
     * [AppLanguage.SYSTEM], which *is* the unmodified base context.
     */
    fun wrap(base: Context, language: AppLanguage): Context = AppLocale.localized(base, language)

    /**
     * Bring the running activity in line with [language], if it is not already.
     *
     * Called from the composition whenever the stored value changes, which is
     * the one place that already observes the settings flow. Both branches are
     * idempotent — they compare before they act — so the effect can re-run on
     * every recomposition without the screen flickering or, worse, recreating
     * itself in a loop.
     *
     * The pre-33 branch recreates the activity, which throws away everything
     * held in `remember` and keeps everything held in `rememberSaveable` or in a
     * view model: `recreate()` saves and restores instance state and retains the
     * `ViewModelStore` exactly as a configuration change does. That is what lets
     * the first-run flow offer this setting at all — see `OnboardingScreen`.
     *
     * @param language the language that is **stored**, and never a default
     *   standing in for one that has not been read yet. The pre-33 branch
     *   decides by comparing it against [attached], which is always real, so a
     *   placeholder on this side is not a no-op — it is a language change that
     *   nobody asked for, answered by throwing the activity away. `MainActivity`
     *   takes this from `AppShellUiState.languageToApply`, which is `null` until
     *   there is an answer, for exactly that reason.
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
