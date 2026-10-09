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
import com.lumenpearson.lessons.appicon.AppIconStyle.DARK
import com.lumenpearson.lessons.appicon.AppIconStyle.EDGE
import com.lumenpearson.lessons.appicon.AppIconStyle.GLASS
import com.lumenpearson.lessons.appicon.AppIconStyle.GLOW
import com.lumenpearson.lessons.appicon.AppIconStyle.MATTE
import com.lumenpearson.lessons.appicon.AppIconStyle.ONEUI

/**
 * One look of the mark, in the order «Значок приложения» shows its groups.
 *
 * [key] is the first half of every name a variant has: its resources
 * (`ic_launcher_<style>_<palette>`), its alias (`.launcher.<style>_<palette>`)
 * and the generator's (android/logo/export_android.py).
 */
enum class AppIconStyle(val key: String, @param:StringRes val labelRes: Int) {
    CLASSIC("classic", R.string.app_icon_style_classic),
    EDGE("edge", R.string.app_icon_style_edge),
    GLOW("glow", R.string.app_icon_style_glow),
    DARK("dark", R.string.app_icon_style_dark),
    GLASS("glass", R.string.app_icon_style_glass),
    MATTE("matte", R.string.app_icon_style_matte),
    ONEUI("oneui", R.string.app_icon_style_oneui),
    AMOLED("amoled", R.string.app_icon_style_amoled),
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
        AppIconVariant(EDGE, RASSVET, R.mipmap.ic_launcher_edge_rassvet),
        AppIconVariant(EDGE, INDIGO, R.mipmap.ic_launcher_edge_indigo),
        AppIconVariant(EDGE, MYATA, R.mipmap.ic_launcher_edge_myata),
        AppIconVariant(EDGE, LAVANDA, R.mipmap.ic_launcher_edge_lavanda),
        AppIconVariant(EDGE, RASSVET_LIGHT, R.mipmap.ic_launcher_edge_rassvet_light),
        AppIconVariant(EDGE, INDIGO_LIGHT, R.mipmap.ic_launcher_edge_indigo_light),
        AppIconVariant(EDGE, MYATA_LIGHT, R.mipmap.ic_launcher_edge_myata_light),
        AppIconVariant(EDGE, LAVANDA_LIGHT, R.mipmap.ic_launcher_edge_lavanda_light),
        AppIconVariant(GLOW, RASSVET, R.mipmap.ic_launcher_glow_rassvet),
        AppIconVariant(GLOW, INDIGO, R.mipmap.ic_launcher_glow_indigo),
        AppIconVariant(GLOW, MYATA, R.mipmap.ic_launcher_glow_myata),
        AppIconVariant(GLOW, LAVANDA, R.mipmap.ic_launcher_glow_lavanda),
        AppIconVariant(GLOW, RASSVET_LIGHT, R.mipmap.ic_launcher_glow_rassvet_light),
        AppIconVariant(GLOW, INDIGO_LIGHT, R.mipmap.ic_launcher_glow_indigo_light),
        AppIconVariant(GLOW, MYATA_LIGHT, R.mipmap.ic_launcher_glow_myata_light),
        AppIconVariant(GLOW, LAVANDA_LIGHT, R.mipmap.ic_launcher_glow_lavanda_light),
        AppIconVariant(DARK, RASSVET, R.mipmap.ic_launcher_dark_rassvet),
        AppIconVariant(DARK, INDIGO, R.mipmap.ic_launcher_dark_indigo),
        AppIconVariant(DARK, MYATA, R.mipmap.ic_launcher_dark_myata),
        AppIconVariant(DARK, LAVANDA, R.mipmap.ic_launcher_dark_lavanda),
        AppIconVariant(DARK, RASSVET_LIGHT, R.mipmap.ic_launcher_dark_rassvet_light),
        AppIconVariant(DARK, INDIGO_LIGHT, R.mipmap.ic_launcher_dark_indigo_light),
        AppIconVariant(DARK, MYATA_LIGHT, R.mipmap.ic_launcher_dark_myata_light),
        AppIconVariant(DARK, LAVANDA_LIGHT, R.mipmap.ic_launcher_dark_lavanda_light),
        AppIconVariant(GLASS, RASSVET, R.mipmap.ic_launcher_glass_rassvet),
        AppIconVariant(GLASS, INDIGO, R.mipmap.ic_launcher_glass_indigo),
        AppIconVariant(GLASS, MYATA, R.mipmap.ic_launcher_glass_myata),
        AppIconVariant(GLASS, LAVANDA, R.mipmap.ic_launcher_glass_lavanda),
        AppIconVariant(GLASS, RASSVET_LIGHT, R.mipmap.ic_launcher_glass_rassvet_light),
        AppIconVariant(GLASS, INDIGO_LIGHT, R.mipmap.ic_launcher_glass_indigo_light),
        AppIconVariant(GLASS, MYATA_LIGHT, R.mipmap.ic_launcher_glass_myata_light),
        AppIconVariant(GLASS, LAVANDA_LIGHT, R.mipmap.ic_launcher_glass_lavanda_light),
        AppIconVariant(MATTE, RASSVET, R.mipmap.ic_launcher_matte_rassvet),
        AppIconVariant(MATTE, INDIGO, R.mipmap.ic_launcher_matte_indigo),
        AppIconVariant(MATTE, MYATA, R.mipmap.ic_launcher_matte_myata),
        AppIconVariant(MATTE, LAVANDA, R.mipmap.ic_launcher_matte_lavanda),
        AppIconVariant(MATTE, RASSVET_LIGHT, R.mipmap.ic_launcher_matte_rassvet_light),
        AppIconVariant(MATTE, INDIGO_LIGHT, R.mipmap.ic_launcher_matte_indigo_light),
        AppIconVariant(MATTE, MYATA_LIGHT, R.mipmap.ic_launcher_matte_myata_light),
        AppIconVariant(MATTE, LAVANDA_LIGHT, R.mipmap.ic_launcher_matte_lavanda_light),
        AppIconVariant(ONEUI, RASSVET, R.mipmap.ic_launcher_oneui_rassvet),
        AppIconVariant(ONEUI, INDIGO, R.mipmap.ic_launcher_oneui_indigo),
        AppIconVariant(ONEUI, MYATA, R.mipmap.ic_launcher_oneui_myata),
        AppIconVariant(ONEUI, LAVANDA, R.mipmap.ic_launcher_oneui_lavanda),
        AppIconVariant(ONEUI, RASSVET_LIGHT, R.mipmap.ic_launcher_oneui_rassvet_light),
        AppIconVariant(ONEUI, INDIGO_LIGHT, R.mipmap.ic_launcher_oneui_indigo_light),
        AppIconVariant(ONEUI, MYATA_LIGHT, R.mipmap.ic_launcher_oneui_myata_light),
        AppIconVariant(ONEUI, LAVANDA_LIGHT, R.mipmap.ic_launcher_oneui_lavanda_light),
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
