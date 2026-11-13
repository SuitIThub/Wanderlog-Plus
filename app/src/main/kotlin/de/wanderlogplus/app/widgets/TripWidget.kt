package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent

/** Base of the trip widgets: responsive sizes, theme, and the content for the bucket the widget is placed in. */
abstract class TripWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(ALL_SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Content(context, bucketOf(LocalSize.current)) } }
    }

    @Composable
    abstract fun Content(context: Context, bucket: Bucket)
}
