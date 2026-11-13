package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height

class NextUpWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, m: WidgetModels, bucket: Bucket) = NextUpContent(context, m, bucket)
}

class NextUpReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextUpWidget()
}

/** „Als Nächstes“: before the trip the countdown, during it the next steps (more of them the larger the widget). */
@Composable
fun NextUpContent(context: Context, m: WidgetModels, bucket: Bucket) {
    when (m.phase) {
        Phase.NO_TRIP -> Hint(SETUP_HINT, openApp(context, WidgetTarget.Today))
        Phase.AFTER -> Hint("Willkommen zurück", openApp(context, WidgetTarget.Today))
        Phase.BEFORE -> Countdown(context, m.nextUp, bucket)
        Phase.DURING -> During(context, m.nextUp, bucket)
    }
}

const val SETUP_HINT = "Reise in der App einrichten"

/** „noch 42 Tage“ with the trip's name, the open deadlines and (wide enough) the next one. */
@Composable
fun Countdown(context: Context, n: NextUpModel, bucket: Bucket) {
    val days = n.daysUntilStart ?: 0
    WidgetFrame(openApp(context, WidgetTarget.Today)) {
        if (bucket == Bucket.TINY) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) { BigValue("$days", if (days == 1L) "Tag" else "Tage") }
            return@WidgetFrame
        }
        LabelText(n.tripName ?: "Reise")
        TitleText("noch $days " + if (days == 1L) "Tag" else "Tage", size = 18.sp)
        if (bucket.roomy && n.openDeadlines > 0) {
            Spacer(GlanceModifier.height(4.dp))
            BodyText("${n.openDeadlines} offene " + if (n.openDeadlines == 1) "Frist" else "Fristen")
            if (bucket != Bucket.SMALL) n.nextDeadline?.let { LabelText(it, maxLines = if (bucket.big) 3 else 2) }
        }
    }
}

@Composable
private fun During(context: Context, n: NextUpModel, bucket: Bucket) {
    val next = n.upcoming.firstOrNull()
    val dayTarget = n.date?.let { WidgetTarget.Day(it) } ?: WidgetTarget.Today
    if (next == null && n.current == null) {
        Hint("Heute nichts mehr geplant", openApp(context, dayTarget))
        return
    }
    when (bucket) {
        Bucket.TINY -> WidgetFrame(openApp(context, (next ?: n.current)!!.target), padding = 6) {
            val l = (next ?: n.current)!!
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                WidgetIcon(l.kind.icon(), size = 16)
                TitleText(l.time ?: "jetzt", size = 16.sp)
            }
        }
        Bucket.STRIP -> WidgetFrame(openApp(context, dayTarget), padding = 8) {
            if (next != null) StepRow(context, next, "nu-next", emphasize = false)
            else StepRow(context, n.current!!, "nu-current", prefix = "Jetzt")
        }
        Bucket.SMALL, Bucket.MEDIUM, Bucket.WIDE -> WidgetFrame(openApp(context, dayTarget)) {
            n.current?.let { StepRow(context, it, "nu-current", prefix = "Jetzt") }
            next?.let { StepRow(context, it, "nu-next", way = bucket == Bucket.WIDE, emphasize = n.current == null) }
            // A second upcoming step only when nothing is running (the space is two rows).
            if (n.current == null && bucket != Bucket.SMALL) n.upcoming.getOrNull(1)?.let { StepRow(context, it, "nu-row-1") }
        }
        Bucket.TALL, Bucket.LARGE -> WidgetFrame(openApp(context, dayTarget)) {
            LabelText("Als Nächstes")
            n.current?.let { StepRow(context, it, "nu-current", prefix = "Jetzt") }
            // Scrolls when the steps do not fit, instead of cutting the last one off.
            androidx.glance.appwidget.lazy.LazyColumn {
                itemsIndexed(n.upcoming) { i, l -> StepRow(context, l, "nu-row-$i", way = true, emphasize = i == 0) }
            }
        }
    }
}
