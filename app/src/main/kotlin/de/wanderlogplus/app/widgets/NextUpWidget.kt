package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class NextUpWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, m: WidgetModels, bucket: Bucket) = Hint("…")
}

class NextUpReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextUpWidget()
}
