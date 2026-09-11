package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import java.math.BigDecimal
import java.math.RoundingMode

/** Decimal places a stepped value is rounded to, so 0.7 + 0.1 is not 0.7999999. */
private const val ValueScale = 2

/**
 * A row of a group that holds a slider, with a minus and a plus button either
 * side of it.
 *
 * A port of `ConfigSliderItem` from
 * [Essentials](https://github.com/sameerasw/essentials). The two buttons are the
 * part worth keeping: a slider alone cannot be set precisely with a thumb, and
 * Essentials' answer is to step it in exact increments through `BigDecimal` —
 * which is also why the value never drifts after twenty taps.
 *
 * @param increment how far one press of a button moves the value.
 * @param valueFormatter renders the value into the title; return an empty string
 *   to hide it.
 */
@Composable
fun GroupSliderItem(
    title: String,
    tone: AccentTone,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    increment: Float = 0.1f,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueFormatter: ((Float) -> String)? = null,
) {
    val view = rememberHapticView()
    val scheme = MaterialTheme.colorScheme
    val formatted = valueFormatter?.invoke(value).orEmpty()

    fun step(direction: Int) {
        LessonsHaptics.press(view)
        val stepped = BigDecimal.valueOf(value.toDouble())
            .add(BigDecimal.valueOf(increment.toDouble() * direction))
            .setScale(ValueScale, RoundingMode.HALF_UP)
            .toFloat()
        onValueChange(stepped.coerceIn(valueRange))
        onValueChangeFinished?.invoke()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.rowContainer)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) {
                AccentIconTile(icon = icon, tone = tone)
            }
            RowText(
                title = if (formatted.isBlank()) title else "$title: $formatted",
                subtitle = subtitle,
                titleColor = if (enabled) scheme.onSurface else scheme.outline,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { step(-1) }, enabled = enabled) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = stringResource(R.string.ds_slider_decrease),
                    tint = scheme.primary,
                )
            }
            Slider(
                value = value,
                onValueChange = {
                    if (it != value) LessonsHaptics.tick(view)
                    onValueChange(it)
                },
                valueRange = valueRange,
                steps = steps,
                onValueChangeFinished = onValueChangeFinished,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { step(1) }, enabled = enabled) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.ds_slider_increase),
                    tint = scheme.primary,
                )
            }
        }
    }
}

@Preview(name = "GroupSliderItem", showBackground = true)
@Composable
private fun GroupSliderItemPreview() {
    LessonsTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RoundedCardContainer {
                GroupSliderItem(
                    title = "Сила размытия",
                    icon = Icons.Rounded.MotionPhotosOn,
                    tone = accentTone(4),
                    value = 1.4f,
                    onValueChange = {},
                    valueRange = 0.5f..2.5f,
                    valueFormatter = { "%.1f".format(it) },
                )
            }
        }
    }
}
