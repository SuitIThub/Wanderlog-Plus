package de.wanderlogplus.app.ui.chat

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.shared.sync.ChatCard
import de.wanderlogplus.shared.sync.ChatContext
import de.wanderlogplus.shared.sync.ChatInfo
import de.wanderlogplus.shared.sync.ChatMessage
import de.wanderlogplus.shared.sync.ChatScreens
import de.wanderlogplus.shared.sync.ChatSendRequest
import de.wanderlogplus.shared.sync.ChatSummary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class ChatTest {
    @get:Rule val rule = createComposeRule()

    /** Server stand-in: a chat answers after [answerAfter] polls. */
    private class FakeBackend(var answerAfter: Int = 1, var failCreate: Exception? = null) : ChatBackend {
        val chats = LinkedHashMap<String, ChatInfo>()
        val sent = mutableListOf<ChatSendRequest>()
        private var polls = 0
        override suspend fun list() = chats.values.map { ChatSummary(it.id, it.title, it.updatedAt) }
        override suspend fun create(): ChatInfo {
            failCreate?.let { throw it }
            return ChatInfo("c${chats.size + 1}", "", 0, 0).also { chats[it.id] = it }
        }
        override suspend fun get(id: String): ChatInfo {
            val c = chats[id] ?: throw ApiException(ApiErrorKind.BAD_REQUEST, "not_found", 404)
            if (c.pending && ++polls >= answerAfter) {
                val done = c.copy(
                    pending = false, status = null,
                    messages = c.messages + ChatMessage("a${c.messages.size}", ChatMessage.ROLE_ASSISTANT, "Nimm den **Tempel**.", 1, cards = listOf(ChatCard(ChatCard.KIND_PLACE, "Alter Bergtempel", "s-tempel"))),
                )
                chats[id] = done
                return done
            }
            return c
        }
        override suspend fun send(id: String, req: ChatSendRequest): ChatInfo {
            sent += req
            val c = chats.getValue(id)
            return c.copy(title = req.text, pending = true, status = "Durchsuche hinterlegte Vorschläge …", messages = c.messages + ChatMessage("u${c.messages.size}", ChatMessage.ROLE_USER, req.text, 0, req.context))
                .also { chats[id] = it }
        }
        override suspend fun retry(id: String) = chats.getValue(id).copy(pending = true).also { chats[id] = it }
        override suspend fun delete(id: String) { chats.remove(id) }
    }

    private class MemLast(var id: String? = null) : LastChat {
        override suspend fun get() = id
        override suspend fun set(id: String?) { this.id = id }
    }

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }

    @Test fun contextFollowsTheScreen() {
        val s = Fixtures.trip()
        val day = chatContextFor("trip/day/{date}", mapOf("date" to "2027-04-03"), s, "2027-04-01")
        // Day 3 is overridden to the lake region.
        assertEquals(ChatContext(ChatScreens.DAY, date = "2027-04-03", regionId = "r-see", label = "Tag Sa 03.04. · Seenland"), day)
        assertEquals("r-berg", chatContextFor("heute", emptyMap(), s, "2027-04-02").regionId)
        assertEquals("Heute · Bergstadt", chatContextFor("heute", emptyMap(), s, "2027-04-02").label)
        assertEquals("2027-04-05", chatContextFor("trip/day/{date}/map", mapOf("date" to "2027-04-05"), s, "x").date)
        val place = chatContextFor("orte/place/{savedPlaceId}", mapOf("savedPlaceId" to "s-nudel"), s, "x")
        assertEquals(ChatContext(ChatScreens.PLACE, savedPlaceId = "s-nudel", regionId = "r-berg", label = "Nudelküche Weizenhalm"), place)
        assertEquals("Region Küstenort", chatContextFor("orte/region/{regionKey}", mapOf("regionKey" to "r-kueste"), s, "x").label)
        assertEquals(ChatScreens.LOGISTICS, chatContextFor("logistik/{type}/{id}", emptyMap(), s, "x").screen)
        assertEquals(ChatScreens.OTHER, chatContextFor(null, emptyMap(), s, "x").screen)
    }

    @Test fun sendCreatesAChatWithContextAndPollsForTheAnswer() {
        val backend = FakeBackend(answerAfter = 2)
        val last = MemLast()
        val vm = ChatViewModel(backend, last, pollMillis = 10)
        val ctx = ChatContext(ChatScreens.DAY, date = "2027-04-02", label = "Tag Fr 02.04.")
        vm.openFrom(ctx)
        vm.send("  Was tun?  ")
        waitUntil { vm.chat.value?.pending == false && vm.chat.value?.messages?.size == 2 }
        assertEquals(listOf(ChatSendRequest("Was tun?", ctx)), backend.sent)
        assertEquals("c1", last.id)
        assertEquals("s-tempel", vm.chat.value!!.messages.last().cards.single().savedPlaceId)

        // Without context the next message goes out with an empty one; a new chat starts empty.
        vm.clearContext()
        vm.send("Und dann?")
        waitUntil { vm.chat.value?.messages?.size == 4 }
        assertEquals(ChatContext(), backend.sent.last().context)
        vm.newChat()
        waitUntil { last.id == null }
        assertNull(vm.chat.value)
    }

    @Test fun locationGoesWithMessagesOnlyWhenSwitchedOn() {
        val backend = FakeBackend(answerAfter = 1)
        var fix: de.wanderlogplus.shared.sync.ChatLocation? = de.wanderlogplus.shared.sync.ChatLocation(35.0, 135.7, 30)
        val vm = ChatViewModel(backend, MemLast(), pollMillis = 10, locator = { fix })
        assertEquals(true, vm.canShareLocation)
        vm.send("Ohne")
        waitUntil { backend.sent.size == 1 && vm.chat.value?.pending == false }
        assertNull(backend.sent.last().location)

        vm.setShareLocation(true)
        vm.send("Was ist hier?")
        waitUntil { backend.sent.size == 2 && vm.chat.value?.pending == false }
        assertEquals(de.wanderlogplus.shared.sync.ChatLocation(35.0, 135.7, 30), backend.sent.last().location)

        fix = null // no fix: sent without, and the user is told
        vm.send("Und jetzt?")
        waitUntil { backend.sent.size == 3 }
        assertNull(backend.sent.last().location)
        assertEquals("Standort gerade nicht verfügbar – ohne Standort gesendet.", vm.message.value)

        vm.locationDenied()
        assertEquals(false, vm.shareLocation.value)
        assertEquals("no locator: stays off", false, ChatViewModel(backend, MemLast()).also { it.setShareLocation(true) }.shareLocation.value)
    }

    @Test fun locationSwitchShowsItsState() {
        var on by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            WanderlogPlusTheme {
                ChatScreen(null, emptyList(), null, false, null, null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, shareLocation = on, onToggleLocation = { on = it })
            }
        }
        rule.onNodeWithTag("chat-location").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, on)
        rule.onNodeWithText("Frag, was hier in der Nähe geht …").assertExists()
    }

    @Test fun lastChatIsRestoredAndErrorsBecomeMessages() {
        val backend = FakeBackend()
        backend.chats["old"] = ChatInfo("old", "Alter Chat", 0, 0)
        val vm = ChatViewModel(backend, MemLast("old"), pollMillis = 10)
        vm.openFrom(null)
        waitUntil { vm.chat.value != null }
        assertEquals("Alter Chat", vm.chat.value!!.title)

        val failing = ChatViewModel(FakeBackend(failCreate = ApiException(ApiErrorKind.NOT_CONFIGURED, "ai_not_configured", 503)), MemLast(), pollMillis = 10)
        failing.send("Hallo")
        waitUntil { failing.message.value != null }
        assertEquals("Die KI ist auf dem Server nicht eingerichtet (OpenRouter-Key fehlt).", failing.message.value)
        assertEquals(false, failing.sending.value)
    }

    @Test fun screenShowsMessagesCardsAndOpensThem() {
        var opened: String? = null
        var url: String? = null
        var sentText: String? = null
        val chat = ChatInfo(
            "c1", "Regentag", 0, 0,
            messages = listOf(
                ChatMessage("u", ChatMessage.ROLE_USER, "Was bei Regen?", 0, ChatContext(label = "Tag Fr 02.04."), author = "daniel"),
                ChatMessage(
                    "a", ChatMessage.ROLE_ASSISTANT, "Zwei Ideen:", 1,
                    cards = listOf(
                        ChatCard(ChatCard.KIND_PLACE, "Alter Bergtempel", "s-tempel", "Bergstadt · Muss man sehen"),
                        ChatCard(ChatCard.KIND_WEB, "Museum Guide", url = "https://www.example.org/museum", subtitle = "Museen bei Regen"),
                    ),
                ),
            ),
            pending = true, status = "Websuche: Museen …",
        )
        rule.setContent {
            WanderlogPlusTheme {
                ChatScreen(
                    chat, emptyList(), ChatContext(label = "Tag Fr 02.04."), sending = false, message = null, snapshot = Fixtures.trip(),
                    onBack = {}, onSend = { sentText = it }, onRetry = {}, onClearContext = {}, onNewChat = {}, onShowList = {},
                    onSelect = {}, onDelete = {}, onConsumeMessage = {}, onOpenPlace = { opened = it }, onOpenUrl = { url = it },
                )
            }
        }
        rule.onNodeWithText("Was bei Regen?").assertExists()
        rule.onNodeWithText("daniel · Tag Fr 02.04.").assertExists()
        rule.onNodeWithText("Kontext: Tag Fr 02.04.").assertExists()
        rule.onNodeWithText("Websuche: Museen …").assertExists()
        rule.onNodeWithText("example.org · nicht hinterlegt").assertExists()
        rule.onNodeWithTag("chat-card-s-tempel").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("s-tempel", opened)
        rule.onNodeWithTag("chat-web-card").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("https://www.example.org/museum", url)
        // While an answer runs nothing can be sent.
        rule.onNodeWithTag("chat-input").performTextInput("Noch was")
        rule.onNodeWithTag("chat-send").assertIsNotEnabled()
        rule.onNodeWithTag("chat-send").performSemanticsAction(SemanticsActions.OnClick)
        assertNull(sentText)
    }

    private class RecordingActions : ChatActions {
        val calls = mutableListOf<String>()
        override fun planSaved(savedPlaceId: String, date: String, position: de.wanderlogplus.client.ItemPosition) { calls += "saved:$savedPlaceId@$date/${position.encode()}" }
        override fun planFound(card: ChatCard, date: String, position: de.wanderlogplus.client.ItemPosition) { calls += "found:${card.title}@$date/${position.encode()}" }
        override fun addFound(card: ChatCard) { calls += "add:${card.title}" }
        var plans = mutableListOf<Triple<String, List<de.wanderlogplus.client.PlanOp>, Set<String>>>()
        override fun applyPlan(date: String, ops: List<de.wanderlogplus.client.PlanOp>, remove: Set<String>, at: Long) { plans += Triple(date, ops, remove); calls += "plan:$date" }
        override fun restorePlan(date: String, at: Long) { calls += "restore:$date" }
    }

    @Test fun cardButtonsPlanAndAddWithTheChatsSuggestion() {
        val actions = RecordingActions()
        val chat = ChatInfo(
            "c1", "x", 0, 0,
            messages = listOf(
                ChatMessage(
                    "a", ChatMessage.ROLE_ASSISTANT, "Ideen:", 1,
                    cards = listOf(
                        // Suggested after the noodle kitchen on day 2.
                        ChatCard(ChatCard.KIND_PLACE, "Moosgarten", "s-garten", planDate = "2027-04-02", planAfter = "s-nudel"),
                        ChatCard(ChatCard.KIND_WEB, "Onsen am Fluss", url = "https://x.example/onsen", lat = 35.02, lng = 135.77, regionId = "r-berg"),
                        // At the noodle kitchen's spot: already saved, so no second "Zu Orten".
                        ChatCard(ChatCard.KIND_WEB, "Nudeln (Blog)", url = "https://x.example/nudel", lat = 35.0001, lng = 135.7501),
                        ChatCard(ChatCard.KIND_WEB, "Nur ein Link", url = "https://x.example/info"),
                    ),
                ),
            ),
        )
        rule.setContent {
            WanderlogPlusTheme {
                ChatScreen(
                    chat, emptyList(), ChatContext(date = "2027-04-03"), false, null, Fixtures.trip(),
                    {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, actions = actions,
                )
            }
        }
        rule.onNodeWithText("Vorschlag: Fr 02.04.").assertExists()
        rule.onNodeWithTag("chat-plan-a-0").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("plan-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("saved:s-garten@2027-04-02/after:i-3", actions.calls.last())

        // A web find: day from the chat's context, position chosen in the dialog.
        rule.onNodeWithTag("chat-plan-a-1").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("plan-pos-start").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("plan-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("found:Onsen am Fluss@2027-04-03/start", actions.calls.last())
        rule.onNodeWithTag("chat-add-a-1").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("add:Onsen am Fluss", actions.calls.last())

        rule.onNodeWithTag("chat-saved-a-2").assertExists()
        rule.onNodeWithTag("chat-add-a-2").assertDoesNotExist()
        rule.onNodeWithTag("chat-plan-a-2").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("plan-day-2027-04-05").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("plan-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("saved:s-nudel@2027-04-05/end", actions.calls.last())
        rule.onNodeWithText("Eingeplant: Fr 02.04.").assertExists() // the noodle kitchen is on day 2 already

        rule.onNodeWithTag("chat-plan-a-3").assertDoesNotExist()
        rule.onNodeWithTag("chat-add-a-3").assertDoesNotExist()
    }

    private val plan = de.wanderlogplus.shared.sync.ChatPlan(
        "2027-04-02",
        listOf(
            de.wanderlogplus.shared.sync.ChatPlanStep("add", "Moosgarten", savedPlaceId = "s-garten", time = "08:30", reason = "früh leer"),
            de.wanderlogplus.shared.sync.ChatPlanStep("keep", "Alter Bergtempel", itemId = "i-2", time = "10:00", oldTime = "09:00"),
            de.wanderlogplus.shared.sync.ChatPlanStep("remove", "Nudelküche Weizenhalm", itemId = "i-3", oldTime = "12:30", reason = "zu voll"),
            de.wanderlogplus.shared.sync.ChatPlanStep("keep", "Notiz: Tickets", itemId = "i-1"),
            de.wanderlogplus.shared.sync.ChatPlanStep("add", "Onsen am Fluss", lat = 35.0, lng = 135.7, category = "SIGHT", time = "17:00"),
        ),
        check = "- Moosgarten öffnet erst ca. 09:00.",
    )

    @Test fun planOpsFollowTheCheckedRows() {
        val all = planOps(plan, setOf(0, 1, 2, 4))
        assertEquals(
            listOf(
                de.wanderlogplus.client.PlanOp.AddSaved("s-garten", "08:30"),
                de.wanderlogplus.client.PlanOp.Keep("i-2", "10:00"),
                de.wanderlogplus.client.PlanOp.Keep("i-1"),
                de.wanderlogplus.client.PlanOp.AddFound("Onsen am Fluss", 35.0, 135.7, null, de.wanderlogplus.shared.model.PlaceCategory.SIGHT, "17:00", "Aus dem Reise-Chat"),
            ),
            all.first,
        )
        assertEquals(setOf("i-3"), all.second)
        // Rejected: the removal (item stays at its row), the new time and the web find.
        val few = planOps(plan, setOf(0))
        assertEquals(
            listOf(
                de.wanderlogplus.client.PlanOp.AddSaved("s-garten", "08:30"),
                de.wanderlogplus.client.PlanOp.Keep("i-2", null),
                de.wanderlogplus.client.PlanOp.Keep("i-3"),
                de.wanderlogplus.client.PlanOp.Keep("i-1"),
            ),
            few.first,
        )
        assertEquals(emptySet<String>(), few.second)
    }

    @Test fun planCardMarksRemovalsAppliesAndResets() {
        val actions = RecordingActions()
        val chat = ChatInfo("c1", "x", 0, 0, messages = listOf(
            ChatMessage("a", ChatMessage.ROLE_ASSISTANT, "So ginge es:", 1, cards = listOf(ChatCard(ChatCard.KIND_PLAN, "Plan für Freitag, 02.04.2027", plan = plan))),
        ))
        rule.setContent {
            WanderlogPlusTheme {
                ChatScreen(chat, emptyList(), null, false, null, Fixtures.trip(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, actions = actions)
            }
        }
        rule.onNodeWithText("Entfällt").assertExists()
        rule.onNodeWithText("09:00 → 10:00").assertExists()
        rule.onNodeWithText("Plan-Check:\n- Moosgarten öffnet erst ca. 09:00.").assertExists()
        rule.onNodeWithText("Plan übernehmen (4 Änderungen)").assertExists()
        // Keep the noodle kitchen after all.
        rule.onNodeWithTag("plan-step-a-0-2-check").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("bleibt").assertExists()
        rule.onNodeWithText("Entfällt").assertDoesNotExist()
        rule.onNodeWithTag("plan-apply-a-0").performSemanticsAction(SemanticsActions.OnClick)
        val (date, ops, remove) = actions.plans.single()
        assertEquals("2027-04-02", date)
        assertEquals(emptySet<String>(), remove)
        assertEquals(de.wanderlogplus.client.PlanOp.Keep("i-3"), ops[2])
        rule.onNodeWithText("Übernommen").assertExists()
        rule.onNodeWithTag("plan-reset-a-0").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("restore:2027-04-02", actions.calls.last())
        rule.onNodeWithTag("plan-apply-a-0").assertExists()
    }

    @Test fun errorOffersRetryAndEmptyChatOffersExamples() {
        var retried = 0
        var sentText: String? = null
        val failed = ChatInfo("c1", "x", 0, 0, messages = listOf(
            ChatMessage("u", ChatMessage.ROLE_USER, "Hallo", 0),
            ChatMessage("e", ChatMessage.ROLE_ERROR, "Die KI ist gerade nicht erreichbar.", 1),
        ))
        var current by androidx.compose.runtime.mutableStateOf<ChatInfo?>(failed)
        rule.setContent {
            WanderlogPlusTheme {
                ChatScreen(
                    current, emptyList(), null, false, null, null, {}, { sentText = it }, { retried++ }, {}, {}, {}, {}, {}, {}, {}, {},
                )
            }
        }
        rule.onNodeWithTag("chat-retry").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, retried)
        current = null
        rule.onNodeWithText(CHAT_EXAMPLES.first()).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(CHAT_EXAMPLES.first(), sentText)
    }
}
