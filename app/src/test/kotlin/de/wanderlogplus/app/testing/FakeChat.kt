package de.wanderlogplus.app.testing

import de.wanderlogplus.app.ui.chat.ChatBackend
import de.wanderlogplus.app.ui.chat.LastChat
import de.wanderlogplus.shared.sync.ChatCard
import de.wanderlogplus.shared.sync.ChatInfo
import de.wanderlogplus.shared.sync.ChatMessage
import de.wanderlogplus.shared.sync.ChatSendRequest
import de.wanderlogplus.shared.sync.ChatSummary

/** A chat server in memory: answers after [answerAfter] polls with [answerCards]; records what was sent. */
class FakeChatBackend(
    var answerAfter: Int = 1,
    var answerCards: List<ChatCard> = listOf(ChatCard(ChatCard.KIND_PLACE, "Alter Bergtempel", "s-tempel", subtitle = "Ruhig am Morgen")),
    var answerRole: String = ChatMessage.ROLE_ASSISTANT,
    var failCreate: Exception? = null,
    var neverAnswer: Boolean = false,
) : ChatBackend {
    val chats = LinkedHashMap<String, ChatInfo>()
    val sent = mutableListOf<ChatSendRequest>()
    var creates = 0
    private var polls = 0

    override suspend fun list() = chats.values.map { ChatSummary(it.id, it.title, it.updatedAt) }
    override suspend fun create(): ChatInfo {
        failCreate?.let { throw it }
        creates++
        return ChatInfo("c${chats.size + 1}", "", 0, 0).also { chats[it.id] = it }
    }
    override suspend fun get(id: String): ChatInfo {
        val c = chats[id] ?: error("not found")
        if (c.pending && !neverAnswer && ++polls >= answerAfter) {
            val done = c.copy(
                pending = false, status = null,
                messages = c.messages + ChatMessage("a${c.messages.size}", answerRole, "Geh zum **Tempel**.", 1, cards = answerCards),
            )
            chats[id] = done
            return done
        }
        return c
    }
    override suspend fun send(id: String, req: ChatSendRequest): ChatInfo {
        sent += req
        val c = chats.getValue(id)
        return c.copy(title = req.text, pending = true, messages = c.messages + ChatMessage("u${c.messages.size}", ChatMessage.ROLE_USER, req.text, 0, req.context))
            .also { chats[id] = it }
    }
    override suspend fun retry(id: String) = chats.getValue(id).copy(pending = true).also { chats[id] = it }
    override suspend fun delete(id: String) { chats.remove(id) }
}

class MemLastChat(var id: String? = null) : LastChat {
    override suspend fun get() = id
    override suspend fun set(id: String?) { this.id = id }
}
