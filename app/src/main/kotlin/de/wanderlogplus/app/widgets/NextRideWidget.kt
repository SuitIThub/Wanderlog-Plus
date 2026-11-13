package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class NextRideWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, bucket: Bucket) = Hint("…")
}

class NextRideReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextRideWidget()
}
