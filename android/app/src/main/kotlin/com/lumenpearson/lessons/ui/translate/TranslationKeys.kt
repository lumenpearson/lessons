package com.lumenpearson.lessons.ui.translate

import android.content.res.Resources

/**
 * Going from the integer a screen passes around to the name a translator reads.
 *
 * `R.string.settings_title` is a number, and a number is no use to anyone
 * merging a correction. The platform can give back the name behind it, but as a
 * fully qualified one — `com.lumenpearson.lessons:string/settings_title` — and
 * the parsing of that is kept apart from the lookup so it can be tested
 * without a device, which is where the interesting cases are: an id from
 * another package, an id that is not a string at all, and an id of zero.
 */
internal object TranslationKeys {

    /**
     * The entry name behind [id], or `null` when it does not name a string this
     * app could export.
     *
     * Nothing about a correction is worth crashing a screen for, which is why
     * the lookup is wrapped: an id that no longer resolves throws, and it can
     * happen for a resource that was removed between a saved state and the code
     * reading it back.
     */
    fun of(resources: Resources, id: Int): String? {
        if (id == 0) return null
        val qualified = runCatching { resources.getResourceName(id) }.getOrNull()
        return entryNameOf(qualified)
    }

    /**
     * `package:type/entry` reduced to `entry`, and only for a string.
     *
     * A plural or an array cannot be written back as a single `<string>`
     * element, so an id naming one is refused here rather than exported into a
     * fragment that would not compile.
     */
    fun entryNameOf(qualifiedName: String?): String? {
        if (qualifiedName == null) return null
        val separator = qualifiedName.indexOf('/')
        if (separator <= 0) return null
        val type = qualifiedName.substring(0, separator).substringAfterLast(':')
        if (type != "string") return null
        return qualifiedName.substring(separator + 1).takeIf { it.isNotBlank() }
    }
}
