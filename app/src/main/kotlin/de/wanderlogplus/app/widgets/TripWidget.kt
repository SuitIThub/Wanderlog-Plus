package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import de.wanderlogplus.app.WanderlogPlusApp
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime

/** Base of the trip widgets: loads the shared models, then draws the content for the bucket the widget is placed in. */
abstract class TripWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(ALL_SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val models = loadModels(context)
        provideContent { GlanceTheme { Content(context, models, bucketOf(LocalSize.current)) } }
    }

    @Composable
    abstract fun Content(context: Context, m: WidgetModels, bucket: Bucket)
}

/** The models of the current redraw; without data (or on any error) the "set up the trip" state. */
suspend fun loadModels(context: Context): WidgetModels = try {
    val app = context.applicationContext as WanderlogPlusApp
    WidgetData.build(app.container.widgets.input())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    WidgetData.build(WidgetInput(null, LocalDateTime.now(), null, null, null, null))
}
