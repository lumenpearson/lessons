package com.lumenpearson.lessons.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.appicon.AppIconCatalog
import com.lumenpearson.lessons.appicon.AppIconImage
import com.lumenpearson.lessons.appicon.AppIconVariant
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ConcentricShape
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.PillButtonHeight
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * «Значок приложения»: every launcher icon the app offers, one group per style.
 *
 * Essentials' picker is one row of five icons that applies on tap. Here a tap
 * only selects. The preview at the top shows the choice under a circle and a
 * rounded square, the two masks where edge-to-edge and inset styles differ,
 * and «Применить» switches once. Every switch is a launcher re-index, and
 * some launchers drop the icon's place on the home screen each time.
 */
@Composable
internal fun AppIconScreen(
    modifier: Modifier = Modifier,
    viewModel: AppIconViewModel = viewModel(factory = AppIconViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The key rather than the variant: it survives being saved, and a key this
    // build no longer has falls back to the icon in use instead of failing.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = AppIconCatalog.byKey(selectedKey) ?: state.current

    SettingsPage(
        modifier = modifier,
        message = if (state.failed) correctedString(R.string.app_icon_failed) else null,
        messageKey = state.failed,
        onMessageShown = viewModel::consumeFailure,
    ) {
        item(key = "header") {
            ScreenHeader(
                title = correctedString(SettingsSection.APP_ICON.titleRes),
                subtitle = correctedString(SettingsSection.APP_ICON.subtitleRes),
            )
        }
        appIconRows(
            state = state,
            selected = selected,
            onSelect = { selectedKey = it.key },
            onApply = { viewModel.apply(selected) },
        )
    }
}

/**
 * The preview, a group per style and «Применить», over whatever [variants]
 * the catalog still has. Grouped in catalog order, so a style that has been
 * taken out leaves no empty group, and one with fewer palettes leaves a short
 * row.
 */
internal fun LazyListScope.appIconRows(
    state: AppIconUiState,
    selected: AppIconVariant,
    onSelect: (AppIconVariant) -> Unit,
    onApply: () -> Unit,
    variants: List<AppIconVariant> = AppIconCatalog.variants,
) {
    item(key = "app-icon-preview") { AppIconPreview(selected) }
    variants.groupBy { it.style }.forEach { (style, inStyle) ->
        item(key = "app-icon-${style.key}") {
            SettingsGroup(title = correctedString(style.labelRes)) {
                AppIconTiles(variants = inStyle, selected = selected, onSelect = onSelect)
            }
        }
    }
    item(key = "app-icon-apply") {
        ApplyBlock(enabled = selected != state.current && !state.applying, onApply = onApply)
    }
}

/** The row on «Оформление» that opens the page, with the icon in use beside its name. */
@Composable
internal fun AppIconLinkRow(current: AppIconVariant, onOpen: () -> Unit) {
    GroupItem(
        title = correctedString(R.string.settings_app_icon),
        subtitle = variantLabel(current),
        icon = Icons.Rounded.Apps,
        tone = accentTone(slot = 3),
        onClick = onOpen,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LinkGap),
            ) {
                AppIconImage(variant = current, shape = CircleShape, modifier = Modifier.size(LinkIconSize))
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(ChevronSize),
                )
            }
        },
    )
}

/** «Свечение · Мята». */
@Composable
private fun variantLabel(variant: AppIconVariant): String = correctedString(
    R.string.app_icon_variant,
    correctedString(variant.style.labelRes),
    correctedString(variant.palette.labelRes),
)

@Composable
private fun AppIconPreview(variant: AppIconVariant) {
    RoundedCardContainer {
        GroupRow {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PreviewGap),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(PreviewGap)) {
                    MaskedPreview(variant, CircleShape, R.string.app_icon_mask_circle)
                    MaskedPreview(variant, SquircleShape, R.string.app_icon_mask_rounded)
                }
                Text(text = variantLabel(variant), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun MaskedPreview(variant: AppIconVariant, shape: Shape, @StringRes captionRes: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CaptionGap),
    ) {
        AppIconImage(variant = variant, shape = shape, modifier = Modifier.size(PreviewSize))
        Text(
            text = correctedString(captionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppIconTiles(
    variants: List<AppIconVariant>,
    selected: AppIconVariant,
    onSelect: (AppIconVariant) -> Unit,
) {
    GroupRow {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            variants.chunked(TilesPerRow).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { variant ->
                        AppIconTile(variant = variant, selected = variant == selected, onClick = { onSelect(variant) })
                    }
                    // A short last row keeps the columns of the full one above it.
                    repeat(TilesPerRow - row.size) { Spacer(Modifier.size(IconPreviewSize)) }
                }
            }
        }
    }
}

@Composable
private fun AppIconTile(variant: AppIconVariant, selected: Boolean, onClick: () -> Unit) {
    val view = rememberHapticView()
    val label = variantLabel(variant)
    // The ring sits in a gap every tile has, so the chosen icon does not shrink
    // when it is chosen; Essentials pads the selected tile alone, and it jumps.
    // Cell, not CircleShape: this grid picks among icon variants, and the tile
    // around each one is a standalone cell — the same role the weekday tile
    // and the month-grid cell have — not itself a claim that the icon is round.
    // The ring's own corner is ConcentricShape(Cell, RingGap), not Cell
    // itself: a Cell border drawn RingGap outside a Cell clip is the exact
    // wonky corner ConcentricShape's own KDoc describes — the border's
    // radius has to grow by the gap to stay concentric with the clip inside it.
    val ring = if (selected) {
        Modifier.border(
            RingWidth,
            MaterialTheme.colorScheme.primary,
            ConcentricShape(inner = LessonsShapeTokens.Cell, inset = RingGap),
        )
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .size(IconPreviewSize)
            .then(ring)
            .padding(RingGap)
            .clip(LessonsShapeTokens.Cell)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                    LessonsHaptics.press(view)
                    onClick()
                },
            )
            .semantics { contentDescription = label },
    ) {
        AppIconImage(variant = variant, shape = LessonsShapeTokens.Cell, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ApplyBlock(enabled: Boolean, onApply: () -> Unit) {
    val view = rememberHapticView()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HintGap)) {
        Button(
            onClick = {
                LessonsHaptics.press(view)
                onApply()
            },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(PillButtonHeight),
        ) {
            Text(text = correctedString(R.string.app_icon_apply))
        }
        Text(
            text = correctedString(R.string.app_icon_apply_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = HintInset),
        )
    }
}

/**
 * A superellipse, |x|⁵ + |y|⁵ = 1. It stands for the rounded-square masks of
 * One UI and of Pixel's «Squircle», and android/logo/build_styles.py previews
 * the styles under the same one.
 */
private val SquircleShape: Shape = GenericShape { size, _ ->
    for (step in 0 until SquircleSteps) {
        val angle = 2 * PI * step / SquircleSteps
        val x = size.width / 2 * (1 + superellipse(cos(angle)))
        val y = size.height / 2 * (1 + superellipse(sin(angle)))
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun superellipse(value: Double): Float = (sign(value) * abs(value).pow(SquircleExponent)).toFloat()

private const val TilesPerRow = 4
private const val SquircleSteps = 120

/** 2 / n for the superellipse of degree n = 5. */
private const val SquircleExponent = 0.4

/** The picker grid's own tile, distinct from the design system's `TileSize` (the 40 dp
 *  circular accent tile in front of a row) — the two share no role and used to share a
 *  name by coincidence. */
private val IconPreviewSize = 56.dp
private val RingWidth = 2.5.dp
private val RingGap = 4.dp
private val TileGap = 12.dp
private val PreviewSize = 112.dp
private val PreviewGap = 16.dp
private val CaptionGap = 8.dp
private val HintGap = 8.dp
private val HintInset = 16.dp
private val LinkIconSize = 32.dp
private val LinkGap = 8.dp
private val ChevronSize = 20.dp
