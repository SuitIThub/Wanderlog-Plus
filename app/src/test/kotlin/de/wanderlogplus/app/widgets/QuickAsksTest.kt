package de.wanderlogplus.app.widgets

import de.wanderlogplus.app.testing.FakeChatBackend
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.shared.sync.ChatCard
import de.wanderlogplus.shared.sync.ChatInfo
import de.wanderlogplus.shared.sync.ChatLocation
import de.wanderlogplus.shared.sync.ChatMessage
import de.wanderlogplus.shared.sync.QuickAsk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class QuickAsksTest {
    @get:Rule val tmp = TemporaryFolder()
    private val trip = Fixtures.trip()
    private fun at(s: String) = LocalDateTime.parse(s)

    @Test fun freeTimeUntilTheNextStep() {
        val r = QuickAsks.request(QuickAsk.KIND_FOOD, trip, at("2027-04-02T10:00"))
        assertEquals("Wo kann ich jetzt gut essen?", r.question)
        assertEquals("10:00", r.quick.now)
        assertEquals("12:30", r.quick.freeUntil)
        assertEquals("Nudelküche Weizenhalm", r.quick.nextLabel)
        assertEquals(35.00, r.quick.nextLat!!, 0.001)
        // Where one probably is: the temple, running now.
        assertEquals(35.02, r.anchor!!.lat, 0.001)
    }

    @Test fun noFurtherStepNoFreeTime() {
        val r = QuickAsks.request(QuickAsk.KIND_SIGHT, trip, at("2027-04-02T20:00"))
        assertNull(r.quick.freeUntil)
        assertEquals("Was kann ich jetzt in der Nähe besichtigen?", r.question)
        assertNotNull(r.anchor) // the stay
    }

    private fun chat(vararg cards: ChatCard, role: String = ChatMessage.ROLE_ASSISTANT, text: String = "Geh zum **Tempel**.") = ChatInfo(
        "c1", "", 0, 0,
        messages = listOf(ChatMessage("u", ChatMessage.ROLE_USER, "?", 0), ChatMessage("a", role, text, 1, cards = cards.toList())),
    )

    @Test fun resultKeepsTheOrderWithImageAndDistance() {
        val from = ChatLocation(35.01, 135.76) // the stay
        val r = QuickAsks.result(
            "sight", chat(
                ChatCard(ChatCard.KIND_PLACE, "Alter Bergtempel", "s-tempel", subtitle = "Ruhig"),
                ChatCard(ChatCard.KIND_WEB, "Teehaus", url = "https://t.example", lat = 35.0, lng = 135.77),
                ChatCard(ChatCard.KIND_PLAN, "Plan"),
            ), from, trip, at = 5L, date = "2027-04-02",
        )
        assertEquals(QuickResult.DONE, r.state)
        assertEquals(listOf("Alter Bergtempel", "Teehaus"), r.cards.map { it.title })
        assertEquals("img-tempel", r.cards[0].imageKey)
        assertEquals("s-tempel", r.cards[0].savedPlaceId)
        val d = r.cards[0].distanceMeters!!
        assertTrue("$d", d in 1_800..2_300)
        assertNull(r.cards[1].imageKey)
        assertEquals("c1", r.chatId)
    }

    @Test fun resultWithoutCardsKeepsTheTextAndErrorsFail() {
        val text = QuickAsks.result("food", chat(text = "**Heute** ist alles zu."), null, trip, 1L, "2027-04-02")
        assertEquals(QuickResult.DONE, text.state)
        assertEquals("Heute ist alles zu.", text.text)
        assertEquals(QuickResult.FAILED, QuickAsks.result("food", chat(role = ChatMessage.ROLE_ERROR), null, trip, 1L, "2027-04-02").state)
    }

    private fun runner(backend: FakeChatBackend, timeout: Long = 5_000, located: ChatLocation? = null): Pair<QuickAskRunner, MutableList<QuickResult>> {
        val store = QuickStore(java.io.File(tmp.newFolder(), "q.json"))
        val written = mutableListOf<QuickResult>()
        var t = 0L
        val r = QuickAskRunner(
            QuickDeps(
                backend = backend,
                load = { WidgetInput(trip, at("2027-04-02T10:00"), null, null, null, null) },
                locate = { located },
                store = object : QuickSink {
                    override fun write(r: QuickResult) { written += r; store.write(r) }
                },
                update = {},
                pollMillis = 1,
                timeoutMillis = timeout,
                clock = { t++ },
            ),
        )
        return r to written
    }

    @Test fun runnerAsksAndStoresTheResult() = runBlocking {
        val backend = FakeChatBackend()
        val (r, written) = runner(backend)
        r.run(QuickAsk.KIND_FOOD)
        assertEquals(QuickResult.RUNNING, written.first().state)
        assertEquals(QuickResult.DONE, written.last().state)
        assertEquals("Alter Bergtempel", written.last().cards.first().title)
        val req = backend.sent.single()
        assertEquals(QuickAsk.KIND_FOOD, req.quick?.kind)
        assertEquals("12:30", req.quick?.freeUntil)
        assertNotNull(req.location) // the anchor when the position is unknown
    }

    @Test fun runnerTimesOutAndFailsOnErrors() = runBlocking {
        val (slow, w1) = runner(FakeChatBackend(neverAnswer = true), timeout = 20)
        slow.run(QuickAsk.KIND_SIGHT)
        assertEquals(QuickResult.FAILED, w1.last().state)
        assertEquals("c1", w1.last().chatId)
        val (broken, w2) = runner(FakeChatBackend(failCreate = IllegalStateException("offline")))
        broken.run(QuickAsk.KIND_SIGHT)
        assertEquals(QuickResult.FAILED, w2.last().state)
    }

    @Test fun aTapShowsTheSearchAtOnceThenStartsTheWork() = runBlocking {
        val events = mutableListOf<String>()
        startQuickAsk(
            QuickAsk.KIND_SIGHT, WidgetInput(trip, at("2027-04-02T10:00"), null, null, null, null),
            sink = { r -> events += "write:${r.state}:${r.kind}" },
            update = { events += "update" },
            enqueue = { k -> events += "enqueue:$k" },
            clock = { 7L },
        )
        assertEquals(listOf("write:RUNNING:sight", "update", "enqueue:sight"), events)
    }
}
