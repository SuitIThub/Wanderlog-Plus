package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import java.util.Locale

class BudgetWidget : TripWidget() {
    @Composable
    override fun Content(context: Context, m: WidgetModels, bucket: Bucket) = BudgetContent(context, m, bucket)
}

class BudgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BudgetWidget()
}

/** „4.300 ¥“ */
fun yen(v: Long): String = String.format(Locale.GERMAN, "%,d ¥", v)

/** „6,10 €“ */
fun eur(v: Double): String = String.format(Locale.GERMAN, "%,.2f €", v)

/** „12.04.“ from yyyy-MM-dd. */
private fun shortDate(d: String): String = runCatching { java.time.LocalDate.parse(d.take(10)).let { "%02d.%02d.".format(it.dayOfMonth, it.monthValue) } }.getOrDefault(d)

/** „Yen & Budget“: the yen rate, the conversion table and what was spent today; „+“ opens a new yen expense. */
@Composable
fun BudgetContent(context: Context, m: WidgetModels, bucket: Bucket) {
    val b = m.budget
    val budget = openApp(context, WidgetTarget.NewExpense)
    val thousand = b.table.firstOrNull { it.first == 1000L }
    WidgetFrame(if (bucket == Bucket.TINY) budget else null, padding = if (bucket == Bucket.TINY) 6 else 10) {
        if (bucket == Bucket.TINY) {
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                if (thousand == null) LabelText("Kein Kurs bekannt", maxLines = 2)
                else {
                    LabelText(yen(1000))
                    TitleText(eur(thousand.second), size = 14.sp)
                }
            }
            return@WidgetFrame
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                if (thousand == null) BodyText("Kein Kurs bekannt", GlanceModifier.tag("budget-rate"))
                else TitleText("${yen(1000)} = ${eur(thousand.second)}", GlanceModifier.tag("budget-rate"), size = 14.sp)
                BodyText("heute ${yen(b.todayJpy)}")
            }
            PillButton("+", budget, "budget-add")
        }
        if (bucket == Bucket.STRIP || bucket == Bucket.SMALL) {
            if (bucket == Bucket.SMALL) b.rate?.let { LabelText("Kurs vom ${shortDate(it.date)}") }
            return@WidgetFrame
        }
        if (b.table.isNotEmpty()) {
            Spacer(GlanceModifier.height(4.dp))
            Column(GlanceModifier.tag("budget-table")) {
                // Two columns side by side where there is width, else one below the other.
                b.table.chunked(if (bucket == Bucket.TALL) 1 else 2).forEach { row ->
                    Row(GlanceModifier.fillMaxWidth()) {
                        row.forEach { (y, e) -> LabelText("${yen(y)} = ${eur(e)}", GlanceModifier.defaultWeight().padding(end = 6.dp)) }
                    }
                }
            }
            b.rate?.let { LabelText("Kurs vom ${shortDate(it.date)}") }
        }
        if (bucket.big) {
            Spacer(GlanceModifier.height(6.dp))
            Column(GlanceModifier.clickable(openApp(context, WidgetTarget.Today))) {
                BodyText("Ausgaben gesamt ${eur(b.totalEur)}")
                b.topCategories.forEach { (label, v) -> LabelText("$label ${eur(v)}") }
            }
        }
    }
}
