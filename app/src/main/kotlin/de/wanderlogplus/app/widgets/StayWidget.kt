package de.wanderlogplus.app.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import de.wanderlogplus.app.R

class StayWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, m: WidgetModels, bucket: Bucket) = StayContent(context, m, bucket)
}

class StayReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = StayWidget()
}

/** „Unterkunft heute“: name and check-in/out; then the address; large with booking number, map and „Adresse zeigen“. */
@Composable
fun StayContent(context: Context, m: WidgetModels, bucket: Bucket) {
    if (m.phase == Phase.NO_TRIP) return Hint(SETUP_HINT, openApp(context, WidgetTarget.Today))
    val s = m.stay ?: return Hint("Heute keine Unterkunft eingetragen", openApp(context, WidgetTarget.Today))
    val detail = openApp(context, WidgetTarget.Logistics(de.wanderlogplus.shared.sync.EntityType.STAY, s.stayId))
    WidgetFrame(detail, padding = if (bucket == Bucket.TINY) 6 else 10) {
        if (bucket == Bucket.TINY) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) { WidgetIcon(R.drawable.ic_w_bed, size = 28) }
            return@WidgetFrame
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            WidgetIcon(R.drawable.ic_w_bed, size = 16)
            Spacer(GlanceModifier.width(6.dp))
            TitleText(s.name, maxLines = if (bucket == Bucket.STRIP) 1 else 2)
        }
        BodyText(s.checkLine, maxLines = 1)
        if (!bucket.roomy || bucket == Bucket.SMALL) return@WidgetFrame
        s.address?.let { LabelText(it, GlanceModifier.tag("stay-address"), maxLines = if (bucket.big) 3 else 2) }
        if (!bucket.big) return@WidgetFrame
        s.confirmation?.let { BodyText("Buchung $it") }
        Spacer(GlanceModifier.height(8.dp))
        Row {
            PillButton("Adresse zeigen", openApp(context, WidgetTarget.StayAddress(s.stayId)), "stay-show-address", GlanceModifier.padding(end = 6.dp))
            s.mapsUrl?.let { url ->
                PillButton("Karte", actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)), "stay-map")
            }
        }
    }
}
