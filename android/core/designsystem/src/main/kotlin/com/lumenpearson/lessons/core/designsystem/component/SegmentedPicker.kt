package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer

/**
 * A connected button group: two to four mutually exclusive options, laid out as
 * one segmented control.
 *
 * A port of `SegmentedPicker` from
 * [Essentials](https://github.com/sameerasw/essentials), minus its in-app
 * translation-editor plumbing — that component carries a long-press gesture
 * that opens a string-editing sheet, which is a feature of that app rather than
 * of the design system. What is kept is what makes it look right: Material's
 * connected leading/middle/trailing shapes, the `ConnectedSpaceBetween` gap, the
 * row of equal weights and the marquee on labels too long for their segment.
 *
 * @param labelProvider the visible text of an option; also its accessibility name.
 * @param iconProvider optional glyph, drawn before the label.
 */
@Composable
fun <T> SegmentedPicker(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    labelProvider: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    iconProvider: ((T) -> ImageVector)? = null,
    containerColor: Color = Color.Transparent,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val view = rememberHapticView()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        items.forEachIndexed { index, item ->
            val selected = item == selectedItem
            ToggleButton(
                checked = selected,
                onCheckedChange = {
                    LessonsHaptics.tap(view)
                    onItemSelected(item)
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val icon = iconProvider?.invoke(item)
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = labelProvider(item),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                }
            }
        }
    }
}

/**
 * A [SegmentedPicker] presented as a row of a group: the icon tile and title on
 * the first line, the control on the second.
 *
 * This is the shape Essentials gives every one of its pickers — default tab,
 * language, app icon — and the reason they read as settings rather than as a
 * toolbar that happens to be inside a list.
 */
@Composable
fun <T> GroupSegmentedItem(
    title: String,
    tone: AccentTone,
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    labelProvider: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconProvider: ((T) -> ImageVector)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.rowContainer)
            .padding(top = 12.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) {
                AccentIconTile(icon = icon, tone = tone)
            }
            RowText(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        }
        SegmentedPicker(
            items = items,
            selectedItem = selectedItem,
            onItemSelected = onItemSelected,
            labelProvider = labelProvider,
            iconProvider = iconProvider,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
    }
}

@Preview(name = "SegmentedPicker", showBackground = true)
@Composable
private fun SegmentedPickerPreview() {
    LessonsTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RoundedCardContainer {
                GroupSegmentedItem(
                    title = "Вибрация",
                    subtitle = "Отклик на нажатия",
                    icon = Icons.Rounded.Vibration,
                    tone = accentTone(2),
                    items = listOf("Нет", "Лёгкая", "Двойная", "Чёткая"),
                    selectedItem = "Лёгкая",
                    onItemSelected = {},
                    labelProvider = { it },
                )
            }
        }
    }
}
