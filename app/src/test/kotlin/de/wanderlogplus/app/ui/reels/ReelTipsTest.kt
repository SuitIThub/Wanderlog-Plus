package de.wanderlogplus.app.ui.reels

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.GuideAddition
import de.wanderlogplus.shared.sync.GuideRequest
import de.wanderlogplus.shared.sync.ReelTip
import de.wanderlogplus.shared.sync.TipVerdict
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class ReelTipsTest {
    @get:Rule val rule = createComposeRule()

    private val trueTip = ReelTip("Kein Trinkgeld geben.", "Geld", verdict = TipVerdict.TRUE, text = "Trinkgeld ist unüblich.", reason = "Belegt.", sources = listOf("https://a.jp"), guideId = "g1")
    private val partly = ReelTip("Überall nur Bargeld!", "Geld", verdict = TipVerdict.PARTLY, text = "Kleine Läden oft nur bar.", sources = listOf("https://b.jp"), guideId = "g1")
    private val newGuide = ReelTip("Rolltreppe: in Tokio links stehen.", "Verkehr", verdict = TipVerdict.TRUE, text = "In Tokio links stehen.", targetTitle = "Rolltreppen")
    private val wrong = ReelTip("Japan hat keine Mülleimer, Strafe 1000 €.", verdict = TipVerdict.FALSE, reason = "Erfunden.", targetTitle = "Müll")
    private val covered = ReelTip("Schuhe aus im Ryokan.", coveredBy = "g2", coveredQuote = "Schuhe aus")
    private val unverified = ReelTip("Konbini-Kaffee ist der beste.", verdict = TipVerdict.UNVERIFIED, text = "Konbini-Kaffee ist der beste.", targetTopic = "konbini")

    @Test fun selectableAndPreselected() {
        assertTrue(tipSelectable(trueTip) && tipPreselected(trueTip))
        assertTrue(tipSelectable(partly) && tipPreselected(partly))
        assertFalse(tipSelectable(wrong))
        assertFalse(tipSelectable(covered))
        assertTrue(tipSelectable(unverified))
        assertFalse(tipPreselected(unverified))
    }

    @Test fun asksAreGroupedByTarget() {
        val asks = tipAsks(listOf(trueTip, partly, newGuide, unverified))
        assertEquals(3, asks.size)
        val g1 = asks.single { it.guideId == "g1" }
        assertEquals(listOf(GuideAddition("Trinkgeld ist unüblich.", listOf("https://a.jp")), GuideAddition("Kleine Läden oft nur bar.", listOf("https://b.jp"))), g1.additions)
        assertEquals("Rolltreppen", asks.single { it.title == "Rolltreppen" }.title)
        assertEquals("konbini", asks.single { it.topic == "konbini" }.topic)
    }

    @Test fun textFallsBackToClaim() {
        assertEquals("x", tipAsks(listOf(ReelTip("x", verdict = TipVerdict.TRUE, targetTitle = "T"))).single().additions.single().text)
    }

    @Test fun tipsOnlyJobCanBeImported() {
        val job = AiJobInfo("j2", "reel", 0, "DONE", summary = "Tipps", tips = listOf(trueTip, wrong, unverified))
        var got: Pair<List<ReelPick>, List<ReelTip>>? = null
        rule.setContent { WanderlogPlusTheme { ReelJobScreen(job, Fixtures.trip(), imported = false, onImport = { p, t, _ -> got = p to t }, onOpenPlaces = {}, onBack = {}) } }
        rule.onNodeWithTag("reel-tip-0").assertIsOn()
        rule.onNodeWithTag("reel-tip-1").assertIsNotEnabled()
        rule.onNodeWithTag("reel-tip-2").assertIsOff()
        rule.onNodeWithText("falsch").assertExists()
        rule.onNodeWithText("1 Tipp übernehmen").assertExists()
        rule.onNodeWithTag("reel-import").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(emptyList<ReelPick>(), got!!.first)
        assertEquals(listOf(trueTip), got!!.second)
    }

    @Test fun importWithOnlyTipsStartsOneGuideJob() {
        val bodies = mutableListOf<String>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val api = SyncApi(HttpClient(MockEngine { req ->
            bodies += String(req.body.toByteArray())
            respond("""{"id":"g","kind":"guides","createdAt":0,"status":"QUEUED"}""", HttpStatusCode.OK, json)
        }), "https://s", "tok")
        val snap = Fixtures.trip()
        val edit: EditFn = { _ -> error("no place to write") }
        val imported = mutableSetOf<String>()
        val vm = ReelsViewModel({ MutableStateFlow<TripSnapshot?>(snap) }, edit, { api }, object : ImportedJobs {
            override fun contains(id: String) = id in imported
            override fun add(id: String) { imported += id }
        }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        vm.import("j2", emptyList(), listOf(trueTip, newGuide), null)
        val deadline = System.currentTimeMillis() + 5_000
        while ("j2" !in imported && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val req = Json { ignoreUnknownKeys = true }.decodeFromString(GuideRequest.serializer(), bodies.single())
        assertEquals(2, req.asks.size)
        assertTrue("j2" in imported)
    }

    @Test fun asksStayWithinServerLimits() {
        val big = ReelTip("x", verdict = TipVerdict.TRUE, text = "y".repeat(1500), sources = (1..30).map { "https://s$it.jp" }, targetTitle = "T")
        val a = tipAsks(listOf(big)).single().additions.single()
        assertEquals(1000, a.text.length)
        assertEquals(20, a.sources.size)
    }

    @Test fun failedGuideStartKeepsTheJobOpen() {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val api = SyncApi(HttpClient(MockEngine { respond("""{"error":"ai_not_configured"}""", HttpStatusCode.ServiceUnavailable, json) }), "https://s", "tok")
        var edits = 0
        val edit: EditFn = { _ -> edits++ }
        val imported = mutableSetOf<String>()
        val vm = ReelsViewModel({ MutableStateFlow<TripSnapshot?>(Fixtures.trip()) }, edit, { api }, object : ImportedJobs {
            override fun contains(id: String) = id in imported
            override fun add(id: String) { imported += id }
        }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val pick = ReelPick(de.wanderlogplus.shared.sync.ReelCandidate("Ort", lat = 35.0, lng = 135.0), null)
        vm.import("j3", listOf(pick), listOf(trueTip), null)
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.message.value == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse("j3" in imported)
        assertEquals(0, edits)
        assertTrue(vm.message.value!!.startsWith("Die Tipps konnten nicht übernommen werden"))
    }

    private val placeTip = ReelTip("Bei Ichiran montags zu.", verdict = TipVerdict.TRUE, text = "Montags geschlossen.", sources = listOf("https://c.jp"), placeIds = listOf("sp1"))
    private val savedCandidate = de.wanderlogplus.shared.sync.ReelCandidate("Ichiran", savedPlaceId = "sp1", reelFacts = listOf("Tonkotsu nehmen", "Früh kommen"))

    @Test fun placeTipsDoNotGoIntoGuides() {
        assertEquals(listOf("g1"), tipAsks(listOf(placeTip, trueTip)).map { it.guideId })
    }

    @Test fun placeFactsAreGroupedPerPlace() {
        val other = placeTip.copy(text = "Kura: Teller zählen.", placeIds = listOf("sp2"), sources = emptyList())
        val items = placeFactItems(listOf(placeTip, trueTip, other), listOf(savedCandidate))
        assertEquals(listOf("sp1", "sp2"), items.map { it.savedPlaceId })
        assertEquals(listOf("Montags geschlossen.", "Tonkotsu nehmen", "Früh kommen"), items[0].facts)
        assertEquals(listOf("https://c.jp"), items[0].sources)
    }

    @Test fun factsPerPlaceAreCapped() {
        val many = savedCandidate.copy(reelFacts = (1..15).map { "f$it" })
        assertEquals(12, placeFactItems(emptyList(), listOf(many)).single().facts.size)
    }

    @Test fun savedPlaceFactsCanBeImported() {
        val job = AiJobInfo("j5", "reel", 0, "DONE", summary = "Ramen", candidates = listOf(savedCandidate.copy(lat = 35.0, lng = 135.0)))
        var got: List<de.wanderlogplus.shared.sync.ReelCandidate>? = null
        rule.setContent { WanderlogPlusTheme { ReelJobScreen(job, Fixtures.trip(), imported = false, onImport = { _, _, f -> got = f }, onOpenPlaces = {}, onBack = {}) } }
        rule.onNodeWithText("Neu aus dem Reel").assertExists()
        rule.onNodeWithTag("reel-facts-0").assertIsOn()
        rule.onNodeWithTag("reel-import").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("sp1"), got!!.map { it.savedPlaceId })
    }

    @Test fun importStartsPlaceFactsJob() {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val api = SyncApi(HttpClient(MockEngine { req ->
            paths += req.url.encodedPath
            bodies += String(req.body.toByteArray())
            respond("""{"id":"x","kind":"place-facts","createdAt":0,"status":"QUEUED"}""", HttpStatusCode.OK, json)
        }), "https://s", "tok")
        val imported = mutableSetOf<String>()
        val vm = ReelsViewModel({ MutableStateFlow<TripSnapshot?>(Fixtures.trip()) }, { _ -> error("no write") }, { api }, object : ImportedJobs {
            override fun contains(id: String) = id in imported
            override fun add(id: String) { imported += id }
        }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        vm.import("j6", emptyList(), listOf(placeTip, trueTip), null, listOf(savedCandidate))
        val deadline = System.currentTimeMillis() + 5_000
        while ("j6" !in imported && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf("/api/ai/guides", "/api/ai/place-facts"), paths)
        val req = Json { ignoreUnknownKeys = true }.decodeFromString(de.wanderlogplus.shared.sync.PlaceFactsRequest.serializer(), bodies[1])
        assertEquals(3, req.items.single().facts.size)
    }
}
