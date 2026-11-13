package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.today.inText

class NextRideWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, m: WidgetModels, bucket: Bucket) = NextRideContent(context, m, bucket)
}

class NextRideReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextRideWidget()
}

/** „Nächste Fahrt“: departure, train/flight, platform; larger sizes add route, seat, transfers and tickets. */
@Composable
fun NextRideContent(context: Context, m: WidgetModels, bucket: Bucket) {
    if (m.phase == Phase.NO_TRIP) return Hint(SETUP_HINT, openApp(context, WidgetTarget.Today))
    val r = m.ride ?: return Hint("Keine Fahrt in den nächsten 36 Stunden", openApp(context, WidgetTarget.Today))
    val today = m.now.toLocalDate()
    val dayPrefix = when (java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(r.departsDate))) {
        0L -> null
        1L -> "morgen"
        else -> dayLabel(r.departsDate)
    }
    val countdown = if (r.inMinutes <= 0) r.arrives?.let { "unterwegs · an $it" } ?: "unterwegs" else inText(r.inMinutes)
    val whenLine = listOfNotNull(dayPrefix, r.departs, countdown).joinToString(" · ")
    WidgetFrame(openApp(context, r.target), padding = if (bucket == Bucket.TINY) 6 else 10) {
        when (bucket) {
            Bucket.TINY -> Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                WidgetIcon(r.kind.icon(), size = 16)
                TitleText(r.departs, size = 16.sp)
            }
            Bucket.STRIP -> Row(verticalAlignment = Alignment.CenterVertically) {
                WidgetIcon(r.kind.icon(), size = 20)
                Spacer(GlanceModifier.width(8.dp))
                Column {
                    LabelText(whenLine)
                    BodyText(listOfNotNull(r.name, r.platform).joinToString(" · "), GlanceModifier.tag("ride-platform"))
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WidgetIcon(r.kind.icon(), size = 18)
                    Spacer(GlanceModifier.width(6.dp))
                    LabelText(whenLine)
                }
                TitleText(r.route, maxLines = 2)
                if (bucket == Bucket.SMALL) {
                    r.platform?.let { BodyText(it, GlanceModifier.tag("ride-platform")) }
                    return@WidgetFrame
                }
                BodyText(r.times)
                listOfNotNull(r.platform, r.seat).takeIf { it.isNotEmpty() }?.let { BodyText(it.joinToString(" · "), GlanceModifier.tag("ride-platform"), maxLines = 2) }
                if (bucket == Bucket.MEDIUM) return@WidgetFrame
                r.transfers.take(if (bucket.big) 3 else 1).forEach { LabelText(it, maxLines = 2) }
                if (r.tickets.isNotEmpty()) {
                    Spacer(GlanceModifier.height(6.dp))
                    Row {
                        r.tickets.take(if (bucket == Bucket.TALL) 1 else 2).forEachIndexed { i, (label, id) ->
                            PillButton(label.take(18), openApp(context, WidgetTarget.Attachment(id)), "ride-ticket-$i", GlanceModifier.padding(end = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
