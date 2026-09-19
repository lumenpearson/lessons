package com.lumenpearson.lessons.ui.translate

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.lumenpearson.lessons.core.designsystem.text.Corrections
import com.lumenpearson.lessons.core.designsystem.text.LocalCorrections

/**
 * One string the editor has been opened on: where it lives, and what it says
 * before the reader touches it.
 *
 * [original] is the string as it *ships*, not as it is drawn — for a format
 * pattern those are different, and it is the pattern that has to be exported.
 */
internal data class CorrectionTarget(val key: String, val original: String)

/**
 * The two questions correction mode asks of the resource table.
 *
 * An interface rather than the `Resources` itself, so that [AppCorrections] —
 * which is where the rules are, and where they can be got wrong — is plain
 * Kotlin that a test can drive in milliseconds. `Resources` is stubbed in a JVM
 * unit test and would have made every rule below an instrumented test instead.
 */
internal interface StringTable {

    /** `settings_title` for `R.string.settings_title`, or null for anything that cannot be exported as one `<string>`. */
    fun nameOf(@StringRes id: Int): String?

    /** The string as it ships in the language on screen — the pattern, for a format string. */
    fun textOf(@StringRes id: Int): String
}

/** The real one. */
internal fun Resources.asStringTable(): StringTable = object : StringTable {
    override fun nameOf(id: Int): String? = TranslationKeys.of(this@asStringTable, id)
    override fun textOf(id: Int): String = getString(id)
}

/**
 * Correction mode, implemented.
 *
 * The design system declares [Corrections] and defaults it to nothing; this is
 * the one thing that answers it for real, and it is installed once by
 * [CorrectionHost] around the whole app.
 *
 * It holds two pieces of state. One is the editor: which strings it is open on,
 * or none. The other is the registry — what text is on screen and which
 * resource each piece came out of — which is what turns a long press on a row
 * back into a key. See [Corrections.noteOnScreen] for why it has to be built
 * that way round rather than by searching for the text afterwards.
 */
internal class AppCorrections(
    private val strings: StringTable,
    private val locale: String,
) : Corrections {

    /** The strings the editor is open on; empty while it is closed. */
    var editing by mutableStateOf<List<CorrectionTarget>>(emptyList())

    /**
     * Text currently drawn, to the resource ids that drew it.
     *
     * A list rather than a single id because the same words really can be on
     * screen twice from two different strings, and a multiset rather than a set
     * because the same string can be drawn twice on one page — the second copy
     * leaving must not take the first one's entry with it.
     */
    private val onScreen = mutableStateMapOf<String, List<Int>>()

    /**
     * `R.string.x` to `"x"`, worked out once.
     *
     * `getResourceName` reads the resource table; the alternative is doing that
     * for every string on screen on every recomposition for as long as the mode
     * is on. An id's name cannot change while the process lives, so this is
     * a plain map and not snapshot state.
     */
    private val names = mutableMapOf<Int, String?>()

    override val enabled: Boolean get() = TranslationMode.enabled

    override fun correctionOf(id: Int, shipped: String): String {
        val key = nameOf(id) ?: return shipped
        return TranslationMode.session.correctionOf(key, locale) ?: shipped
    }

    override fun noteOnScreen(id: Int, shown: String) {
        // Strings with no exportable name — a plural, an id from another
        // package — are left out here rather than filtered on the way back, so
        // that a long press on one finds nothing and does nothing, instead of
        // opening an editor over a key that cannot be written into `values/`.
        if (nameOf(id) == null) return
        onScreen[shown] = onScreen[shown].orEmpty() + id
    }

    override fun forget(id: Int, shown: String) {
        val remaining = onScreen[shown]?.toMutableList() ?: return
        remaining.remove(id)
        if (remaining.isEmpty()) onScreen.remove(shown) else onScreen[shown] = remaining
    }

    override fun keysBehind(shown: String): List<Int> = onScreen[shown].orEmpty().distinct()

    /**
     * Opens the editor on every string that says exactly this.
     *
     * Usually that is one. When it is more — 85 of this app's texts are shared
     * by two or three keys — all of them are corrected together, and the sheet
     * names all of them. That is the honest answer rather than a convenience:
     * the reader is looking at a phrase and saying it is wrong, and a phrase
     * that is wrong in «Назад» is wrong in the other two places it is written.
     * Guessing one of the three would silently export a fix for a line the
     * reader never saw.
     *
     * Ids whose shipped text differs from the first one's are dropped. They can
     * only be here because one of them has already been corrected into the
     * other's words, and one field cannot honestly stand for two originals.
     */
    override fun edit(@StringRes ids: List<Int>) {
        val targets = ids.mapNotNull { id ->
            nameOf(id)?.let { CorrectionTarget(key = it, original = strings.textOf(id)) }
        }
        val original = targets.firstOrNull()?.original ?: return
        editing = targets.filter { it.original == original }.distinctBy { it.key }
    }

    private fun nameOf(@StringRes id: Int): String? = names.getOrPut(id) { strings.nameOf(id) }
}

/**
 * Installs correction mode over the app, and hosts the one editor.
 *
 * One sheet for the whole app rather than one per piece of text: the editor is
 * opened from a modifier, and a modifier has nowhere to put a bottom sheet.
 * Hoisting it here also means there is exactly one of it on screen, which is
 * the behaviour anybody would expect and not what a sheet per label would give.
 *
 * Rebuilt when the language changes, because a correction belongs to the
 * `values-` folder the words on screen came out of, and because everything in
 * the registry is about to be re-composed in another language anyway.
 */
@Composable
internal fun CorrectionHost(content: @Composable () -> Unit) {
    val resources = LocalResources.current
    val locale = currentLocale()
    val corrections = remember(resources, locale) {
        AppCorrections(resources.asStringTable(), locale)
    }

    CompositionLocalProvider(LocalCorrections provides corrections, content = content)

    val editing = corrections.editing
    if (editing.isNotEmpty()) {
        TranslationEditorSheet(
            targets = editing,
            locale = locale,
            onDismiss = { corrections.editing = emptyList() },
        )
    }
}

/**
 * The language the app is currently drawn in, which is the folder a correction
 * belongs to.
 *
 * Read from the configuration rather than from the stored preference: on
 * "system" the preference names no language at all, and it is the resolved one
 * that decided which `values-` folder the string on screen came out of.
 */
@Composable
internal fun currentLocale(): String {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        configuration.locales.takeIf { !it.isEmpty }?.get(0)?.language ?: FallbackLocale
    }
}

/** The app's default locale, for the impossible case of an empty locale list. */
private const val FallbackLocale = "ru"
