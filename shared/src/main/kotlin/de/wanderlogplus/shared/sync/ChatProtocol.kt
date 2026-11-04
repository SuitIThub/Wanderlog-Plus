package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable

/**
 * Where the chat was opened from: [screen] is one of [ChatScreens]; [date] (yyyy-MM-dd) for a day, [regionId] for a
 * region, [savedPlaceId] for a place. [label] is the app's short German description ("Tag 12.11. · Kyoto").
 */
@Serializable
data class ChatContext(
    val screen: String = ChatScreens.OTHER,
    val date: String? = null,
    val regionId: String? = null,
    val savedPlaceId: String? = null,
    val label: String? = null,
)

object ChatScreens {
    const val TODAY = "today"
    const val TRIP = "trip"
    const val DAY = "day"
    const val PLACES = "places"
    const val REGION = "region"
    const val PLACE = "place"
    const val LOGISTICS = "logistics"
    const val MORE = "more"
    const val OTHER = "other"
}

/** A card below an assistant answer: a saved place ([KIND_PLACE], opens its detail) or a web page ([KIND_WEB]). */
@Serializable
data class ChatCard(
    val kind: String,
    val title: String,
    val savedPlaceId: String? = null,
    val subtitle: String? = null,
    val url: String? = null,
    /** Where a web find is (looked up by the server); without it the find cannot be added to the trip. */
    val lat: Double? = null,
    val lng: Double? = null,
    val address: String? = null,
    /** A [de.wanderlogplus.shared.model.PlaceCategory] name for adding a web find. */
    val category: String? = null,
    /** The region a web find would be added to (the nearest stay's or the suggested day's). */
    val regionId: String? = null,
    /** The chat's planning suggestion: the day (yyyy-MM-dd) and the place (saved place or place id) to put it after. */
    val planDate: String? = null,
    val planAfter: String? = null,
    /** A whole day plan ([KIND_PLAN]). */
    val plan: ChatPlan? = null,
) {
    val canAdd: Boolean get() = kind == KIND_WEB && lat != null && lng != null

    companion object {
        const val KIND_PLACE = "place"
        const val KIND_WEB = "web"
        const val KIND_PLAN = "plan"
    }
}

/**
 * A proposed program for [date], in order. Every existing item of the day appears as [ChatPlanStep.KEEP] or
 * [ChatPlanStep.REMOVE]; [check] is the server's plan check of the result (German, short).
 */
@Serializable
data class ChatPlan(val date: String, val steps: List<ChatPlanStep>, val check: String? = null)

/**
 * One row of a [ChatPlan]: keep an existing program item ([itemId], maybe at a new [time]), add a place (a saved one,
 * or a web find with a location), or remove an item. [reason] is the chat's short note on the row.
 */
@Serializable
data class ChatPlanStep(
    val action: String,
    val title: String,
    val itemId: String? = null,
    val savedPlaceId: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val address: String? = null,
    val category: String? = null,
    /** "HH:mm" to set; null keeps the item's time (or none for a new one). */
    val time: String? = null,
    /** The item's time before the plan (for KEEP), to show what changes. */
    val oldTime: String? = null,
    val reason: String? = null,
) {
    companion object {
        const val KEEP = "keep"
        const val ADD = "add"
        const val REMOVE = "remove"
    }
}

/** One message; [role] is [ROLE_USER], [ROLE_ASSISTANT] or [ROLE_ERROR] (a failed answer, [text] is German). */
@Serializable
data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val at: Long,
    val context: ChatContext? = null,
    val cards: List<ChatCard> = emptyList(),
    /** Who wrote a user message (token name). */
    val author: String? = null,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_ERROR = "error"
    }
}

/** A chat with all its messages; [pending] while an answer is being worked on, [status] says what it does right now. */
@Serializable
data class ChatInfo(
    val id: String,
    val title: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val author: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pending: Boolean = false,
    val status: String? = null,
)

/** A chat in the list (`GET /api/chats`), newest activity first. */
@Serializable
data class ChatSummary(val id: String, val title: String, val updatedAt: Long, val author: String? = null, val preview: String = "", val pending: Boolean = false)

/** Body of `POST /api/chats/{id}/messages`. */
@Serializable
data class ChatSendRequest(val text: String, val context: ChatContext = ChatContext())
