package de.wanderlogplus.app.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Collects the entries of a [Timeline]. */
class TimelineScope internal constructor() {
    internal class Entry(val marker: String, val key: Any?, val content: @Composable () -> Unit)

    internal val entries = mutableListOf<Entry>()
}

/** Adds an entry: [marker] (time, number or "Nacht") above [content]. */
fun TimelineScope.item(marker: String, key: Any? = null, content: @Composable () -> Unit) {
    entries += TimelineScope.Entry(marker, key, content)
}

private val RailWidth = 26.dp
private val DotY = 9.dp
private val DotRadius = 5.dp

/**
 * Vertical timeline: a line with one dot per entry on the left, marker in [accent] above each entry's content.
 * A plain Column (timelines are short), so it may sit inside a scrolling parent.
 */
@Composable
fun Timeline(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    /** Ring around each dot; pass the surface colour when the timeline sits on a tinted surface. */
    ringColor: Color = MaterialTheme.colorScheme.background,
    content: TimelineScope.() -> Unit,
) {
    val entries = TimelineScope().apply(content).entries
    val ring = ringColor
    val line = accent.copy(alpha = 0.35f)
    val markerColor = readableOn(accent, ringColor) // small text: region colors can be too dim on the dark background
    Column(modifier.fillMaxWidth()) {
        entries.forEachIndexed { i, e ->
            key(e.key ?: i) {
                val first = i == 0
                val last = i == entries.lastIndex
                Row(
                    Modifier.fillMaxWidth().drawBehind {
                        val x = RailWidth.toPx() / 2 - 4.dp.toPx()
                        val dotY = DotY.toPx()
                        val w = 2.dp.toPx()
                        if (!first) drawLine(line, Offset(x, 0f), Offset(x, dotY), w)
                        if (!last) drawLine(line, Offset(x, dotY), Offset(x, size.height), w)
                        drawCircle(ring, DotRadius.toPx() + 2.dp.toPx(), Offset(x, dotY))
                        drawCircle(accent, DotRadius.toPx(), Offset(x, dotY))
                    },
                ) {
                    Spacer(Modifier.width(RailWidth))
                    Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 14.dp)) {
                        Text(
                            e.marker,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            color = markerColor,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        e.content()
                    }
                }
            }
        }
    }
}
