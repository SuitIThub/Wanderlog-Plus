package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import de.wanderlogplus.app.R
import de.wanderlogplus.app.ui.today.inText

/** The drawable of a step kind. */
fun StepKind.icon(): Int = when (this) {
    StepKind.PLACE -> R.drawable.ic_w_place
    StepKind.TRAIN -> R.drawable.ic_w_train
    StepKind.FLIGHT -> R.drawable.ic_w_flight
}

/** A tinted widget icon. */
@Composable
fun WidgetIcon(res: Int, size: Int = 18, color: ColorProvider = GlanceTheme.colors.primary, modifier: GlanceModifier = GlanceModifier) =
    Image(ImageProvider(res), contentDescription = null, modifier = modifier.size(size.dp), colorFilter = ColorFilter.tint(color))

/** „09:00 · in 1 Std.“ – the fixed time always stands beside the countdown. */
fun whenText(l: StepLine): String = listOfNotNull(l.time, l.inMinutes?.let { inText(it) }).joinToString(" · ").ifBlank { "ohne Uhrzeit" }

/** One step as a row: icon, time/countdown and title (and the way when [way]); tapping opens [StepLine.target]. */
@Composable
fun StepRow(context: Context, l: StepLine, tag: String, way: Boolean = false, emphasize: Boolean = false, prefix: String? = null) {
    val faded = if (l.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(openApp(context, l.target)).tag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WidgetIcon(l.kind.icon(), size = if (emphasize) 20 else 16, color = if (l.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.primary)
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            LabelText(listOfNotNull(prefix, whenText(l)).joinToString(" · "))
            if (emphasize) TitleText(l.title, maxLines = 2, color = faded)
            else BodyText(l.title, color = faded)
            if (way) l.wayText?.let { LabelText(it) }
        }
    }
}

/** The big number of a tiny widget with a small label under it. */
@Composable
fun BigValue(value: String, label: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TitleText(value, size = 18.sp)
        label?.let { LabelText(it) }
    }
}
