package com.lumenpearson.lessons.core.data.locale

import com.lumenpearson.lessons.core.model.AppLanguage
import java.util.Locale

/**
 * The whole language rule, as a function of two values and nothing else.
 *
 * Everything that puts the app into its chosen language below Android 13 —
 * the activity's base context, a notification being built on a broadcast, a
 * widget being redrawn — ends up asking the same question: *given what the user
 * stored and what this context is currently resolving in, which locales should
 * this context be given?* Keeping the answer here, with no `Context` in sight,
 * is what lets it be tested on the JVM and what stops the three callers from
 * each inventing a slightly different version of it.
 *
 * @param language the stored choice.
 * @param deviceLocales the locales the context already resolves through, most
 *   preferred first, as BCP-47 tags — `Configuration.getLocales()` in order.
 * @return the locales to override with, or `null` for "leave this context
 *   alone". `null` is the answer in two quite different cases and both matter:
 *
 *   * [AppLanguage.SYSTEM] means *the absence of a per-app locale*, not
 *     "Russian". Returning `listOf("ru")` for it — which is what an enum whose
 *     `tag` is read without thinking would give, because `values/` is Russian —
 *     would turn "follow the phone" into "force Russian" for every English
 *     phone that never touched the setting.
 *   * The context already resolves in exactly that language and nothing else,
 *     so overriding it would allocate a configuration and a context to arrive
 *     where it already was. That matters here rather than being a micro-
 *     optimisation: this runs on every widget redraw and every alert broadcast.
 *
 * The override is always **one** locale, never the device list with the choice
 * prepended. The list is what resource resolution walks, so a second entry left
 * behind it would let a string that is missing from the chosen language come
 * back in the phone's language instead of in the app's own default — a single
 * Russian line in the middle of an English notification, with nothing logged.
 *
 * A device locale is compared by [AppLanguage.fromTag], so `en-GB` counts as
 * English: a phone set to British English is already showing `values-en/`, and
 * replacing `en-GB` with a bare `en` would change nothing but the allocation.
 */
fun localeOverrideFor(language: AppLanguage, deviceLocales: List<String>): List<String>? {
    if (language == AppLanguage.SYSTEM) return null
    val only = deviceLocales.singleOrNull()
    if (only != null && AppLanguage.fromTag(only) == language) return null
    return listOf(language.tag)
}

/**
 * The locale the *process* should default to, given the same answer.
 *
 * `createConfigurationContext` changes where resources are looked up and
 * nothing else. Everything that formats without being handed a context —
 * `java.time`'s month and weekday names above all, which is how the app spells
 * «8 сентября» and "8 September" — reads [Locale.getDefault], and
 * that is still the phone's language. Below Android 13 nothing sets it but us;
 * from 13 on the platform sets it itself as part of applying the per-app
 * locale, which is why the caller of this function returns before reaching it
 * on those versions.
 *
 * @param override what [localeOverrideFor] answered.
 * @param systemDefault the process default as it was before the app ever
 *   touched it. It is the answer whenever there is no override, so that
 *   choosing «Системный» after choosing English puts the months back into the
 *   phone's language instead of leaving them in the last choice.
 */
fun processLocaleFor(override: List<String>?, systemDefault: Locale): Locale =
    override?.firstOrNull()?.let(Locale::forLanguageTag) ?: systemDefault
