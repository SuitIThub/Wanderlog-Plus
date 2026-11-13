package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class BudgetWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, bucket: Bucket) = Hint("…")
}

class BudgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BudgetWidget()
}
