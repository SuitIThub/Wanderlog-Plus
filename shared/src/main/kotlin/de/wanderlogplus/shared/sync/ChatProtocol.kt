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
) {
    val canAdd: Boolean get() = kind == KIND_WEB && lat != null && lng != null

    companion object {
        const val KIND_PLACE = "place"
        const val KIND_WEB = "web"
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
