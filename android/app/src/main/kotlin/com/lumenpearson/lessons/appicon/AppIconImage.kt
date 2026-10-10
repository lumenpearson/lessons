package com.lumenpearson.lessons.appicon

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * A launcher icon, drawn the way a launcher draws it, less a flat plate. The
 * adaptive icon's layers are laid over its 108 dp canvas, the box shows the
 * middle 72 dp, and [shape] masks it.
 *
 * A ground of one flat colour ([AppIconStyle.flatGround]: white under
 * «Классика» and «Край в край», black under «AMOLED») is there only because a
 * launcher needs a full square. In the app it would be a tile on the app's own
 * surface, so it is left out and the mark stands alone. Every other ground is
 * part of the style and is drawn. Essentials' picker drew every foreground on
 * white, which is wrong for «Свечение», «Тёмная», «Матовая», «Размытие» and
 * «Матовое стекло».
 */
@Composable
fun AppIconImage(
    variant: AppIconVariant,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val icon = remember(context, variant) {
        // getDrawable's instances share a vector's render cache, and the chosen icon draws at two sizes at once.
        (context.getDrawable(variant.icon) as? AdaptiveIconDrawable)?.apply { mutate() }
    }
    Canvas(modifier.clip(shape)) {
        val layers = icon ?: return@Canvas
        val bleed = (size.minDimension * BleedFraction).roundToInt()
        val right = size.width.roundToInt() + bleed
        val bottom = size.height.roundToInt() + bleed
        val ground = layers.background.takeUnless { variant.style.flatGround }
        drawIntoCanvas { canvas ->
            for (layer in listOfNotNull(ground, layers.foreground)) {
                layer.setBounds(-bleed, -bleed, right, bottom)
                layer.draw(canvas.nativeCanvas)
            }
        }
    }
}

/**
 * The icon the launcher shows, read off the main thread. Until the first read
 * lands it is the default, and on a cold start that is a few milliseconds.
 */
@Composable
fun rememberCurrentAppIcon(): AppIconVariant {
    val context = LocalContext.current
    val store = remember(context) { AppIcons.store(context) }
    LaunchedEffect(store) { runCatching { store.refresh() } }
    val current by store.current.collectAsStateWithLifecycle()
    return current ?: AppIconCatalog.default
}

/** What the 108 dp canvas has beyond the visible 72 dp on each side, as a fraction of the 72. */
private const val BleedFraction = (108f - 72f) / 2f / 72f
