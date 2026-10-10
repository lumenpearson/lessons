package com.lumenpearson.lessons.appicon

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.appicon.AppIconPalette.INDIGO
import com.lumenpearson.lessons.appicon.AppIconPalette.INDIGO_LIGHT
import com.lumenpearson.lessons.appicon.AppIconPalette.LAVANDA
import com.lumenpearson.lessons.appicon.AppIconPalette.LAVANDA_LIGHT
import com.lumenpearson.lessons.appicon.AppIconPalette.MYATA
import com.lumenpearson.lessons.appicon.AppIconPalette.MYATA_LIGHT
import com.lumenpearson.lessons.appicon.AppIconPalette.RASSVET
import com.lumenpearson.lessons.appicon.AppIconPalette.RASSVET_LIGHT
import com.lumenpearson.lessons.appicon.AppIconStyle.AMOLED
import com.lumenpearson.lessons.appicon.AppIconStyle.CLASSIC

/**
 * One look of the mark, in the order «Значок приложения» shows its groups.
 *
 * [key] is the first half of every name a variant has: its resources
 * (`ic_launcher_<style>_<palette>`), its alias (`.launcher.<style>_<palette>`)
 * and the generator's (android/logo/export_android.py).
 *
 * [flatGround] says the style's ground is one flat colour: a plate for the
 * launcher's sake, which the app leaves out so the mark stands on the app's
 * own surface. A ground with a gradient, a glow or glass is part of the look
 * and is drawn. `AppIconGroundTest` holds the flag to the resources.
 */
enum class AppIconStyle(val key: String, @param:StringRes val labelRes: Int, val flatGround: Boolean) {
    CLASSIC("classic", R.string.app_icon_style_classic, flatGround = true),
    AMOLED("amoled", R.string.app_icon_style_amoled, flatGround = true),
}

/**
 * One colouring of the mark: four hues, saturated first and light after, so
 * that a group's two rows of tiles are the four hues twice.
 */
enum class AppIconPalette(val key: String, @param:StringRes val labelRes: Int) {
    RASSVET("rassvet", R.string.app_icon_palette_rassvet),
    INDIGO("indigo", R.string.app_icon_palette_indigo),
    MYATA("myata", R.string.app_icon_palette_myata),
    LAVANDA("lavanda", R.string.app_icon_palette_lavanda),
    RASSVET_LIGHT("rassvet_light", R.string.app_icon_palette_rassvet_light),
    INDIGO_LIGHT("indigo_light", R.string.app_icon_palette_indigo_light),
    MYATA_LIGHT("myata_light", R.string.app_icon_palette_myata_light),
    LAVANDA_LIGHT("lavanda_light", R.string.app_icon_palette_lavanda_light),
}

/** One launcher icon the app offers: a style in a palette, and the adaptive icon that draws it. */
data class AppIconVariant(
    val style: AppIconStyle,
    val palette: AppIconPalette,
    @param:DrawableRes val icon: Int,
) {
    val key: String get() = "${style.key}_${palette.key}"

    val resourceName: String get() = "ic_launcher_$key"

    /** The `<activity-alias>` that puts this icon on the launcher, as a class name. */
    val alias: String get() = "$AliasPackage.$key"
}

/**
 * Every icon the app offers, in the order the page shows them.
 *
 * Taking one out is three deletions: its line here, its `<activity-alias>` in
 * the manifest, and its four resources. `AppIconCatalogTest` fails until all
 * three are gone, and `LauncherAliases.reconcile` moves a phone that had chosen
 * it back to [default] on the update.
 */
object AppIconCatalog {

    val variants: List<AppIconVariant> = listOf(
        AppIconVariant(CLASSIC, RASSVET, R.mipmap.ic_launcher_classic_rassvet),
        AppIconVariant(CLASSIC, INDIGO, R.mipmap.ic_launcher_classic_indigo),
        AppIconVariant(CLASSIC, MYATA, R.mipmap.ic_launcher_classic_myata),
        AppIconVariant(CLASSIC, LAVANDA, R.mipmap.ic_launcher_classic_lavanda),
        AppIconVariant(CLASSIC, RASSVET_LIGHT, R.mipmap.ic_launcher_classic_rassvet_light),
        AppIconVariant(CLASSIC, INDIGO_LIGHT, R.mipmap.ic_launcher_classic_indigo_light),
        AppIconVariant(CLASSIC, MYATA_LIGHT, R.mipmap.ic_launcher_classic_myata_light),
        AppIconVariant(CLASSIC, LAVANDA_LIGHT, R.mipmap.ic_launcher_classic_lavanda_light),
        AppIconVariant(AMOLED, RASSVET, R.mipmap.ic_launcher_amoled_rassvet),
        AppIconVariant(AMOLED, INDIGO, R.mipmap.ic_launcher_amoled_indigo),
        AppIconVariant(AMOLED, MYATA, R.mipmap.ic_launcher_amoled_myata),
        AppIconVariant(AMOLED, LAVANDA, R.mipmap.ic_launcher_amoled_lavanda),
        AppIconVariant(AMOLED, RASSVET_LIGHT, R.mipmap.ic_launcher_amoled_rassvet_light),
        AppIconVariant(AMOLED, INDIGO_LIGHT, R.mipmap.ic_launcher_amoled_indigo_light),
        AppIconVariant(AMOLED, MYATA_LIGHT, R.mipmap.ic_launcher_amoled_myata_light),
        AppIconVariant(AMOLED, LAVANDA_LIGHT, R.mipmap.ic_launcher_amoled_lavanda_light),
    )

    /**
     * «Классика · Мята»: the dial at 90 % on white, inside every mask's safe
     * zone, so no launcher clips a lobe. The manifest enables its alias alone.
     */
    val default: AppIconVariant = variants.single { it.style == CLASSIC && it.palette == MYATA }

    /** `null` for a key this build does not have, `null` included. */
    fun byKey(key: String?): AppIconVariant? = variants.firstOrNull { it.key == key }
}

/**
 * Where the manifest's `.launcher.` aliases resolve: against the namespace,
 * not the application id, which carries `.debug` in a debug build.
 */
private const val AliasPackage = "com.lumenpearson.lessons.launcher"
