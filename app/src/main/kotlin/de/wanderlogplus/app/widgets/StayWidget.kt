package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class StayWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, bucket: Bucket) = Hint("…")
}

class StayReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = StayWidget()
}
