package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideSection
import de.wanderlogplus.shared.sync.ReelTip
import de.wanderlogplus.shared.sync.TipVerdict
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TipCheckTest {
    private val guides = listOf(
        Guide("g1", topic = "geld", title = "Geld & Bezahlen", summary = "Bar und Karte.", sections = listOf(GuideSection("Trinkgeld", "Unüblich.", donts = listOf("Trinkgeld geben")))),
        Guide("g2", title = "Rolltreppen"),
    )
    private val tip = FoundTip("In Japan gibt man kein Trinkgeld.", "Geld", "Folie 2")

    private fun model(match: String = """{"tips":[]}""", verify: String = """{"verdict":"TRUE","text":"Stimmt.","reason":"Belegt."}""") =
        object : ChatModel {
            val systems = mutableListOf<String>()
            val users = mutableListOf<String>()
            override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String {
                systems += system
                users += (parts.first() as AiPart.Text).text
                return if (system.contains("gleichst")) match else verify
            }
        }

    private val facts = FactSource { _, _ -> Facts(listOf(Fact("Trinkgeld ist unüblich.", listOf("https://a.jp"))), listOf("https://a.jp", "https://b.jp")) }

    @Test
    fun `match marks covered tips`() {
        val m = model("""{"tips":[{"i":0,"covered":true,"guideId":"g1","quote":"Trinkgeld geben"}]}""")
        val r = TipCheck(facts, m).match(listOf(tip), guides).single()
        assertEquals("g1", r.coveredBy)
        assertEquals("Trinkgeld geben", r.coveredQuote)
        assertTrue(m.users.single().contains("Geld & Bezahlen"))
    }

    @Test
    fun `match drops unknown targets`() {
        val r = TipCheck(facts, model("""{"tips":[{"i":0,"covered":false,"guideId":"nope"}]}""")).match(listOf(tip), guides).single()
        assertNull(r.coveredBy)
        assertNull(r.guideId)
        assertEquals("Geld", r.targetTitle)
    }

    @Test
    fun `covered with unknown guide counts as new`() {
        val r = TipCheck(facts, model("""{"tips":[{"i":0,"covered":true,"guideId":"nope"}]}""")).match(listOf(tip), guides).single()
        assertNull(r.coveredBy)
        assertEquals("Geld", r.targetTitle)
    }

    @Test
    fun `match keeps existing guide and catalog topic`() {
        val two = listOf(tip, FoundTip("Im Onsen keine Badehose.", "Onsen", ""), FoundTip("x", "", ""))
        val r = TipCheck(facts, model("""{"tips":[{"i":0,"guideId":"g1"},{"i":1,"topic":"onsen"},{"i":2,"topic":"gibtsnicht"}]}""")).match(two, guides)
        assertEquals("g1", r[0].guideId)
        assertEquals("onsen", r[1].targetTopic)
        assertNull(r[2].targetTopic)
        assertEquals("Reisetipps", r[2].targetTitle)
    }

    @Test
    fun `match failure makes every tip new`() {
        val broken = ChatModel { _, _, _ -> throw AiFailed("ai_unreachable") }
        val r = TipCheck(facts, broken).match(listOf(tip), guides)
        assertEquals("Geld", r.single().targetTitle)
    }

    @Test
    fun `verify parses verdict and takes sources from the facts`() {
        val r = TipCheck(facts, model(verify = """{"verdict":"PARTLY","text":"korrigiert","reason":"Teilweise."}""")).verify(ReelTip(tip.claim, targetTitle = "Geld"))
        assertEquals(TipVerdict.PARTLY, r.verdict)
        assertEquals("korrigiert", r.text)
        assertEquals("Teilweise.", r.reason)
        assertEquals(listOf("https://a.jp", "https://b.jp"), r.sources)
        assertEquals("Geld", r.targetTitle)
    }

    @Test
    fun `verify with unknown verdict or empty text`() {
        val r = TipCheck(facts, model(verify = """{"verdict":"MAYBE","text":"","reason":"?"}""")).verify(ReelTip(tip.claim))
        assertEquals(TipVerdict.UNVERIFIED, r.verdict)
        assertEquals(tip.claim, r.text)
    }

    @Test
    fun `verify failure is unverified`() {
        val failing = FactSource { _, _ -> throw AiFailed("research_timeout") }
        val r = TipCheck(failing, model()).verify(ReelTip(tip.claim))
        assertEquals(TipVerdict.UNVERIFIED, r.verdict)
        assertEquals("Prüfung fehlgeschlagen", r.reason)
    }

    @Test
    fun `checkAll skips covered tips`() {
        val calls = AtomicInteger()
        val counting = FactSource { q, s -> calls.incrementAndGet(); facts.facts(q, s) }
        val two = listOf(tip, FoundTip("Suica geht in Google Wallet.", "IC-Karte", ""))
        val r = TipCheck(counting, model("""{"tips":[{"i":0,"covered":true,"guideId":"g1"},{"i":1,"title":"IC-Karten"}]}""")).checkAll(two, guides)
        assertEquals(1, calls.get())
        assertNull(r[0].verdict)
        assertEquals(TipVerdict.TRUE, r[1].verdict)
        assertEquals("IC-Karten", r[1].targetTitle)
    }

    @Test
    fun `digest is short per guide`() {
        val long = Guide("g3", title = "Lang", sections = List(50) { GuideSection("Abschnitt $it", "x".repeat(200)) })
        val d = guideDigest(listOf(long))
        assertTrue(d.length <= 1600)
        assertTrue(d.contains("g3"))
    }

    @Test
    fun `no research means unverified without asking the model`() {
        val m = model(verify = """{"verdict":"TRUE","text":"x","reason":"y"}""")
        val r = TipCheck(FactSource { _, _ -> null }, m).verify(ReelTip(tip.claim))
        assertEquals(TipVerdict.UNVERIFIED, r.verdict)
        assertEquals("Keine Belege gefunden", r.reason)
        assertTrue(m.systems.isEmpty())
    }

    @Test
    fun `sources and text are capped`() {
        val many = FactSource { _, _ -> Facts(listOf(Fact("a")), (1..30).map { "https://s$it.jp" }) }
        val r = TipCheck(many, model(verify = """{"verdict":"TRUE","text":"${"x".repeat(1500)}","reason":"y"}""")).verify(ReelTip(tip.claim))
        assertEquals(MAX_TIP_SOURCES, r.sources.size)
        assertEquals(1000, r.text.length)
    }

    private val places = listOf(PlaceRef("sp1", "Ichiran", "Osaka"), PlaceRef("sp2", "Kura", "Osaka"), PlaceRef("sp3", "Mos", null))

    @Test
    fun `a tip about one place targets the place`() {
        val m = model("""{"tips":[{"i":0,"covered":false,"placeIds":["sp1"],"title":"Ichiran in Osaka"}]}""")
        val r = TipCheck(facts, m).match(listOf(tip), guides, places).single()
        assertEquals(listOf("sp1"), r.placeIds)
        assertNull(r.guideId); assertNull(r.targetTopic); assertNull(r.targetTitle)
    }

    @Test
    fun `unknown and extra place ids are dropped`() {
        val m = model("""{"tips":[{"i":0,"placeIds":["x","sp1","sp2","sp3"]},{"i":1,"placeIds":["x"]}]}""")
        val r = TipCheck(facts, m).match(listOf(tip, tip.copy(claim = "Anderes")), guides, places)
        assertEquals(listOf("sp1", "sp2"), r[0].placeIds)
        assertEquals(emptyList(), r[1].placeIds)
        assertEquals("Geld", r[1].targetTitle)
    }

    @Test
    fun `covered wins over a place`() {
        val m = model("""{"tips":[{"i":0,"covered":true,"guideId":"g1","quote":"q","placeIds":["sp1"]}]}""")
        val r = TipCheck(facts, m).match(listOf(tip), guides, places).single()
        assertEquals("g1", r.coveredBy)
        assertEquals(emptyList(), r.placeIds)
    }

    @Test
    fun `the prompt lists the trip's places and the guide rule`() {
        val m = model()
        TipCheck(facts, m).match(listOf(tip), guides, places)
        assertTrue("sp1 | Ichiran | Osaka" in m.users.single())
        assertTrue("nie für einen einzelnen Ort" in m.systems.single())
        assertTrue("kein Muster" in m.systems.single())
    }
}
