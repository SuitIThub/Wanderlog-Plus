package de.wanderlogplus.server

import de.wanderlogplus.server.ai.AiFailed
import de.wanderlogplus.server.ai.ChatAgent
import de.wanderlogplus.server.ai.ToolChatModel
import de.wanderlogplus.server.ai.ToolTurn
import de.wanderlogplus.shared.sync.ChatInfo
import de.wanderlogplus.shared.sync.ChatMessage
import de.wanderlogplus.shared.sync.ChatSummary
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Answers "Antwort auf <text>"; fails on "kaputt"; waits for [gate] when set. */
    private class EchoModel(var gate: CountDownLatch? = null) : ToolChatModel {
        override fun turn(messages: List<kotlinx.serialization.json.JsonObject>, tools: kotlinx.serialization.json.JsonArray?): ToolTurn {
            gate?.await(5, TimeUnit.SECONDS)
            val last = messages.last()["content"].toString().trim('"')
            if ("kaputt" in last) throw AiFailed("ai_no_credit")
            val t = "Antwort auf $last"
            return ToolTurn(t, emptyList(), buildJsonObject { put("role", "assistant"); put("content", t) })
        }
    }

    private lateinit var chats: ChatService
    private lateinit var store: SqliteStore

    private fun ApplicationTestBuilder.start(model: ToolChatModel? = EchoModel()) {
        val dir = Files.createTempDirectory("wlp-chat")
        val base = testServices(dir)
        store = base.store
        chats = ChatService(base.store, model?.let { ChatAgent(it, null) { "2027-05-19" } }, executor = Executors.newSingleThreadExecutor())
        application {
            module(Services(base.config, base.store, base.sync, base.files, base.imports, base.routes, base.ai, base.transit, base.tokens, base.vault, chats))
        }
    }

    private suspend fun ApplicationTestBuilder.create(): ChatInfo {
        val r = client.post("/api/chats") { authed() }
        assertEquals(HttpStatusCode.Created, r.status)
        return json.decodeFromString(ChatInfo.serializer(), r.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.send(id: String, text: String, token: String = TOKEN_A) = client.post("/api/chats/$id/messages") {
        authed(token)
        contentType(ContentType.Application.Json)
        setBody("""{"text":"$text","context":{"screen":"day","date":"2027-05-19","label":"Tag 19.05."}}""")
    }

    private suspend fun ApplicationTestBuilder.get(id: String) = json.decodeFromString(ChatInfo.serializer(), client.get("/api/chats/$id") { authed() }.bodyAsText())

    @Test fun chatIsAnsweredInTheBackgroundAndSharedBetweenUsers() = testApplication {
        start()
        val chat = create()
        val sent = json.decodeFromString(ChatInfo.serializer(), send(chat.id, "Was tun?").bodyAsText())
        assertTrue(sent.pending)
        assertEquals("Was tun?", sent.title)
        chats.drain()
        val done = get(chat.id)
        assertEquals(false, done.pending)
        assertEquals(listOf(ChatMessage.ROLE_USER, ChatMessage.ROLE_ASSISTANT), done.messages.map { it.role })
        assertEquals("alice", done.messages[0].author)
        assertEquals("Tag 19.05.", done.messages[0].context?.label)
        assertTrue(done.messages[1].text.startsWith("Antwort auf [Geöffnet bei: Tag 19.05.] Was tun?"))

        // Bob sees and continues the same chat.
        val list = json.decodeFromString(ListSerializer(ChatSummary.serializer()), client.get("/api/chats") { authed(TOKEN_B) }.bodyAsText())
        assertEquals(listOf(chat.id), list.map { it.id })
        assertEquals(HttpStatusCode.OK, send(chat.id, "Und morgen?", TOKEN_B).status)
        chats.drain()
        assertEquals("bob", get(chat.id).messages[2].author)
    }

    @Test fun failedAnswerShowsErrorAndRetryRemovesIt() = testApplication {
        start()
        val chat = create()
        send(chat.id, "kaputt")
        chats.drain()
        val failed = get(chat.id)
        assertEquals(ChatMessage.ROLE_ERROR, failed.messages.last().role)
        assertEquals("Das OpenRouter-Guthaben ist aufgebraucht.", failed.messages.last().text)
        assertEquals(HttpStatusCode.OK, client.post("/api/chats/${chat.id}/retry") { authed() }.status)
        chats.drain()
        // Still failing, but only one error message at the end.
        assertEquals(listOf(ChatMessage.ROLE_USER, ChatMessage.ROLE_ERROR), get(chat.id).messages.map { it.role })
    }

    @Test fun busyChatRejectsMessagesAndDelete() = testApplication {
        val gate = CountDownLatch(1)
        start(EchoModel(gate))
        val chat = create()
        send(chat.id, "eins")
        assertEquals(HttpStatusCode.Conflict, send(chat.id, "zwei").status)
        assertEquals(HttpStatusCode.Conflict, client.delete("/api/chats/${chat.id}") { authed() }.status)
        gate.countDown()
        chats.drain()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/chats/${chat.id}") { authed() }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/chats/${chat.id}") { authed() }.status)
    }

    @Test fun referencesAreCleanedBeforeSaving() {
        val ok = de.wanderlogplus.shared.sync.ChatRef(de.wanderlogplus.shared.sync.ChatRef.PLACE, "s1", "Kiyomizu-dera")
        val refs = listOf(ok, ok, de.wanderlogplus.shared.sync.ChatRef("weird", "x", "X"), de.wanderlogplus.shared.sync.ChatRef(de.wanderlogplus.shared.sync.ChatRef.DAY, "d", "  "))
        assertEquals(listOf(ok), ChatService.cleanRefs(refs))
        val many = (1..30).map { de.wanderlogplus.shared.sync.ChatRef(de.wanderlogplus.shared.sync.ChatRef.GUIDE, "g$it", "G".repeat(200)) }
        val cleaned = ChatService.cleanRefs(many)
        assertEquals(12, cleaned.size)
        assertEquals(80, cleaned.first().label.length)
    }

    @Test fun badInputAndMissingKey() = testApplication {
        start(null)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/chats") { authed() }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/chats/nope") { authed() }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/chats").status)
    }

    @Test fun blankMessageIsRejectedAndRestartMarksPendingAsFailed() = testApplication {
        start()
        val chat = create()
        assertEquals(HttpStatusCode.BadRequest, send(chat.id, "   ").status)
        // A chat left pending by a crash ends with an error message.
        val stuck = chat.copy(pending = true, status = "Denke nach …")
        store.putChat(stuck.id, stuck.updatedAt, Json.encodeToString(ChatInfo.serializer(), stuck))
        chats.resumeInterrupted()
        val after = get(chat.id)
        assertEquals(false, after.pending)
        assertEquals("Der Server wurde neu gestartet, bevor die Antwort fertig war.", after.messages.last().text)
    }
}
