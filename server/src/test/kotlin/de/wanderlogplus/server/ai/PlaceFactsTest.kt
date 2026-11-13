package de.wanderlogplus.server.ai

import de.wanderlogplus.server.ASSISTANT_AUTHOR
import de.wanderlogplus.server.ServerEdit
import de.wanderlogplus.server.SqliteStore
import de.wanderlogplus.server.SyncService
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.sync.AssessConfig
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.PlaceFactsItem
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceFactsTest {
    private class Scripted(private val answer: (String) -> String) : ChatModel {
        val systems = mutableListOf<String>()
        val users = mutableListOf<String>()
        override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String {
            systems += system
            val user = parts.joinToString("\n") { (it as? AiPart.Text)?.text.orEmpty() }
            users += user
            return answer(user)
        }
    }

    private val old = PlaceDossier(goodToKnow = listOf("A"), sources = listOf("https://alt.jp"), researchedAt = 5, verdict = "Gut.")
    private val good = """{"goodToKnow":["A","Montags geschlossen."],"tips":["Tonkotsu nehmen."],"hints":[]}"""

    @Test
    fun `facts are sorted in and old entries stay`() {
        val d = PlaceFacts(Scripted { good }).merge(old, "Ichiran", listOf("Montags zu", "Tonkotsu ist das Beste"), listOf("https://neu.jp", "https://alt.jp"))
        assertEquals(listOf("A", "Montags geschlossen."), d.goodToKnow)
        assertEquals(listOf("Tonkotsu nehmen."), d.tips)
        assertEquals(5, d.researchedAt)
        assertEquals("Gut.", d.verdict)
        assertEquals(listOf("Montags zu", "Tonkotsu ist das Beste"), d.reelFacts)
        assertEquals(listOf("https://alt.jp", "https://neu.jp"), d.sources)
    }

    @Test
    fun `a merge that drops old entries fails`() {
        val e = runCatching { PlaceFacts(Scripted { """{"goodToKnow":["Montags zu"],"tips":[],"hints":[]}""" }).merge(old, "Ichiran", listOf("Montags zu"), emptyList()) }.exceptionOrNull()
        assertEquals("ai_bad_answer", (e as AiFailed).code)
    }

    @Test
    fun `without a dossier one is made`() {
        val d = PlaceFacts(Scripted { good }).merge(null, "Ichiran", listOf("Montags zu"), emptyList())
        assertEquals(0, d.researchedAt)
        assertEquals(listOf("Tonkotsu nehmen."), d.tips)
        assertEquals(listOf("Montags zu"), d.reelFacts)
    }

    @Test
    fun `prompt asks for short sentences without context`() {
        val m = Scripted { good }
        PlaceFacts(m).merge(old, "Ichiran", listOf("Montags zu"), emptyList())
        assertTrue("ein Satz" in m.systems.single() && "nichts über den Beitrag" in m.systems.single())
        assertTrue("Montags zu" in m.users.single() && "Ichiran" in m.users.single() && "A" in m.users.single())
    }

    @Test
    fun `keepsDossier needs every old entry`() {
        assertTrue(keepsDossier(null, PlaceDossier()))
        assertTrue(keepsDossier(old, old.copy(goodToKnow = listOf("B", "A"))))
        assertTrue(!keepsDossier(old, old.copy(goodToKnow = emptyList())))
    }

    private val tmp = Files.createTempDirectory("wlp-facts")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))
    private val sync = SyncService(store)

    private fun seedPlace(dossier: PlaceDossier?) {
        sync.writeAsServer(
            ASSISTANT_AUTHOR,
            listOf(
                ServerEdit(EntityType.PLACE, "p1", EntityCodec.fields(Place("p1", name = "Ichiran")), create = true),
                ServerEdit(EntityType.SAVED_PLACE, "sp1", EntityCodec.fields(SavedPlace("sp1", "p1", null, PlaceCategory.FOOD, dossier = dossier)), create = true),
            ),
        )
    }

    private fun savedDossier() = EntityCodec.decode<SavedPlace>(store.get(EntityType.SAVED_PLACE, "sp1")!!).dossier

    @Test
    fun `place facts write the dossier and skip unknown places`() {
        seedPlace(old)
        val j = AiJobs(
            store, null, object : ReelMedia {
                override fun download(url: String, dir: java.nio.file.Path) = throw DownloadFailed()
                override fun extract(d: Downloaded, dir: java.nio.file.Path) = throw DownloadFailed()
            },
            tmp.resolve("work"), executor = Executors.newSingleThreadExecutor(),
            writeResults = { edits -> sync.writeAsServer(ASSISTANT_AUTHOR, edits) },
            placeFacts = PlaceFacts(Scripted { good }),
        )
        val started = j.startPlaceFacts(listOf(PlaceFactsItem("sp1", listOf("Montags zu")), PlaceFactsItem("gone", listOf("x"))))
        assertEquals(listOf("sp1", "gone"), started.savedPlaceIds)
        j.drain()
        val job = j.get(started.id)!!
        assertEquals("DONE", job.status)
        assertEquals(listOf("DONE", "FAILED"), job.items.map { it.state })
        assertEquals("Ort nicht gefunden", job.items[1].detail)
        assertEquals(listOf("A", "Montags geschlossen."), savedDossier()!!.goodToKnow)
    }

    @Test
    fun `research keeps reel facts`() {
        seedPlace(old.copy(reelFacts = listOf("Montags zu")))
        val research = mutableListOf<String>()
        val model = object : ChatModel {
            override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String {
                research += parts.joinToString("\n") { (it as? AiPart.Text)?.text.orEmpty() }
                return """{"description":"Ramen.","worth":"WORTH","verdict":"Gut.","goodToKnow":["Neu"]}"""
            }
        }
        val results = Assistant(model, Geocoder { null }).researchSaved(store, listOf("sp1"), AssessConfig(), { 99 })
        assertTrue(research.any { "Laut Reel: Montags zu" in it }, research.joinToString())
        assertEquals(listOf("Montags zu"), results.single().dossier.reelFacts)
    }
}
