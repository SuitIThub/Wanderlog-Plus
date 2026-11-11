package de.wanderlogplus.server.ai

import de.wanderlogplus.server.ASSISTANT_AUTHOR
import de.wanderlogplus.server.FileStore
import de.wanderlogplus.server.SqliteStore
import de.wanderlogplus.server.SyncService
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.RoomInfo
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.StayInfo
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.StayResearchRequest
import de.wanderlogplus.shared.sync.encode
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StayResearchTest {
    private val stay = Stay("st", "p-ryo", checkIn = "2027-05-14", checkOut = "2027-05-16", confirmationNumber = "21315092583975", travelerNames = listOf("Christian Dieckmann"))
    private val place = Place("p-ryo", googlePlaceId = "ChIJryo", name = "Tsutaya Ryokan", address = "Kiso, Nagano")

    private val writerAnswer = """{"room":{"type":"Japanisches Zimmer mit eigenem Rotenburo","beds":"2 Futons","bath":"eigenes Freiluftbad",
        "amenities":[{"name":"Bad","items":[{"name":"Rotenburo","note":"privat"}]}]},
        "property":{"description":"Traditionelles Ryokan am Fluss.","meals":"Kaiseki 18:00","amenities":[{"name":"Service","items":[{"name":"Wäscherei"}]}]}}"""

    private class Recorder(private val answer: (List<AiPart>) -> String) : ChatModel {
        val asked = mutableListOf<String>()
        val parts = mutableListOf<List<AiPart>>()
        override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String {
            this.parts += parts
            asked += parts.filterIsInstance<AiPart.Text>().joinToString("\n") { it.text }
            return answer(parts)
        }
    }

    private val gemini = Recorder { "Zimmertyp: Japanese-style room with open-air bath\nBuchung 21315092583975 für Christian Dieckmann\nAbendessen: Kaiseki 18:00" }
    private val pages = PageReader { url, _ -> if ("broken" in url) throw AiFailed("research_failed") else Facts(listOf(Fact("Zimmer mit Blick auf den Fluss, 10 Tatami", listOf(url))), listOf(url)) }
    private val web = FactSource { _, _ -> Facts(listOf(Fact("Das Ryokan hat eine Wäscherei.", listOf("https://web.example"))), listOf("https://web.example")) }

    @Test
    fun `confirmation, link and web facts make the stay info`() {
        val writer = Recorder { writerAnswer }
        val said = mutableListOf<String>()
        val info = StayResearch(gemini, writer, web, pages).research(
            stay, place, listOf(StayDocument("application/pdf", byteArrayOf(1, 2), "Bestätigung.pdf")), listOf("https://ryokan.example/rooms"),
        ) { said += it }!!
        assertEquals("Japanisches Zimmer mit eigenem Rotenburo", info.room.type)
        assertEquals("Wäscherei", info.property.amenities.single().items.single().name)
        assertTrue("https://ryokan.example/rooms" in info.sources)
        assertTrue(gemini.parts.single().any { it is AiPart.Pdf })
        val prompt = writer.asked.single()
        assertTrue("Japanese-style room with open-air bath" in prompt && "10 Tatami" in prompt && "Wäscherei" in prompt, prompt)
    }

    @Test
    fun `booking data never leaves gemini`() {
        val writer = Recorder { writerAnswer.replace("Traditionelles", "Buchung 21315092583975 Christian Dieckmann Traditionelles") }
        val said = mutableListOf<String>()
        val info = StayResearch(gemini, writer, web, pages).research(
            stay, place, listOf(StayDocument("image/png", byteArrayOf(1), "screenshot.png")), emptyList(),
        ) { said += it }!!
        val all = writer.asked.single() + EntityCodec.fields(info).toString() + said.joinToString()
        assertFalse("21315092583975" in all || "Christian Dieckmann" in all, all)
    }

    @Test
    fun `a booking number with separators is removed too`() {
        val spaced = Recorder { "Zimmertyp: Twin\nBuchungsnummer 2131-5092-583975" }
        val writer = Recorder { writerAnswer.replace("Traditionelles", "Ref 2131 5092 583975 Traditionelles") }
        val info = StayResearch(spaced, writer, web, pages).research(stay, place, listOf(StayDocument("image/png", byteArrayOf(1), "s.png")), emptyList()) {}!!
        assertFalse("583975" in writer.asked.single(), writer.asked.single())
        assertFalse("583975" in EntityCodec.fields(info).toString())
    }

    @Test
    fun `room photos are only fetched from public https hosts`() {
        val guard = UrlGuard { host -> if (host == "intern.example") listOf(java.net.InetAddress.getByName("10.0.0.5")) else listOf(java.net.InetAddress.getByName("93.184.216.34")) }
        assertTrue(guard.allowed("https://hotel.example/rooms"))
        assertFalse(guard.allowed("http://hotel.example/rooms"), "plain http")
        assertFalse(guard.allowed("https://intern.example/x"), "private network")
        assertFalse(guard.allowed("https://localhost/x"))
        assertFalse(guard.allowed("https://169.254.169.254/latest/meta-data"))
        assertFalse(guard.allowed("https://[::1]/x"))
        assertFalse(guard.allowed("not a url"))
        // A private link yields no photos (and is never requested).
        assertEquals(emptyList(), HttpRoomPhotoSource(guard).photos("https://intern.example/rooms", "Twin"))
    }

    @Test
    fun `large answers are cut off instead of read into memory`() {
        assertEquals(3, readCapped(java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3)), 5)!!.size)
        assertEquals(null, readCapped(java.io.ByteArrayInputStream(ByteArray(10)), 5))
    }

    @Test
    fun `a broken link does not stop the research`() {
        val said = mutableListOf<String>()
        val info = StayResearch(gemini, Recorder { writerAnswer }, web, pages).research(stay, place, emptyList(), listOf("https://broken.example", "https://ryokan.example")) { said += it }
        assertTrue(info != null)
        assertTrue(said.any { "broken.example" in it && "nicht lesbar" in it }, said.toString())
    }

    @Test
    fun `without confirmations and links only the property is filled`() {
        val writer = Recorder { """{"room":{"type":"Erfunden"},"property":{"description":"Ryokan am Fluss."}}""" }
        val info = StayResearch(gemini, writer, web, pages).research(stay, place, emptyList(), emptyList()) {}!!
        assertTrue("Keine Angaben zum gebuchten Zimmer" in writer.asked.single())
        assertEquals(RoomInfo(), info.room, "no booking source: the room stays empty")
        assertEquals("Ryokan am Fluss.", info.property.description)
    }

    private val tmp = Files.createTempDirectory("wlp-stay")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))
    private val sync = SyncService(store)
    private val files = FileStore(tmp.resolve("files"), 10_000_000)

    private fun <T> seed(type: EntityType, id: String, fields: Map<String, kotlinx.serialization.json.JsonElement>) {
        var rec = EntityRecord(type, id)
        (fields + ("deleted" to JsonPrimitive(false))).forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun jobs(photos: RoomPhotoSource? = null) = AiJobs(
        store, null, object : ReelMedia {
            override fun download(url: String, dir: java.nio.file.Path) = throw DownloadFailed()
            override fun extract(d: Downloaded, dir: java.nio.file.Path) = throw DownloadFailed()
        },
        tmp.resolve("work"), executor = Executors.newSingleThreadExecutor(),
        writeResults = { edits -> sync.writeAsServer(ASSISTANT_AUTHOR, edits) },
        stayResearch = StayResearch(gemini, Recorder { writerAnswer }, web, pages, photos = photos), files = files,
    )

    private fun storedStay() = EntityCodec.decode<Stay>(store.get(EntityType.STAY, "st")!!)

    @Test
    fun `the job keeps hand edits and stores links and room photos`() {
        seed<Place>(EntityType.PLACE, "p-ryo", EntityCodec.fields(place))
        val handInfo = StayInfo(room = RoomInfo(type = "Twin (von Hand)"), editedByHand = setOf("room.type"))
        seed<Stay>(EntityType.STAY, "st", EntityCodec.fields(stay.copy(info = handInfo)))
        val photos = RoomPhotoSource { _, _ -> listOf("room1.jpg" to byteArrayOf(9, 9, 9)) }
        val j = jobs(photos)
        val started = j.startStay(StayResearchRequest("st", listOf("https://ryokan.example/rooms")))
        j.drain()
        assertEquals("DONE", j.get(started.id)!!.status)
        val s = storedStay()
        assertEquals("Twin (von Hand)", s.info!!.room.type)
        assertEquals("2 Futons", s.info!!.room.beds)
        assertEquals(listOf("https://ryokan.example/rooms"), s.infoLinks)
        val photo = store.all(EntityType.ATTACHMENT).filter { !it.isDeleted }.map { EntityCodec.decode<Attachment>(it) }.single()
        assertEquals(Attachment.LABEL_ROOM, photo.label)
        assertEquals("st", photo.targetId)
        assertEquals("photo", photo.kind)
        // "Alles neu recherchieren" replaces the hand edit too.
        j.startStay(StayResearchRequest("st", emptyList(), fresh = true)); j.drain()
        assertEquals("Japanisches Zimmer mit eigenem Rotenburo", storedStay().info!!.room.type)
    }
}
