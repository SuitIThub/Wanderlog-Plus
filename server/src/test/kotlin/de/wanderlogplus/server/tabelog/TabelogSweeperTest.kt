package de.wanderlogplus.server.tabelog

import de.wanderlogplus.server.ServerEdit
import de.wanderlogplus.server.ai.AiPart
import de.wanderlogplus.server.ai.ChatModel
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.TabelogInfo
import de.wanderlogplus.shared.model.TabelogStatus
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import de.wanderlogplus.shared.sync.encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabelogSweeperTest {
    private val store = InMemoryEntityStore()

    private fun put(type: EntityType, id: String, fields: Map<String, kotlinx.serialization.json.JsonElement>) =
        store.put(EntityRecord(type, id, fields.mapValues { (_, v) -> FieldValue(v, Hlc(1, 0, "a").encode()) }))

    private fun restaurant(id: String, category: PlaceCategory = PlaceCategory.FOOD, status: PlaceStatus = PlaceStatus.IDEA, lat: Double = 35.0, tabelog: TabelogInfo? = null) {
        put(EntityType.PLACE, "p-$id", EntityCodec.fields(Place("p-$id", name = "Lokal $id", lat = lat, lng = if (lat == 0.0) 0.0 else 135.0, tabelog = tabelog)))
        put(EntityType.SAVED_PLACE, "s-$id", EntityCodec.fields(SavedPlace("s-$id", "p-$id", category = category, status = status)))
    }

    private fun hit(id: String, lat: Double = 35.0) = TabelogHit(id, "店 $id", "https://tabelog.com/x/$id/", 3.5, 20, "居酒屋", lat, 135.0, "", "￥3,000～￥3,999", "17:00 - 23:00", "予約可", "駅")

    private inner class FakeSource(val failOn: Int = -1) : TabelogSource {
        val searches = mutableListOf<String>()
        val details = mutableListOf<String>()
        override fun search(keyword: String, area: String): List<TabelogHit> {
            searches += keyword
            if (searches.size == failOn) throw TabelogUnavailable("apify_402")
            return listOf(hit(keyword.removePrefix("店 ")))
        }
        override fun details(url: String): Pair<TabelogHit?, List<TabelogReview>> {
            details += url
            val id = url.trimEnd('/').substringAfterLast('/')
            return hit(id) to listOf(TabelogReview("おいしい", "とてもおいしい", "2026/10"))
        }
    }

    // The Japanese name is "店 <id>" so the fake search can answer with a matching hit.
    private val names = JaNameSource { p -> JaName("店 " + p.id.removePrefix("p-"), "kyoto") }
    private val summary = TabelogSummary(ChatModel { _, _, _ -> """{"summary":"Gelobt wird die Brühe."}""" })
    private val written = mutableListOf<ServerEdit>()

    private fun sweeper(source: TabelogSource, batch: Int = 20, n: JaNameSource = names) =
        TabelogSweeper(store, source, n, summary, write = { edits -> written += edits; edits.forEach { e -> applyEdit(e) }; edits.size }, now = { 42 }, batch = batch)

    private fun applyEdit(e: ServerEdit) {
        val rec = store.get(e.type, e.id)!!
        store.put(rec.copy(fields = rec.fields + e.fields.mapValues { (_, v) -> FieldValue(v, Hlc(2, 0, "s").encode()) }))
    }

    private fun tabelogOf(id: String) = EntityCodec.decode<Place>(store.get(EntityType.PLACE, "p-$id")!!).tabelog

    @Test
    fun `only restaurants without data are fetched`() {
        restaurant("a")
        restaurant("bar", category = PlaceCategory.NIGHTLIFE)
        restaurant("sight", category = PlaceCategory.SIGHT)
        restaurant("dropped", status = PlaceStatus.DROPPED)
        restaurant("nopos", lat = 0.0)
        restaurant("done", tabelog = TabelogInfo(TabelogStatus.FOUND, "https://tabelog.com/x/done/"))
        restaurant("missing", tabelog = TabelogInfo(TabelogStatus.NOT_FOUND))
        val src = FakeSource()
        assertEquals(2, sweeper(src).sweep())
        assertEquals(listOf("店 a", "店 bar"), src.searches)
        val a = tabelogOf("a")!!
        assertEquals(TabelogStatus.FOUND, a.status)
        assertEquals(3.5, a.score)
        assertEquals("Gelobt wird die Brühe.", a.summary)
        assertEquals("https://tabelog.com/x/a/", a.url)
        assertEquals(42, a.checkedAt)
        assertTrue(written.all { it.type == EntityType.PLACE && it.fields.keys == setOf("tabelog") })
    }

    @Test
    fun `a link set by hand is fetched directly`() {
        restaurant("h", tabelog = TabelogInfo(TabelogStatus.REQUESTED, "https://tabelog.com/x/real/"))
        val src = FakeSource()
        sweeper(src).sweep()
        assertEquals(emptyList(), src.searches)
        assertEquals(listOf("https://tabelog.com/x/real/"), src.details)
        assertEquals("https://tabelog.com/x/real/", tabelogOf("h")!!.url)
    }

    @Test
    fun `a foreign link is ignored and searched normally`() {
        restaurant("e", tabelog = TabelogInfo(TabelogStatus.REQUESTED, "https://evil.jp/x/"))
        val src = FakeSource()
        sweeper(src).sweep()
        assertEquals(listOf("店 e"), src.searches)
        assertTrue(src.details.none { "evil" in it })
    }

    @Test
    fun `requested ones go first and the batch is limited`() {
        restaurant("a"); restaurant("b")
        restaurant("r", tabelog = TabelogInfo(TabelogStatus.REQUESTED))
        val src = FakeSource()
        assertEquals(2, sweeper(src, batch = 2).sweep())
        assertEquals(listOf("店 r", "店 a"), src.searches)
    }

    @Test
    fun `two saved places of one place are fetched once`() {
        restaurant("a")
        put(EntityType.SAVED_PLACE, "s-a2", EntityCodec.fields(SavedPlace("s-a2", "p-a", category = PlaceCategory.FOOD)))
        val src = FakeSource()
        sweeper(src).sweep()
        assertEquals(1, src.searches.size)
    }

    @Test
    fun `apify failing stops the sweep without writing`() {
        restaurant("a"); restaurant("b"); restaurant("c")
        val src = FakeSource(failOn = 2)
        assertEquals(1, sweeper(src).sweep())
        assertEquals(listOf("店 a", "店 b"), src.searches)
        assertEquals(null, tabelogOf("b"))
        assertEquals(null, tabelogOf("c"))
    }

    @Test
    fun `no name or no match is not found`() {
        restaurant("a"); restaurant("far")
        val src = object : TabelogSource {
            override fun search(keyword: String, area: String) = listOf(hit("far", lat = 35.1))
            override fun details(url: String) = error("no details")
        }
        val n = JaNameSource { p -> if (p.id == "p-a") null else JaName("店 far", "kyoto") }
        assertEquals(2, sweeper(src, n = n).sweep())
        assertEquals(TabelogStatus.NOT_FOUND, tabelogOf("a")!!.status)
        assertEquals(TabelogStatus.NOT_FOUND, tabelogOf("far")!!.status)
    }

    @Test
    fun `summary failure keeps the rest`() {
        val failing = TabelogSummary(ChatModel { _, _, _ -> throw RuntimeException("down") })
        assertEquals("", failing.summarize(listOf(TabelogReview("t", "x", ""))))
        assertEquals("", summary.summarize(emptyList()))
        val asked = mutableListOf<String>()
        val s = TabelogSummary(ChatModel { _, parts, _ -> asked += (parts.single() as AiPart.Text).text; """{"summary":"Gut."}""" })
        assertEquals("Gut.", s.summarize(listOf(TabelogReview("最高", "とてもおいしい", "2026/10"))))
        assertTrue("とてもおいしい" in asked.single())
    }
}
