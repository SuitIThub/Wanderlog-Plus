package de.wanderlogplus.app.widgets

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.QuickAsk

/** Intent extra of [de.wanderlogplus.app.ui.MainActivity] with an encoded [WidgetTarget]. */
const val EXTRA_WIDGET_TARGET = "wlp.widget.target"

/** Where a widget tap leads in the app; [encode]/[parse] carry it as a string through the intent. */
sealed interface WidgetTarget {
    data object Today : WidgetTarget
    data class Day(val date: String) : WidgetTarget
    data class Logistics(val type: EntityType, val id: String) : WidgetTarget
    data class Attachment(val id: String) : WidgetTarget
    data object NewExpense : WidgetTarget
    data class StayAddress(val stayId: String) : WidgetTarget
    data class Place(val savedPlaceId: String) : WidgetTarget
    data class Chat(val chatId: String?) : WidgetTarget
    data class Ask(val kind: String) : WidgetTarget

    fun encode(): String = when (this) {
        Today -> "today"
        is Day -> "day:$date"
        is Logistics -> "logistics:${type.name}:$id"
        is Attachment -> "attachment:$id"
        NewExpense -> "expense:new"
        is StayAddress -> "stay-address:$stayId"
        is Place -> "place:$savedPlaceId"
        is Chat -> if (chatId == null) "chat" else "chat:$chatId"
        is Ask -> "ask:$kind"
    }

    companion object {
        private val LOGISTICS = setOf(EntityType.TRAIN, EntityType.FLIGHT, EntityType.STAY)

        fun parse(s: String?): WidgetTarget? {
            if (s == null) return null
            val head = s.substringBefore(':')
            val rest = s.substringAfter(':', "").takeIf { it.isNotEmpty() }
            return when (head) {
                "today" -> Today
                "expense" -> NewExpense.takeIf { rest == "new" }
                "chat" -> Chat(rest)
                "day" -> rest?.let { Day(it) }
                "attachment" -> rest?.let { Attachment(it) }
                "stay-address" -> rest?.let { StayAddress(it) }
                "place" -> rest?.let { Place(it) }
                "ask" -> rest?.takeIf { it == QuickAsk.KIND_SIGHT || it == QuickAsk.KIND_FOOD }?.let { Ask(it) }
                "logistics" -> rest?.let { r ->
                    val type = runCatching { EntityType.valueOf(r.substringBefore(':')) }.getOrNull()?.takeIf { it in LOGISTICS }
                    val id = r.substringAfter(':', "").takeIf { it.isNotEmpty() }
                    if (type != null && id != null) Logistics(type, id) else null
                }
                else -> null
            }
        }
    }
}

/** The intent that opens the app at [target]. */
fun widgetIntent(context: Context, target: WidgetTarget): Intent =
    Intent().setComponent(ComponentName(context.packageName, "de.wanderlogplus.app.ui.MainActivity"))
        .setAction(ACTION_WIDGET)
        .putExtra(EXTRA_WIDGET_TARGET, target.encode())
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        // A distinct data URI per target: otherwise Android merges the pending intents of different buttons.
        .setData(android.net.Uri.parse("wlp-widget://open/" + android.net.Uri.encode(target.encode())))

const val ACTION_WIDGET = "de.wanderlogplus.app.WIDGET"

/** Glance action that opens the app at [target]. */
fun openApp(context: Context, target: WidgetTarget): Action = actionStartActivity(widgetIntent(context, target))
