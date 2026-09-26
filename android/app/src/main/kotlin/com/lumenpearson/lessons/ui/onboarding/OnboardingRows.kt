package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.designsystem.theme.rowSelectedContainer

/**
 * The shape of the list steps — region, school, provider: a lazy list that
 * grows, then the action row, which does not move.
 *
 * Separate from `StepScaffold` because that one scrolls a plain column, and a
 * column that scrolls cannot hold a lazy list — eighty-nine regions drawn at
 * once is a first frame nobody should wait for. The keyboard padding is on the
 * outer column, as the join screen has it, so the keyboard shrinks the list and
 * lifts the action rather than covering it.
 */
@Composable
internal fun ListStepScaffold(
    actions: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .appScrollMotionBlur(listState),
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(GroupSpacing),
            content = content,
        )
        actions()
    }
}

/**
 * A row that is one answer of several: highlighted when chosen, and said to be
 * so, because the highlight alone is a colour a screen reader cannot see.
 *
 * On `GroupRow` rather than `GroupItem`, which has no container parameter and
 * so no way to show a choice other than by its switch.
 */
@Composable
internal fun ChoiceRow(
    title: String,
    tone: AccentTone,
    selected: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    below: @Composable (ColumnScope.() -> Unit)? = null,
) {
    GroupRow(
        modifier = modifier.semantics {
            this.selected = selected
            if (onClick != null) role = Role.RadioButton
        },
        container = if (selected) MaterialTheme.colorScheme.rowSelectedContainer else MaterialTheme.colorScheme.rowContainer,
        onClick = onClick,
    ) {
        if (icon != null) AccentIconTile(icon = icon, tone = tone)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            below?.invoke(this)
        }
        trailing?.invoke()
    }
}

/** A sentence in place of a list: nothing found, what to type, why the search is resting. */
@Composable
internal fun NoteCard(text: String, modifier: Modifier = Modifier) {
    RoundedCardContainer(modifier = modifier) {
        GroupRow {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The search field of the region and school steps.
 *
 * @param onSearch the keyboard's search key; `null` where typing is enough.
 */
@Composable
internal fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (value.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = clearLabel)
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboard?.hide()
                onSearch?.invoke()
            },
        ),
    )
}
