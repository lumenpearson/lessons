package com.lumenpearson.lessons.core.data.locale

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.model.AppLanguage
import java.util.Locale

/**
 * A `Context` that resolves resources in the app's chosen language.
 *
 * This lives in `:core:data` rather than beside `AppLocales` in `:app` for the
 * plain reason that `:core:data` and `:widget` cannot see `:app` — the
 * dependency runs the other way — and both of them draw text: a notification is
 * built on a broadcast with no activity alive, and the widget is rendered from
 * the application context, often in the launcher's own wake-up of the process.
 * `AppLocales` in `:app` is still the place the two-mechanism seam is
 * *described*; the mechanism itself is here, called from all three.
 *
 * On API 33+ every function here hands the context straight back. The platform
 * has already applied the per-app locale to the whole process — activity,
 * application context and the process's default `Locale` alike — so there is
 * nothing left to wrap and wrapping would only cost an allocation.
 */
object AppLocale {

    /**
     * The stored choice, read synchronously.
     *
     * Reads the graph, never initialises it: every caller runs after
     * `Application.onCreate`, and initialising it from here would be
     * initialising it without the arguments the application passes — silently,
     * because `Graph.init` is a no-op the second time. If the container is
     * somehow absent, text comes out in the phone's language rather than not
     * coming out at all.
     *
     * One DataStore read, and the file is a few hundred bytes that DataStore
     * serves from memory after the first time. That is why the rule is one call
     * *per build* — per notification, per widget render — and never one per
     * string: [localized] is meant to be called once and the context it returns
     * passed down.
     */
    fun storedLanguage(): AppLanguage =
        runCatching { Graph.container.settingsRepository.languageBlocking() }
            .getOrDefault(AppLanguage.SYSTEM)

    /**
     * [base] in the stored language.
     *
     * The version check comes *before* [storedLanguage] rather than inside
     * [localized]: on API 33+ the answer is discarded, and this is called once
     * per widget redraw and once per alert broadcast, so a discarded answer is
     * a DataStore read per redraw for nothing.
     *
     * @see localized
     */
    fun localized(base: Context): Context =
        if (platformAppliesLocale(Build.VERSION.SDK_INT)) base else localized(base, storedLanguage())

    /**
     * [base], resolving resources in [language].
     *
     * Returns [base] itself whenever [localeOverrideFor] says there is nothing
     * to override — on API 33+, on [AppLanguage.SYSTEM], and when the context
     * already resolves in exactly that language — so callers can wrap without
     * checking first, and a context that has already been wrapped can be handed
     * on to something that wraps again without a second configuration being
     * built.
     */
    // Lint reads a configuration locale change as a sign of an app bundle with
    // per-language splits, which needs Play Core to fetch the language first.
    // This app is a single APK off a GitHub release — every locale it filters
    // for is already inside it — so there is nothing to download and no split
    // to guard against.
    @SuppressLint("AppBundleLocaleChanges")
    fun localized(base: Context, language: AppLanguage): Context {
        if (platformAppliesLocale(Build.VERSION.SDK_INT)) return base
        val current = base.resources.configuration
        val tags = localeOverrideFor(language, current.localeTags())
        // Done on the way past, override or not: the process default is wrong
        // in both directions — left in English after a switch back to
        // «Системный» just as surely as left in Russian after a switch to
        // English — and this is the one function every caller goes through.
        Locale.setDefault(processLocaleFor(tags, systemDefault()))
        if (tags == null) return base
        val configuration = Configuration(current)
        // setLocales rather than setLocale: see the note on the return value of
        // localeOverrideFor about why the list is exactly one entry long.
        configuration.setLocales(LocaleList(*tags.map(Locale::forLanguageTag).toTypedArray()))
        return base.createConfigurationContext(configuration)
    }

    /**
     * The phone's own language, right now.
     *
     * What makes a return to [AppLanguage.SYSTEM] a return to the *phone's*
     * language rather than to whatever was chosen last. It has to be read on
     * every call rather than cached: this used to be a `val` holding
     * `Locale.getDefault()` from the first time the object was touched, and
     * `Locale.setDefault` below then overwrote the very thing it had recorded —
     * correct within one process, wrong the moment the phone's language changed
     * while the app was alive. Set the app to English, change the phone from
     * Russian to Kazakh, ask for «Системный» again, and the cached answer was
     * Russian.
     *
     * `Resources.getSystem()` is the source because it is the one set of
     * resources the app's own overrides never reach — `createConfigurationContext`
     * and `Locale.setDefault` both leave it alone, so it still reports what the
     * device is set to. Empty only on a configuration with no locales at all,
     * which the process default is the sane fallback for.
     */
    private fun systemDefault(): Locale =
        Resources.getSystem().configuration.locales.takeIf { it.size() > 0 }?.get(0)
            ?: Locale.getDefault()

    /** The locales this configuration resolves through, most preferred first. */
    private fun Configuration.localeTags(): List<String> =
        (0 until locales.size()).map { locales[it].toLanguageTag() }
}
