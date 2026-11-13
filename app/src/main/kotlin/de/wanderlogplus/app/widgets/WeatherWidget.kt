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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import de.wanderlogplus.app.R
import de.wanderlogplus.shared.sync.DayWeather
import de.wanderlogplus.shared.sync.WeatherText

class WeatherWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, m: WidgetModels, bucket: Bucket) = WeatherContent(context, m, bucket)
}

class WeatherReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WeatherWidget()
}

/** The drawable for a weather code. */
fun weatherIcon(code: Int?): Int = when (WeatherText.icon(code)) {
    "sun" -> R.drawable.ic_w_sun
    "rain", "storm" -> R.drawable.ic_w_rain
    else -> R.drawable.ic_w_cloud
}

private fun hi(d: DayWeather): String = d.tempMax?.let { "${Math.round(it)}°" } ?: "–"

/** „Wetter“: today (and tomorrow, and the hours) for the region of the day, from the stored forecast. */
@Composable
fun WeatherContent(context: Context, m: WidgetModels, bucket: Bucket) {
    if (m.phase == Phase.NO_TRIP) return Hint(SETUP_HINT, openApp(context, WidgetTarget.Today))
    val w = m.weather
    val today = w?.today ?: return Hint("Noch keine Vorhersage", openApp(context, WidgetTarget.Today))
    WidgetFrame(openApp(context, WidgetTarget.Today), padding = if (bucket == Bucket.TINY) 6 else 10) {
        if (bucket == Bucket.TINY) {
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                if (today.isForecast) WidgetIcon(weatherIcon(today.code), size = 20)
                TitleText(if (today.isForecast) hi(today) else "Ø ${hi(today)}", size = 16.sp)
            }
            return@WidgetFrame
        }
        LabelText(w.place)
        Row(GlanceModifier.fillMaxWidth()) {
            DayColumn("Heute", today, "weather-today", GlanceModifier.defaultWeight())
            if (bucket.wide && bucket != Bucket.STRIP) w.tomorrow?.let { DayColumn("Morgen", it, "weather-tomorrow", GlanceModifier.defaultWeight()) }
        }
        if (!bucket.big) return@WidgetFrame
        val hours = today.hours.filterIndexed { i, _ -> i % 3 == 0 }.take(if (bucket == Bucket.LARGE) 6 else 4)
        if (hours.isNotEmpty()) {
            Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth().tag("weather-hours")) {
                hours.forEach { h ->
                    Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        LabelText(h.time.take(5))
                        WidgetIcon(weatherIcon(h.code), size = 14)
                        LabelText(h.temp?.let { "${Math.round(it)}°" } ?: "–")
                    }
                }
            }
        }
        w.rainHint?.let {
            Spacer(GlanceModifier.height(6.dp))
            LabelText(it, maxLines = 3)
        }
    }
}

@Composable
private fun DayColumn(label: String, d: DayWeather, tag: String, modifier: GlanceModifier) {
    Column(modifier.tag(tag).padding(end = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (d.isForecast) {
                WidgetIcon(weatherIcon(d.code), size = 18)
                Spacer(GlanceModifier.width(4.dp))
            }
            TitleText((if (d.isForecast) "" else "Ø ") + (WidgetData.tempRange(d) ?: "–"), size = 14.sp)
        }
        LabelText(label + " · " + if (d.isForecast) WeatherText.label(d.code) else "Klima-Mittel")
        d.rainChance?.takeIf { d.isForecast }?.let { LabelText("Regen $it %") }
    }
}
