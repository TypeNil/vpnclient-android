package dev.typenil.vpnclient.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

enum class EmptyArt { Subscriptions, NoServers, Search }

/**
 * An empty list that says why and what to do: drawn illustration (decorative, hidden from TalkBack), one heading,
 * one line of explanation and at most one primary action. The text carries the whole meaning.
 */
@Composable
fun AfterglowEmptyState(
    art: EmptyArt,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionStyle: AfterglowButtonStyle = AfterglowButtonStyle.Primary,
    onAction: () -> Unit = {},
) {
    val c = AfterglowTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = AfterglowTokens.emptyInset, vertical = AfterglowTokens.sectionGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AfterglowTokens.headerGap),
    ) {
        EmptyIllustration(art)
        Text(title, style = AfterglowType.rowTitle, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(body, style = AfterglowType.rowCaption, color = c.muted, textAlign = TextAlign.Center)
        if (actionLabel != null) {
            AfterglowButton(onClick = onAction, style = actionStyle, modifier = Modifier.padding(top = AfterglowTokens.headerGap)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun EmptyIllustration(art: EmptyArt) {
    val c = AfterglowTheme.colors
    Canvas(Modifier.size(AfterglowTokens.emptyArt)) {
        val line = AfterglowTokens.chartLine.toPx()
        when (art) {
            EmptyArt.Subscriptions -> {
                card(Offset(.2f, .12f), c.paperSecondary, c.border, line)
                card(Offset(.1f, .26f), c.surfaceElevated, c.borderStrong, line)
                bar(.22f, .42f, .56f, c.muted, line)
                bar(.22f, .54f, .4f, c.border, line)
                drawCircle(c.coral, radius = size.minDimension * .17f, center = Offset(size.width * .76f, size.height * .78f))
                bar(.68f, .78f, .16f, c.onInk, line) // plus: horizontal…
                drawLine(c.onInk, Offset(size.width * .76f, size.height * .70f), Offset(size.width * .76f, size.height * .86f), line, StrokeCap.Round)
            }
            EmptyArt.NoServers -> {
                card(Offset(.1f, .2f), c.surfaceElevated, c.borderStrong, line)
                bar(.22f, .36f, .56f, c.border, line)
                bar(.22f, .5f, .4f, c.border, line)
                bar(.22f, .64f, .28f, c.border, line)
                drawCircle(c.coral, radius = size.minDimension * .06f, center = Offset(size.width * .78f, size.height * .64f))
            }
            EmptyArt.Search -> {
                val r = size.minDimension * .26f
                val center = Offset(size.width * .44f, size.height * .42f)
                drawCircle(c.surfaceElevated, r, center)
                drawCircle(c.borderStrong, r, center, style = Stroke(line * 1.5f))
                drawLine(c.coral, center + Offset(r * .72f, r * .72f), center + Offset(r * 1.7f, r * 1.7f), line * 2, StrokeCap.Round)
                drawLine(c.muted, center - Offset(r * .4f, 0f), center + Offset(r * .4f, 0f), line, StrokeCap.Round)
            }
        }
    }
}

/** Rounded card at a fractional [at] position, 80% x 56% of the canvas. */
private fun DrawScope.card(at: Offset, fill: Color, outline: Color, line: Float) {
    val topLeft = Offset(size.width * at.x, size.height * at.y)
    val box = Size(size.width * .8f, size.height * .56f)
    val corner = CornerRadius(size.minDimension * .1f)
    drawRoundRect(fill, topLeft, box, corner)
    drawRoundRect(outline, topLeft, box, corner, Stroke(line))
}

private fun DrawScope.bar(x: Float, y: Float, length: Float, color: Color, line: Float) =
    drawLine(color, Offset(size.width * x, size.height * y), Offset(size.width * (x + length), size.height * y), line * 1.5f, StrokeCap.Round)
