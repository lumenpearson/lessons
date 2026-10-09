package com.lumenpearson.lessons.appicon

/**
 * A catalog for the tests of selection, order and layout, built from the
 * default's own resource.
 *
 * Not [AppIconCatalog]: the owner is about to take most of its sixty-four out,
 * and a test that took its second icon, or its second style, from the real
 * list would throw before it asserted anything the day only «Классика», or
 * only the default, is kept. Every variant here draws the default's icon,
 * which none of these tests looks at; the real catalog's pictures are
 * `AppIconResourcesTest`'s, and its agreement with the manifest and res/ is
 * `AppIconCatalogTest`'s.
 */
internal object TestCatalog {

    /** The real default, which is the alias the manifest enables and the fake reads as on. */
    val default: AppIconVariant = AppIconCatalog.default

    private val otherStyles: List<AppIconStyle> = AppIconStyle.entries - default.style

    /** A variant in a style other than the default's. */
    val other: AppIconVariant = default.copy(style = otherStyles[0])

    /** A variant in a third style, after [other] in [variants]. */
    val another: AppIconVariant = default.copy(style = otherStyles[1], palette = AppIconPalette.INDIGO)

    /**
     * The default's style in five palettes, the default among them: a full row
     * of four tiles and a short row of one.
     */
    val inDefaultStyle: List<AppIconVariant> = listOf(default) +
        (AppIconPalette.entries - default.palette)
            .take(PalettesInDefaultStyle - 1)
            .map { default.copy(palette = it) }

    val variants: List<AppIconVariant> = inDefaultStyle + other + another

    /** A fake launcher whose aliases are this catalog's. */
    fun launcher(explicit: Map<String, Boolean> = emptyMap(), failure: RuntimeException? = null) =
        FakeLauncherComponents(explicit, failure, variants, default)

    /** [LauncherAliases] over this catalog rather than the real one. */
    fun aliases(components: LauncherComponents) = LauncherAliases(components, variants, default)
}

/** One more than a row of four tiles, so that the style's second row holds exactly one. */
private const val PalettesInDefaultStyle = 5
