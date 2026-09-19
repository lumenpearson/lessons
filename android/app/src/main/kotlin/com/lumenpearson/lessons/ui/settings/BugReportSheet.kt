package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.text.MarqueeText
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer

/**
 * Where a problem gets written down.
 *
 * A port of the "Bug report" sheet from
 * [Essentials](https://github.com/sameerasw/essentials): the device block in
 * a card, a big field for the words, a small one for a way back, one filled
 * button that does the sending and two outlined ones for doing it some other
 * way. Essentials posts the form to its own server; this app has none, so the
 * filled button files a GitHub issue as the signed-in user and stays disabled
 * until there is one. The two alternatives need no account, which is the point
 * of keeping them on the sheet rather than behind the sign-in.
 *
 * The sheet holds only what is typed. Which device this is, whether an issue is
 * in flight and whether there is anyone to email are the caller's facts, passed
 * in — so the sheet renders the same from a preview as from the settings page.
 *
 * @param deviceInfo the lines of the device card, in the order they are shown.
 * @param isSignedIn gates the filled button; the sign-in row lives elsewhere.
 * @param isSending an issue is on its way; the filled button shows it and
 *   refuses a second tap.
 * @param canEmail whether the build carries a contact address. When it does
 *   not, the email pill is not drawn at all rather than drawn and disabled: a
 *   greyed button asks the reader to work out what would enable it, and the
 *   answer here is "a different build".
 * @param onSend the trimmed description and the contact email, `null` when the
 *   field was left empty.
 * @param onOpenIssuePage the description, for a prefilled new-issue page.
 * @param onEmail the description and the device block, one `key: value` per
 *   line, for the body of a mail.
 */
@Composable
fun BugReportSheet(
    deviceInfo: List<Pair<String, String>>,
    isSignedIn: Boolean,
    isSending: Boolean,
    canEmail: Boolean,
    onDismiss: () -> Unit,
    onSend: (description: String, email: String?) -> Unit,
    onOpenIssuePage: (description: String) -> Unit,
    onEmail: (description: String, deviceBlock: String) -> Unit,
) {
    LessonsBottomSheet(onDismissRequest = onDismiss) {
        BugReportContent(
            deviceInfo = deviceInfo,
            isSignedIn = isSignedIn,
            isSending = isSending,
            canEmail = canEmail,
            onSend = onSend,
            onOpenIssuePage = onOpenIssuePage,
            onEmail = onEmail,
        )
    }
}

/**
 * The sheet's body, split out so that a `@Preview` can render it without the
 * modal host: `ModalBottomSheet` lives in its own window and draws nothing in
 * the design tool.
 */
@Composable
private fun ColumnScope.BugReportContent(
    deviceInfo: List<Pair<String, String>>,
    isSignedIn: Boolean,
    isSending: Boolean,
    canEmail: Boolean,
    onSend: (description: String, email: String?) -> Unit,
    onOpenIssuePage: (description: String) -> Unit,
    onEmail: (description: String, deviceBlock: String) -> Unit,
) {
    // Saveable, not remembered: a rotation or a trip to the mail app and back
    // must not cost the paragraph someone just wrote.
    var description by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    val view = rememberHapticView()

    val trimmed = description.trim()
    val contact = email.trim().takeIf { it.isNotEmpty() }
    val deviceBlock = remember(deviceInfo) {
        deviceInfo.joinToString(separator = "") { (key, value) -> "$key: $value\n" }
    }
    val canSend = trimmed.isNotEmpty() && isSignedIn && !isSending

    Text(
        text = correctedString(R.string.bug_report_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
    )

    DeviceCard(deviceInfo = deviceInfo)

    ReportField(
        value = description,
        onValueChange = { description = it },
        label = correctedString(R.string.bug_report_description_label),
        minLines = DescriptionMinLines,
        shape = RoundedCornerShape(FieldCorner),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default,
        ),
    )
    ReportField(
        value = email,
        onValueChange = { email = it },
        label = correctedString(R.string.bug_report_email_label),
        singleLine = true,
        // A one-line field is a pill, like the buttons under it; only the
        // tall field needs corners it can afford.
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        ),
    )

    Button(
        onClick = {
            LessonsHaptics.press(view)
            onSend(trimmed, contact)
        },
        enabled = canSend,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .height(PillHeight),
    ) {
        when {
            isSending -> {
                // The content colour rather than `onPrimary`: the button is
                // disabled while it sends, and on a disabled container
                // `onPrimary` is a white ring on a pale grey.
                CircularProgressIndicator(
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                    strokeWidth = BusyStroke,
                    color = LocalContentColor.current,
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            }
            // No glyph while signed out: the label is already the longest
            // string on the sheet, and a "send" arrow on a button that cannot
            // send is a promise the button does not keep.
            isSignedIn -> {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            }
        }
        MarqueeText(
            text = correctedString(
                if (isSignedIn) R.string.bug_report_send else R.string.bug_report_send_signed_out,
            ),
        )
    }

    Text(
        text = correctedString(R.string.bug_report_or),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedPill(
        label = correctedString(R.string.bug_report_open_issue),
        icon = Icons.Rounded.BugReport,
        onClick = { onOpenIssuePage(trimmed) },
    )
    if (canEmail) {
        OutlinedPill(
            label = correctedString(R.string.bug_report_email),
            icon = Icons.Rounded.Email,
            onClick = { onEmail(trimmed, deviceBlock) },
        )
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * What the report will say about the phone, shown before it is sent.
 *
 * Monospaced so the block reads as the text it will be pasted as, and on the
 * row colour rather than the sheet's own: the sheet is `surfaceContainerHigh`
 * already, and a card in the same colour would be an invisible one.
 */
@Composable
private fun DeviceCard(deviceInfo: List<Pair<String, String>>) {
    Surface(
        shape = RoundedCornerShape(CardCorner),
        color = MaterialTheme.colorScheme.rowContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(DeviceLineGap),
        ) {
            Text(
                text = correctedString(R.string.bug_report_device),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = DeviceLineGap),
            )
            deviceInfo.forEach { (key, value) ->
                Text(
                    text = "$key: $value",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

/**
 * A text field of this sheet: full width, inside the screen margin, with the
 * label going bold while the field has focus — the one accent a field gets,
 * the same as on the server-address sheet.
 */
@Composable
private fun ReportField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    shape: Shape,
    keyboardOptions: KeyboardOptions,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    // Held only to know whether the field has focus.
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        label = {
            Text(
                text = label,
                style = LocalTextStyle.current.emphasised(focused),
            )
        },
        singleLine = singleLine,
        minLines = minLines,
        shape = shape,
        keyboardOptions = keyboardOptions,
        interactionSource = interactionSource,
    )
}

/** One of the two quieter ways out, drawn the same size as the filled one. */
@Composable
private fun OutlinedPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val view = rememberHapticView()

    OutlinedButton(
        onClick = {
            LessonsHaptics.press(view)
            onClick()
        },
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .height(PillHeight),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        MarqueeText(text = label)
    }
}

/** Radius of the device card; the page's other cards use the same one. */
private val CardCorner = 24.dp

private val CardPadding = 20.dp

private val DeviceLineGap = 4.dp

/** Corner of the tall field. Same radius as a group, so it sits in the family. */
private val FieldCorner = 24.dp

/** Tall enough to say that a paragraph is welcome here. */
private const val DescriptionMinLines = 4

/** Height of a full-width pill button on a sheet. */
private val PillHeight = 56.dp

private val BusyStroke = 2.dp

@Preview(name = "Bug report", showBackground = true)
@Composable
private fun BugReportSheetPreview() {
    LessonsTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BugReportContent(
                    deviceInfo = listOf(
                        "Устройство" to "Google Pixel 8",
                        "Android" to "15 (SDK 35)",
                        "Версия" to "0.1.0",
                    ),
                    isSignedIn = true,
                    isSending = false,
                    canEmail = true,
                    onSend = { _, _ -> },
                    onOpenIssuePage = {},
                    onEmail = { _, _ -> },
                )
            }
        }
    }
}
