package de.wanderlogplus.app.ui.chat

import de.wanderlogplus.app.ui.dayCardLabel
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.ChatContext
import de.wanderlogplus.shared.sync.ChatScreens
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

const val CHAT_ROUTE = "chat"

/**
 * The chat context for the screen at [route] (a nav route pattern) with its [args]: the day of a day screen, the
 * region of a region screen, the place of a place screen, else just which tab it is. [today] is the trip-zone date.
 */
fun chatContextFor(route: String?, args: Map<String, String?>, snapshot: TripSnapshot?, today: String): ChatContext {
    val r = route.orEmpty()
    fun dayContext(screen: String, date: String, prefix: String): ChatContext {
        val regionId = snapshot?.queries()?.regionFor(date)
        val regionName = regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id }?.name }
        return ChatContext(screen, date = date, regionId = regionId, label = listOfNotNull(prefix, regionName).joinToString(" · "))
    }
    return when {
        r == "heute" -> dayContext(ChatScreens.TODAY, today, "Heute")
        r.startsWith("trip/day/{date}") -> args["date"]?.takeIf { it.isNotBlank() }?.let { dayContext(ChatScreens.DAY, it, "Tag " + dayCardLabel(it)) }
            ?: ChatContext(ChatScreens.TRIP, label = "Reise")
        r == "trip/place/{savedPlaceId}" || r.startsWith("orte/place/{savedPlaceId}") -> {
            val id = args["savedPlaceId"].orEmpty()
            val saved = snapshot?.savedPlaces?.firstOrNull { it.id == id }
            val name = saved?.let { snapshot.places[it.placeId]?.name }
            ChatContext(ChatScreens.PLACE, savedPlaceId = id.ifEmpty { null }, regionId = saved?.regionId, label = name ?: "Ort")
        }
        r.startsWith("orte/region/{regionKey}") || r.startsWith("orte/search/{regionKey}") -> {
            val key = args["regionKey"].orEmpty()
            val region = snapshot?.regions?.firstOrNull { it.id == key }
            if (region == null) ChatContext(ChatScreens.REGION, label = "Ohne Region")
            else ChatContext(ChatScreens.REGION, regionId = region.id, label = "Region " + region.name)
        }
        r == "reise" -> ChatContext(ChatScreens.TRIP, label = "Reiseübersicht")
        r == "orte" -> ChatContext(ChatScreens.PLACES, label = "Orte")
        r.startsWith("logistik") -> ChatContext(ChatScreens.LOGISTICS, label = "Logistik")
        r.startsWith("mehr") -> ChatContext(ChatScreens.MORE)
        else -> ChatContext(ChatScreens.OTHER)
    }
}

/** The detail a two-pane screen shows beside its list: [route] pattern and [args] as if it were open on its own. */
data class ChatPane(val owner: String, val route: String, val args: Map<String, String?>)

/** Holds the [ChatPane] of the screen on display, so the chat opens with the detail's context, not the list's. */
class ChatPaneHolder {
    var pane: ChatPane? by androidx.compose.runtime.mutableStateOf(null)

    /** The pane of [current] (the nav route on display), or null. */
    fun paneFor(current: String?): ChatPane? = pane?.takeIf { it.owner == current }
}

val LocalChatPane = androidx.compose.runtime.staticCompositionLocalOf<ChatPaneHolder?> { null }

/** Reports, while composed, that the screen [owner] shows the detail [route] with [args] in its detail pane. */
@androidx.compose.runtime.Composable
fun ReportChatPane(owner: String, route: String, args: Map<String, String?>) {
    val holder = LocalChatPane.current ?: return
    val pane = ChatPane(owner, route, args)
    androidx.compose.runtime.DisposableEffect(holder, pane) {
        holder.pane = pane
        onDispose { if (holder.pane == pane) holder.pane = null }
    }
}
