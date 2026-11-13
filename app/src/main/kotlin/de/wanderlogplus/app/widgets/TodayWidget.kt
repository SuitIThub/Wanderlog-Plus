package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.width
import de.wanderlogplus.app.R

class TodayWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, m: WidgetModels, bucket: Bucket) = TodayContent(context, m, bucket)
}

class TodayReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayWidget()
}

/** „Heute kompakt“: date, region, tonight's stay and the next step; tall sizes add the weather and the whole program. */
@Composable
fun TodayContent(context: Context, m: WidgetModels, bucket: Bucket) {
    val t = m.today
    if (t == null) {
        // Outside the trip this widget shows what "Als Nächstes" shows.
        return NextUpContent(context, m, bucket)
    }
    val open = openApp(context, WidgetTarget.Today)
    WidgetFrame(open) {
        if (bucket == Bucket.TINY) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) { BigValue(t.dateLabel.substringAfter(", "), t.dateLabel.substringBefore(",")) }
            return@WidgetFrame
        }
        LabelText(t.dateLabel + (t.region?.let { " · $it" } ?: ""))
        if (bucket == Bucket.STRIP) {
            (m.nextUp.upcoming.firstOrNull() ?: m.nextUp.current)?.let { BodyText(whenText(it) + " " + it.title) } ?: BodyText("Heute nichts mehr geplant")
            return@WidgetFrame
        }
        t.stayName?.let { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                WidgetIcon(R.drawable.ic_w_bed, size = 14)
                Spacer(GlanceModifier.width(6.dp))
                BodyText(name)
            }
        }
        if (bucket.big) t.weatherLine?.let { LabelText(it, maxLines = 2) }
        Spacer(GlanceModifier.height(4.dp))
        if (!bucket.big) {
            val next = m.nextUp.upcoming.firstOrNull()
            when {
                next != null -> StepRow(context, next, "today-step-0", emphasize = bucket != Bucket.SMALL)
                m.nextUp.current != null -> StepRow(context, m.nextUp.current, "today-step-0", prefix = "Jetzt")
                else -> BodyText("Heute nichts mehr geplant")
            }
            return@WidgetFrame
        }
        if (t.steps.isEmpty()) {
            BodyText("Heute nichts geplant", size = 13.sp)
            return@WidgetFrame
        }
        LazyColumn {
            itemsIndexed(t.steps) { i, l -> StepRow(context, l, "today-step-$i", way = bucket == Bucket.LARGE) }
        }
    }
}
