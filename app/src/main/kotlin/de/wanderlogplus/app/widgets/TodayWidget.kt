package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class TodayWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, bucket: Bucket) = Hint("…")
}

class TodayReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayWidget()
}
