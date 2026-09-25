package com.lumenpearson.lessons.ui.docs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.AccentTile
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.text.MarkupStyles
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.model.DocsBlock
import com.lumenpearson.lessons.core.model.DocsSpan

/*
 * How one block of the app's Markdown is drawn, apart from the screen that pages
 * through the guide.
 *
 * Its own file because the guide is no longer the only reader: the terms of use
 * and the privacy policy are written in the same subset and drawn in a sheet by
 * `ui/legal`. A second renderer for the same four blocks would draw a note one
 * way in the guide and another in the policy, and the first reader to notice
 * would be somebody wondering which of the two is the real document. Moved
 * rather than rewritten: nothing here changed but where it lives, and only
 * [DocsRun] is visible outside the file, because a run is the unit a reader
 * draws.
 */

/** One run: either a stack of steps in one container, or a single block. */
@Composable
internal fun DocsRun(run: List<DocsBlock>, styles: MarkupStyles) {
    if (run.size > 1 || run.first() is DocsBlock.Step) {
        RoundedCardContainer {
            run.forEachIndexed { index, block ->
                val step = block as DocsBlock.Step
                StepRow(step = step, tone = index, styles = styles)
            }
        }
        return
    }

    when (val block = run.first()) {
        is DocsBlock.Paragraph -> DocsParagraph(block.spans, styles)
        is DocsBlock.Points -> DocsPoints(block.items.map { it.spans }, styles)
        is DocsBlock.Note -> DocsNote(block.spans, styles)
        // Unreachable: a lone step took the branch above.
        is DocsBlock.Step -> Unit
    }
}

/**
 * Prose, on the page itself rather than in a container.
 *
 * A paragraph in a card reads as a row of a list, which is what every other
 * container in this app is; the documentation is the one screen whose default
 * content is text, and the text should look like text. The 8 dp inset is
 * [ScreenHeader]'s own, so the first line of a paragraph starts under the first
 * letter of the heading.
 */
@Composable
private fun DocsParagraph(spans: List<DocsSpan>, styles: MarkupStyles) {
    Text(
        text = annotated(spans, styles),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    )
}

/** Several peers, each on a row of the group, marked by a coloured dot. */
@Composable
private fun DocsPoints(items: List<List<DocsSpan>>, styles: MarkupStyles) {
    RoundedCardContainer {
        items.forEachIndexed { index, spans ->
            GroupRow(verticalAlignment = Alignment.Top) {
                // A dot rather than the 40 dp icon tile every settings row has:
                // the points of a list are not eight different things needing
                // eight different glyphs, and a column of tiles would be the
                // loudest part of a page made of sentences. The hue still
                // walks the accent slots, which is what makes a point findable
                // again after it has been read once.
                AccentTile(
                    tone = accentTone(index),
                    size = DotSize,
                    modifier = Modifier.padding(top = DotOffset),
                ) {}
                Text(
                    text = annotated(spans, styles),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The sentence a skimmer must not skim past.
 *
 * Given a container and a glyph precisely because the prose around it has
 * neither: on a page of unadorned paragraphs, a card is the loudest thing
 * available and costs nothing to read.
 */
@Composable
private fun DocsNote(spans: List<DocsSpan>, styles: MarkupStyles) {
    RoundedCardContainer {
        GroupRow(
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            verticalAlignment = Alignment.Top,
        ) {
            AccentIconTile(icon = Icons.Rounded.Info, tone = accentTone(5))
            Text(
                text = annotated(spans, styles),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One numbered stage: the number on the tile, then its own title and prose. */
@Composable
private fun StepRow(step: DocsBlock.Step, tone: Int, styles: MarkupStyles) {
    GroupRow(verticalAlignment = Alignment.Top) {
        AccentTile(tone = accentTone(tone)) {
            Text(
                text = step.number.toString(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = accentTone(tone).content,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (step.title.isNotEmpty()) {
                Text(
                    text = annotated(step.title, styles),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = annotated(step.text, styles),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The parsed spans of one line, as text the screen can draw.
 *
 * Flat, like the release notes' renderer and for the same reason: a bold span
 * is bold text, not a sub-document. A link is a `LinkAnnotation` rather than a
 * click handler, so the platform opens it and a screen reader announces it as a
 * link.
 */
private fun annotated(spans: List<DocsSpan>, styles: MarkupStyles): AnnotatedString =
    buildAnnotatedString {
        spans.forEach { span ->
            when {
                span.link != null -> withLink(
                    LinkAnnotation.Url(url = span.link!!, styles = styles.link),
                ) {
                    append(span.text)
                }

                span.code -> withStyle(styles.code) { append(span.text) }
                span.bold -> withStyle(styles.bold) { append(span.text) }
                else -> append(span.text)
            }
        }
    }

/** Diameter of a point's dot, and how far down the row it sits to meet the first line. */
private val DotSize = 10.dp
private val DotOffset = 5.dp
