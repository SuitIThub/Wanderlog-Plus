package de.wanderlogplus.server

import de.wanderlogplus.server.ai.AiFailed
import de.wanderlogplus.server.ai.AiNotConfigured
import de.wanderlogplus.server.ai.ChatAgent
import de.wanderlogplus.server.ai.ChatTrip
import de.wanderlogplus.shared.sync.ChatInfo
import de.wanderlogplus.shared.sync.ChatMessage
import de.wanderlogplus.shared.sync.ChatSendRequest
import de.wanderlogplus.shared.sync.ChatSummary
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** An answer is still being worked on; a new message has to wait. */
class ChatBusy : RuntimeException("chat_busy")

/** No chat with that id. */
class ChatNotFound : RuntimeException("chat_not_found")

/**
 * The trip chats: kept on the server (shared by all devices and travellers), answered in the background by the
 * [agent] (null without OpenRouter key). While an answer runs the chat is `pending` and `status` tells what it does.
 */
class ChatService(
    private val store: SqliteStore,
    private val agent: ChatAgent?,
    private val now: () -> Instant = Instant::now,
    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "chat").apply { isDaemon = true } },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val log = LoggerFactory.getLogger(ChatService::class.java)
    private val lock = Any()

    val configured: Boolean get() = agent != null

    fun list(limit: Int): List<ChatSummary> = store.chats(limit).map { decode(it) }.map { c ->
        val last = c.messages.lastOrNull()
        ChatSummary(c.id, c.title, c.updatedAt, c.author, last?.text?.replace(Regex("\\s+"), " ")?.take(120).orEmpty(), c.pending)
    }

    fun get(id: String): ChatInfo? = store.chat(id)?.let { decode(it) }

    fun create(author: String): ChatInfo {
        val t = now().toEpochMilli()
        return save(ChatInfo(UUID.randomUUID().toString(), "", t, t, author))
    }

    fun delete(id: String) = synchronized(lock) {
        val chat = get(id) ?: throw ChatNotFound()
        if (chat.pending) throw ChatBusy()
        store.deleteChat(id)
    }

    /** Adds the user's message and starts the answer; returns the chat with `pending = true`. */
    fun send(id: String, author: String, req: ChatSendRequest): ChatInfo {
        if (agent == null) throw AiNotConfigured()
        val text = req.text.trim()
        if (text.isEmpty() || text.length > MAX_TEXT) throw BadInput()
        val started = synchronized(lock) {
            val chat = get(id) ?: throw ChatNotFound()
            if (chat.pending) throw ChatBusy()
            val t = now().toEpochMilli()
            val msg = ChatMessage(UUID.randomUUID().toString(), ChatMessage.ROLE_USER, text, t, req.context, author = author)
            save(
                chat.copy(
                    title = chat.title.ifBlank { titleFrom(text) },
                    updatedAt = t,
                    messages = (chat.messages + msg).takeLast(MAX_MESSAGES),
                    pending = true,
                    status = "Wartet auf einen freien Platz …",
                ),
            )
        }
        executor.execute { runAnswer(id) }
        return started
    }

    /** Answers the last user message again after a failed answer (the error message is removed). */
    fun retry(id: String): ChatInfo {
        if (agent == null) throw AiNotConfigured()
        val started = synchronized(lock) {
            val chat = get(id) ?: throw ChatNotFound()
            if (chat.pending) throw ChatBusy()
            val messages = chat.messages.dropLastWhile { it.role == ChatMessage.ROLE_ERROR }
            if (messages.lastOrNull()?.role != ChatMessage.ROLE_USER) throw BadInput()
            save(chat.copy(messages = messages, pending = true, status = "Wartet auf einen freien Platz …", updatedAt = now().toEpochMilli()))
        }
        executor.execute { runAnswer(id) }
        return started
    }

    /** Answers cut off by a restart end with an error message. Call once at startup. */
    fun resumeInterrupted() {
        store.chats(200).map { decode(it) }.filter { it.pending }.forEach { finish(it.id, errorMessage("interrupted")) }
    }

    private fun runAnswer(id: String) {
        val a = agent ?: return
        val chat = get(id) ?: return
        val reply = try {
            val answer = a.answer(chat.messages, ChatTrip.from(store)) { line -> status(id, line) }
            ChatMessage(UUID.randomUUID().toString(), ChatMessage.ROLE_ASSISTANT, answer.text, now().toEpochMilli(), cards = answer.cards)
        } catch (e: AiFailed) {
            errorMessage(e.code)
        } catch (e: Exception) {
            log.error("Chat answer failed: {}", e.javaClass.name)
            errorMessage("internal")
        }
        finish(id, reply)
    }

    private fun status(id: String, line: String) = synchronized(lock) {
        get(id)?.takeIf { it.pending }?.let { save(it.copy(status = line)) }
    }

    private fun finish(id: String, reply: ChatMessage) = synchronized(lock) {
        val chat = get(id) ?: return@synchronized
        save(chat.copy(messages = (chat.messages + reply).takeLast(MAX_MESSAGES), pending = false, status = null, updatedAt = reply.at))
    }

    private fun errorMessage(code: String) = ChatMessage(UUID.randomUUID().toString(), ChatMessage.ROLE_ERROR, errorText(code), now().toEpochMilli())

    private fun save(c: ChatInfo): ChatInfo {
        store.putChat(c.id, c.updatedAt, json.encodeToString(ChatInfo.serializer(), c))
        return c
    }

    private fun decode(s: String) = json.decodeFromString(ChatInfo.serializer(), s)

    /** Waits until queued answers are done (tests). */
    internal fun drain() {
        executor.submit {}.get()
        executor.submit {}.get()
    }

    companion object {
        const val MAX_TEXT = 4000
        const val MAX_MESSAGES = 200

        fun titleFrom(text: String): String {
            val line = text.lineSequence().first().trim()
            return if (line.length <= 48) line else line.take(47).trimEnd() + "…"
        }

        fun errorText(code: String): String = when (code) {
            "ai_key_rejected" -> "Der OpenRouter-Key wurde abgelehnt."
            "ai_no_credit" -> "Das OpenRouter-Guthaben ist aufgebraucht."
            "ai_unreachable" -> "Die KI ist gerade nicht erreichbar."
            "ai_bad_answer" -> "Die KI hat keine brauchbare Antwort geliefert."
            "interrupted" -> "Der Server wurde neu gestartet, bevor die Antwort fertig war."
            else -> "Die Antwort ist fehlgeschlagen."
        }
    }
}
