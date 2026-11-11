package de.wanderlogplus.server.ai

import de.wanderlogplus.server.Services
import de.wanderlogplus.server.SqliteStore
import de.wanderlogplus.server.authed
import de.wanderlogplus.server.module
import de.wanderlogplus.server.testServices
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import de.wanderlogplus.shared.sync.Worth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantTest {
    private val tmp = Files.createTempDirectory("wlp-ai")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))

    private fun seed(type: EntityType, id: String, fields: Map<String, JsonElement>) {
        var rec = EntityRecord(type, id)
        (fields + ("deleted" to JsonPrimitive(false))).forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun seedTrip() {
        seed(EntityType.REGION, "r-kyoto", EntityCodec.fields(Region("r-kyoto", "Kyoto")))
        seed(EntityType.PLACE, "p-hotel", EntityCodec.fields(Place("p-hotel", name = "Hotel Gion", lat = 35.0037, lng = 135.7788)))
        seed(EntityType.STAY, "st", EntityCodec.fields(Stay("st", "p-hotel", "r-kyoto", "2027-05-19", "2027-05-22", confirmationNumber = "SECRET-123")))
        seed(EntityType.PLACE, "p-tempel", EntityCodec.fields(Place("p-tempel", name = "Kiyomizu-dera", lat = 34.9949, lng = 135.785, rating = 4.6)))
        seed(EntityType.SAVED_PLACE, "s-tempel", EntityCodec.fields(SavedPlace("s-tempel", "p-tempel", "r-kyoto", PlaceCategory.SIGHT)))
        seed(EntityType.SAVED_PLACE, "s-done", EntityCodec.fields(SavedPlace("s-done", "p-tempel", "r-kyoto", worth = Worth.MUST)))
    }

    /** Answers by prompt type; records every call. */
    private class FakeModel(var failResearch: Boolean = false) : ChatModel {
        val calls = mutableListOf<Triple<String, List<AiPart>, Boolean>>()
        override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String {
            calls += Triple(system, parts, webSearch)
            return when {
                system.contains("Instagram-Reel") -> """```json
                    {"summary":"Ein Abend in Kyoto.","locations":[
                      {"name":"Fushimi Inari Taisha","localName":"伏見稲荷大社","city":"Kyoto","country":"Japan","kind":"sight","evidence":"Torii-Tunnel im Video"},
                      {"name":"Ichiran Shibuya","city":"Tokyo","country":"Japan","kind":"food","evidence":"Gesprochen"},
                      {"name":"Ichiran Shibuya","city":"Tokyo","kind":"food"}
                    ]}```"""
                system.contains("Du recherchierst") -> if (failResearch) throw AiFailed() else
                    """{"description":"Shinto-Schrein mit tausenden Torii.","category":"SIGHT",
                       "criteria":[{"key":"crowds","score":2,"note":"Sehr voll"},{"key":"unknown","score":5},{"key":"highlight","score":9,"note":"Ikone"}],
                       "worth":"must","verdict":"Ikonisch, früh hingehen.",
                       "insiderTips":["Hinter dem Gipfel ist es leer"],"hiddenSpots":["Kleiner Schrein am Weg"],"goodToKnow":["Eintritt frei"],
                       "tips":["Früh morgens gehen","Bis zum Gipfel wandern"],"hints":["Sehr voll ab 10 Uhr"]}"""
                system.contains("lohnenswert") ->
                    """{"results":[{"id":"s-tempel","worth":"WORTH","verdict":"Schön, aber voll.","criteria":[{"key":"crowds","score":2,"note":"voll"}]},{"id":"unknown","worth":"MUST"}]}"""
                else -> "{}"
            }
        }
    }

    private class FakeGeocoder : Geocoder {
        override fun find(query: String): GeoHit? = when {
            query.contains("Fushimi") -> GeoHit(34.9671, 135.7727, "Fushimi, Kyoto, Japan")
            query.contains("Ichiran") -> GeoHit(35.6612, 139.7016, "Shibuya, Tokyo, Japan")
            else -> null
        }
    }

    private class FakeMedia(val fail: Boolean = false, val imagesOnly: Boolean = false) : ReelMedia {
        var downloads = 0
        var extracted: Downloaded? = null
        override fun download(url: String, dir: Path): Downloaded {
            downloads++
            if (fail) throw DownloadFailed()
            if (imagesOnly) {
                val i = dir.resolve("1.jpg"); Files.writeString(i, "x")
                return Downloaded(emptyList(), listOf(i), "Tipps für Kyoto")
            }
            val v = dir.resolve("video.mp4"); Files.writeString(v, "x")
            return Downloaded(listOf(v), emptyList(), "Mein Kyoto-Abend #fushimi")
        }
        override fun extract(d: Downloaded, dir: Path): Media {
            extracted = d
            return Media(listOf(byteArrayOf(1, 2), byteArrayOf(3)), if (d.videos.isEmpty()) null else byteArrayOf(9))
        }
    }

    private fun jobs(model: ChatModel? = FakeModel(), media: ReelMedia = FakeMedia(), sync: de.wanderlogplus.server.SyncService? = null) =
        AiJobs(
            store, model?.let { Assistant(it, FakeGeocoder()) }, media, tmp.resolve("work"), executor = Executors.newSingleThreadExecutor(),
            writeResults = sync?.let { s -> { edits -> s.writeAsServer(de.wanderlogplus.server.ASSISTANT_AUTHOR, edits) } },
        )

    private fun field(type: EntityType, id: String, name: String) = store.get(type, id)?.fields?.get(name)?.value

    @Test fun referencesAreLocatedButNotResearched() {
        seedTrip()
        val model = object : ChatModel {
            val research = mutableListOf<String>()
            override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String = when {
                system.contains("Instagram-Reel") -> """{"summary":"Ein Izakaya in Kyoto.","locations":[
                    {"name":"Kyoto Station","city":"Kyoto","country":"Japan","kind":"other","role":"reference","evidence":"5 Minuten vom Bahnhof"},
                    {"name":"Fushimi Inari Taisha","area":"Fushimi","city":"Kyoto","country":"Japan","kind":"sight","role":"main","evidence":"Titel"}]}"""
                else -> { research += (parts.first() as AiPart.Text).text; """{"description":"Schrein.","worth":"MUST","verdict":"Ikonisch."}""" }
            }
        }
        val geocoder = object : Geocoder {
            val asked = mutableListOf<String>()
            override fun find(query: String): GeoHit? { asked += query; return GeoHit(34.97, 135.77, query, "JP") }
        }
        val a = Assistant(model, geocoder)
        val (_, found) = a.findPlaces("caption", Media(emptyList(), null), TripContext.from(store))
        assertEquals(listOf(false, true), found.map { it.main })
        assertEquals("Fushimi", found.last().area)
        val candidates = a.researchAll(found, TripContext.from(store), de.wanderlogplus.shared.sync.AssessConfig())
        // The main place first, researched; the station only located, marked as a reference.
        assertEquals(listOf("Fushimi Inari Taisha", "Kyoto Station"), candidates.map { it.name })
        assertEquals(listOf(true, false), candidates.map { it.main })
        assertNull(candidates.last().dossier)
        assertTrue(candidates.last().lat != null)
        assertEquals(1, model.research.size)
        // The area goes into the search for the place and into the research.
        assertEquals("Fushimi Inari Taisha Fushimi Kyoto", geocoder.asked.first { it.startsWith("Fushimi") })
        assertTrue("Stadtteil: Fushimi" in model.research.single(), model.research.single())
    }

    @Test fun reelPipelineFindsLocatesResearchesAndChecksRoute() {
        seedTrip()
        val model = FakeModel()
        val j = jobs(model)
        val started = j.startReel(ReelInput.Link("https://www.instagram.com/reel/abc/"))
        j.drain()
        val job = j.get(started.id)!!
        assertEquals("DONE", job.status)
        assertEquals("Ein Abend in Kyoto.", job.summary)
        assertEquals(2, job.candidates.size) // duplicate Ichiran removed
        val fushimi = job.candidates.first { it.name.startsWith("Fushimi") }
        assertTrue(fushimi.onRoute)
        assertEquals("Kiyomizu-dera", fushimi.nearestAnchor)
        assertEquals(Worth.MUST, fushimi.worth) // "must" accepted case-insensitively
        assertEquals(listOf("Früh morgens gehen", "Bis zum Gipfel wandern"), fushimi.tips)
        assertEquals("Ikonisch, früh hingehen.", fushimi.worthReason)
        val d = fushimi.dossier!!
        assertEquals(listOf("Hinter dem Gipfel ist es leer"), d.insiderTips)
        assertEquals(listOf("Kleiner Schrein am Weg"), d.hiddenSpots)
        // Only chosen criteria, labels filled in, scores clamped to 1–5.
        assertEquals(listOf("crowds" to 2, "highlight" to 5), d.criteria.map { it.key to it.score })
        assertEquals("Andrang", d.criteria.first().label)
        val ichiran = job.candidates.first { it.name.startsWith("Ichiran") }
        assertFalse(ichiran.onRoute) // Tokyo is ~370 km from the Kyoto anchors
        assertTrue(ichiran.distanceKm!! > 300)
        // The analysis call got the caption, both frames and the audio, without web search; research used it.
        val (_, parts, web) = model.calls.first()
        assertFalse(web)
        assertEquals(2, parts.count { it is AiPart.Image })
        assertEquals(1, parts.count { it is AiPart.Audio })
        assertTrue(parts.filterIsInstance<AiPart.Text>().any { it.text.contains("#fushimi") })
        assertTrue(model.calls.drop(1).all { it.third })
        // No booking data ever reaches the model.
        assertTrue(model.calls.none { c -> c.second.filterIsInstance<AiPart.Text>().any { it.text.contains("SECRET") } })
    }

    @Test fun failedResearchStillKeepsThePlace() {
        seedTrip()
        val j = jobs(FakeModel(failResearch = true))
        val id = j.startReel(ReelInput.Link("https://www.instagram.com/reel/abc/")).id
        j.drain()
        val job = j.get(id)!!
        assertEquals("DONE", job.status)
        assertEquals("", job.candidates.first().description)
        assertEquals("SIGHT", job.candidates.first().category)
    }

    @Test fun downloadFailureIsReported() {
        val j = jobs(media = FakeMedia(fail = true))
        val id = j.startReel(ReelInput.Link("https://www.instagram.com/reel/x/")).id
        j.drain()
        assertEquals("FAILED", j.get(id)!!.status)
        assertEquals("download_failed", j.get(id)!!.error)
    }

    @Test fun imagePostIsAnalyzed() {
        seedTrip()
        val media = FakeMedia(imagesOnly = true)
        val j = jobs(media = media)
        val id = j.startReel(ReelInput.Link("https://www.instagram.com/p/abc/")).id
        j.drain()
        assertEquals("DONE", j.get(id)!!.status)
        assertEquals(1, media.extracted!!.images.size)
        assertTrue(j.get(id)!!.steps.any { it.text.contains("1 Bild") })
    }

    @Test fun uploadedVideoIsUsedAndDeleted() {
        seedTrip()
        val media = FakeMedia()
        val j = jobs(media = media)
        val v = Files.createTempFile(tmp, "up", ".mp4")
        val id = j.startReel(ReelInput.Video(v, "Beschreibung")).id
        j.drain()
        assertEquals("DONE", j.get(id)!!.status)
        assertEquals(0, media.downloads)
        assertFalse(Files.exists(v))
    }

    @Test fun assessmentJudgesOnlyUnjudgedAndKnownIds() {
        seedTrip()
        val j = jobs()
        val id = j.startAssessment(emptyList()).id
        j.drain()
        val job = j.get(id)!!
        assertEquals("DONE", job.status)
        assertEquals(listOf("s-tempel"), job.assessments.map { it.savedPlaceId })
        assertEquals(Worth.WORTH, job.assessments.single().worth)
        assertEquals("Schön, aber voll.", job.assessments.single().reason)
        assertEquals(listOf("crowds"), job.assessments.single().criteria.map { it.key })
    }

    @Test fun answersInFencesAndListsParse() {
        assertEquals("1", OpenRouterModel.jsonObjectIn("Hier: ```json\n{\"a\":\"1\"}\n```")["a"]!!.jsonPrimitive.content)
        assertEquals("x", OpenRouterModel.answerText("""{"choices":[{"message":{"content":"x"}}]}"""))
    }

    @Test fun requestBodyHasImagesAudioJsonModeAndWebPlugin() {
        val body = Json.parseToJsonElement(OpenRouterModel.requestBody("m", "sys", listOf(AiPart.Text("t"), AiPart.Image(byteArrayOf(1)), AiPart.Audio(byteArrayOf(2))), webSearch = true)).jsonObject
        val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text", "image_url", "input_audio"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertTrue(content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("web", body["plugins"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        val noWeb = Json.parseToJsonElement(OpenRouterModel.requestBody("m", "s", emptyList(), webSearch = false)).jsonObject
        assertNull(noWeb["plugins"])
    }

    @Test fun urlsAreRecognised() {
        assertEquals("https://www.instagram.com/reel/C1x/?igsh=abc", firstUrlIn("Schau mal https://www.instagram.com/reel/C1x/?igsh=abc"))
        assertTrue(isSupportedVideoUrl("https://www.instagram.com/reel/C1x/"))
        assertFalse(isSupportedVideoUrl("http://www.instagram.com/reel/C1x/"))
        assertFalse(isSupportedVideoUrl("https://evil.example/instagram.com"))
        assertEquals("Beschreibung\n\nTitel", ToolReelMedia().captionOf("""{"title":"Titel","description":"Beschreibung"}"""))
        assertEquals("Gleich", ToolReelMedia().captionOf("""{"title":"Gleich","description":"Gleich"}"""))
        assertNull(ToolReelMedia().captionOf("kein json"))
    }

    @Test fun endpointsWithoutKeyAnswer503() = testApplication {
        application { module(testServices(tmp)) }
        val r = client.post("/api/ai/reels") { authed(); contentType(ContentType.Application.Json); setBody("""{"url":"https://www.instagram.com/reel/a/"}""") }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.bodyAsText().contains("ai_not_configured"))
    }

    @Test fun endpointsStartAndReportJobs() = testApplication {
        seedTrip()
        val base = testServices(tmp)
        val j = AiJobs(base.store, Assistant(FakeModel(), FakeGeocoder()), FakeMedia(), tmp.resolve("work2"), executor = Executors.newSingleThreadExecutor())
        application { module(Services(base.config, base.store, base.sync, base.files, base.imports, base.routes, j)) }
        val bad = client.post("/api/ai/reels") { authed(); contentType(ContentType.Application.Json); setBody("""{"url":"https://example.com/x"}""") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val started = client.post("/api/ai/reels") { authed(); contentType(ContentType.Application.Json); setBody("""{"url":"Guck mal https://www.instagram.com/reel/a/"}""") }
        assertEquals(HttpStatusCode.OK, started.status)
        val id = Json.parseToJsonElement(started.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        j.drain()
        val job = client.get("/api/ai/jobs/$id") { authed() }.bodyAsText()
        assertTrue(job.contains("\"status\":\"DONE\""), job)
        assertTrue(client.get("/api/ai/jobs?kind=reel") { authed() }.bodyAsText().contains(id))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/ai/jobs/nope") { authed() }.status)
        val assess = client.post("/api/ai/assess") { authed(); contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.OK, assess.status)
    }

    @Test fun interruptedJobsResumeOrFail() {
        seedTrip()
        val json = Json { encodeDefaults = true }
        fun put(j: de.wanderlogplus.shared.sync.AiJobInfo) = store.putAiJob(j.id, j.kind, j.createdAt, json.encodeToString(de.wanderlogplus.shared.sync.AiJobInfo.serializer(), j))
        put(de.wanderlogplus.shared.sync.AiJobInfo("link", "reel", 1, "RESEARCHING", source = "https://www.instagram.com/reel/abc/"))
        put(de.wanderlogplus.shared.sync.AiJobInfo("video", "reel", 2, "ANALYZING", source = "Video"))
        put(de.wanderlogplus.shared.sync.AiJobInfo("assess", "assess", 3, "ASSESSING"))
        put(de.wanderlogplus.shared.sync.AiJobInfo("done", "reel", 4, "DONE", source = "https://www.instagram.com/reel/x/"))
        val j = jobs()
        j.resumeInterrupted()
        j.drain()
        assertEquals("DONE", j.get("link")!!.status)
        assertEquals(2, j.get("link")!!.candidates.size)
        assertEquals("interrupted", j.get("video")!!.error)
        assertEquals("interrupted", j.get("assess")!!.error)
        assertEquals("DONE", j.get("done")!!.status)
    }

    @Test fun researchOfSavedPlacesUsesChosenCriteriaAndExtraPrompt() {
        seedTrip()
        val model = FakeModel()
        val j = jobs(model)
        val config = de.wanderlogplus.shared.sync.AssessConfig(listOf("crowds", "booking"), "Wir mögen keine Menschenmassen.")
        val id = j.startResearch(listOf("s-tempel"), config).id
        j.drain()
        val job = j.get(id)!!
        assertEquals("DONE", job.status)
        assertEquals(1, job.progress)
        val r = job.dossiers.single()
        assertEquals("s-tempel", r.savedPlaceId)
        assertEquals(Worth.MUST, r.worth)
        assertEquals(listOf("crowds"), r.dossier.criteria.map { it.key }) // "highlight" not chosen
        assertTrue(r.dossier.researchedAt > 0)
        val system = model.calls.last().first
        assertTrue(system.contains("booking (Buchbarkeit)"))
        assertTrue(system.contains("Wir mögen keine Menschenmassen."))
        assertFalse(system.contains("highlight ("))
    }

    @Test fun researchEndpointRejectsEmptyList() = testApplication {
        val base = testServices(tmp)
        val j = AiJobs(base.store, Assistant(FakeModel(), FakeGeocoder()), FakeMedia(), tmp.resolve("work3"), executor = Executors.newSingleThreadExecutor())
        application { module(Services(base.config, base.store, base.sync, base.files, base.imports, base.routes, j)) }
        val r = client.post("/api/ai/research") { authed(); contentType(ContentType.Application.Json); setBody("""{"savedPlaceIds":[]}""") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test fun reelJobKeepsAReadableLogAndPerPlaceStates() {
        seedTrip()
        val j = jobs(FakeModel())
        val id = j.startReel(ReelInput.Link("https://www.instagram.com/reel/abc/")).id
        j.drain()
        val job = j.get(id)!!
        val texts = job.steps.map { it.text }
        assertTrue(texts.first().startsWith("Reel-Link erhalten"))
        assertTrue(texts.any { it.startsWith("2 Bilder und Tonspur") })
        assertTrue(texts.any { it.startsWith("2 Orte erkannt: Fushimi") })
        assertTrue(texts.any { it.startsWith("Fushimi Inari Taisha: Lage: ") })
        assertTrue(texts.any { it.contains("Fushimi Inari Taisha: fertig, Muss man sehen") })
        assertEquals("Fertig", texts.last())
        assertEquals(2, job.items.size)
        assertTrue(job.items.all { it.state == "DONE" && it.startedAt > 0 && it.finishedAt >= it.startedAt })
        assertTrue(job.finishedAt > 0)
        // Log lines are ours: no booking data.
        assertTrue(texts.none { it.contains("SECRET") })
    }

    @Test fun failedJobsEndTheLogWithTheReason() {
        val j = jobs(media = FakeMedia(fail = true))
        val id = j.startReel(ReelInput.Link("https://www.instagram.com/reel/x/")).id
        j.drain()
        val last = j.get(id)!!.steps.last()
        assertEquals("ERROR", last.level)
        assertEquals("Abgebrochen: Reel ließ sich nicht laden", last.text)
    }

    @Test fun researchMarksPlacesThatGotNoResult() {
        seedTrip()
        val j = jobs(FakeModel(failResearch = true))
        val id = j.startResearch(listOf("s-tempel", "gone")).id
        j.drain()
        val job = j.get(id)!!
        assertEquals("DONE", job.status)
        assertEquals("FAILED", job.items.single().state)
        assertTrue(job.steps.any { it.text == "1 Orte nicht gefunden (gelöscht?)" && it.level == "WARN" })
        assertTrue(job.steps.any { it.text.contains("Recherche fehlgeschlagen") })
    }

    @Test fun researchResultsAreWrittenIntoTheTripByTheServer() {
        seedTrip()
        // A second place without description: it gets the researched one, Kiyomizu keeps nothing (it has none either).
        seed(EntityType.PLACE, "p-leer", EntityCodec.fields(Place("p-leer", name = "Leerer Ort", lat = 34.99, lng = 135.78, description = "Eigene Beschreibung")))
        seed(EntityType.SAVED_PLACE, "s-leer", EntityCodec.fields(SavedPlace("s-leer", "p-leer", "r-kyoto")))
        val sync = de.wanderlogplus.server.SyncService(store)
        val seqBefore = store.maxSeq()
        val j = jobs(FakeModel(), sync = sync)
        val id = j.startResearch(listOf("s-tempel", "s-leer")).id
        j.drain()
        val job = j.get(id)!!
        assertTrue(job.appliedOnServer)
        assertEquals(2, job.dossiers.size)
        assertEquals("\"MUST\"", field(EntityType.SAVED_PLACE, "s-tempel", "worth").toString())
        assertEquals("\"Ikonisch, früh hingehen.\"", field(EntityType.SAVED_PLACE, "s-tempel", "worthReason").toString())
        assertTrue(field(EntityType.SAVED_PLACE, "s-tempel", "dossier").toString().contains("Hinter dem Gipfel"))
        // Description only where there was none.
        assertEquals("\"Shinto-Schrein mit tausenden Torii.\"", field(EntityType.PLACE, "p-tempel", "description").toString())
        assertEquals("\"Eigene Beschreibung\"", field(EntityType.PLACE, "p-leer", "description").toString())
        // The writes are ordinary sync changes: devices pull them.
        assertTrue(store.maxSeq() > seqBefore)
        assertTrue(job.steps.any { it.text == "2 Orte mit den Ergebnissen aktualisiert" })
    }

    @Test fun researchKeepsHandSetReservationData() {
        seedTrip()
        val sync = de.wanderlogplus.server.SyncService(store)
        val hand = de.wanderlogplus.shared.model.ReservationWindow(opens = "2027-04-01", byHand = true)
        val handJson = kotlinx.serialization.json.Json.encodeToJsonElement(de.wanderlogplus.shared.model.ReservationWindow.serializer(), hand)
        sync.writeAsServer("t", listOf(de.wanderlogplus.server.ServerEdit(EntityType.SAVED_PLACE, "s-tempel", mapOf("reservationWindow" to handJson, "reserved" to kotlinx.serialization.json.JsonPrimitive(true)))))
        val j = jobs(FakeModel(), sync = sync)
        j.startResearch(listOf("s-tempel")); j.drain()
        assertTrue(field(EntityType.SAVED_PLACE, "s-tempel", "dossier").toString().contains("Hinter dem Gipfel"))
        assertEquals(handJson, field(EntityType.SAVED_PLACE, "s-tempel", "reservationWindow"))
        assertEquals("true", field(EntityType.SAVED_PLACE, "s-tempel", "reserved").toString())
    }

    @Test fun olderResultsNeverOverwriteNewerResearch() {
        seedTrip()
        val sync = de.wanderlogplus.server.SyncService(store)
        val j = jobs(FakeModel(), sync = sync)
        j.drain()
        // A finished job from an older version: not applied yet, result older than what the place has now.
        val newer = de.wanderlogplus.shared.model.PlaceDossier(verdict = "neu", researchedAt = 5_000)
        sync.writeAsServer("t", listOf(de.wanderlogplus.server.ServerEdit(EntityType.SAVED_PLACE, "s-tempel", mapOf("dossier" to kotlinx.serialization.json.Json.encodeToJsonElement(de.wanderlogplus.shared.model.PlaceDossier.serializer(), newer)))))
        val old = de.wanderlogplus.shared.sync.AiJobInfo(
            "old", "research", 1, "DONE",
            dossiers = listOf(
                de.wanderlogplus.shared.sync.DossierResult("s-tempel", Worth.SKIP, "", de.wanderlogplus.shared.model.PlaceDossier(verdict = "alt", researchedAt = 1_000)),
                de.wanderlogplus.shared.sync.DossierResult("s-done", Worth.WORTH, "", de.wanderlogplus.shared.model.PlaceDossier(verdict = "nachgetragen", researchedAt = 1_000)),
            ),
        )
        store.putAiJob(old.id, old.kind, old.createdAt, kotlinx.serialization.json.Json.encodeToString(de.wanderlogplus.shared.sync.AiJobInfo.serializer(), old))
        j.resumeInterrupted()
        assertTrue(field(EntityType.SAVED_PLACE, "s-tempel", "dossier").toString().contains("\"neu\""))
        assertTrue(field(EntityType.SAVED_PLACE, "s-done", "dossier").toString().contains("nachgetragen"))
        val after = j.get("old")!!
        assertTrue(after.appliedOnServer)
        assertEquals("Nachgetragen: Ergebnisse von 1 Ort in die Orte geschrieben", after.steps.last().text)
    }

    @Test fun interruptedResearchContinuesWithTheRest() {
        seedTrip()
        val sync = de.wanderlogplus.server.SyncService(store)
        val interrupted = de.wanderlogplus.shared.sync.AiJobInfo(
            "r1", "research", 1, "RESEARCHING", total = 2, progress = 1,
            savedPlaceIds = listOf("s-done", "s-tempel"),
            config = de.wanderlogplus.shared.sync.AssessConfig(listOf("crowds")),
            dossiers = listOf(de.wanderlogplus.shared.sync.DossierResult("s-done", Worth.WORTH, "", de.wanderlogplus.shared.model.PlaceDossier(verdict = "schon da", researchedAt = 1))),
            items = listOf(de.wanderlogplus.shared.sync.JobItem("s-done", "Kiyomizu-dera", "DONE")),
        )
        store.putAiJob(interrupted.id, interrupted.kind, interrupted.createdAt, kotlinx.serialization.json.Json.encodeToString(de.wanderlogplus.shared.sync.AiJobInfo.serializer(), interrupted))
        val model = FakeModel()
        val j = jobs(model, sync = sync)
        j.resumeInterrupted()
        j.drain()
        val job = j.get("r1")!!
        assertEquals("DONE", job.status)
        assertEquals(listOf("s-done", "s-tempel"), job.dossiers.map { it.savedPlaceId })
        assertEquals(2, job.progress)
        assertEquals(1, model.calls.size) // only the missing place was researched
        assertTrue(model.calls.single().first.contains("crowds (Andrang)")) // with the stored criteria
        assertTrue(job.steps.any { it.text == "Server neu gestartet, Recherche geht mit den restlichen 1 Orten weiter" })
        assertEquals(setOf("s-done", "s-tempel"), job.items.map { it.key }.toSet())
    }

    @Test fun assessmentIsWrittenByTheServerAndKeepsResearch() {
        seedTrip()
        val sync = de.wanderlogplus.server.SyncService(store)
        val research = de.wanderlogplus.shared.model.PlaceDossier(insiderTips = listOf("Geheimtipp bleibt"), researchedAt = 7)
        sync.writeAsServer("t", listOf(de.wanderlogplus.server.ServerEdit(EntityType.SAVED_PLACE, "s-tempel", mapOf("dossier" to kotlinx.serialization.json.Json.encodeToJsonElement(de.wanderlogplus.shared.model.PlaceDossier.serializer(), research)))))
        val j = jobs(FakeModel(), sync = sync)
        val id = j.startAssessment(emptyList()).id
        j.drain()
        val job = j.get(id)!!
        assertTrue(job.appliedOnServer)
        assertEquals("\"WORTH\"", field(EntityType.SAVED_PLACE, "s-tempel", "worth").toString())
        val dossier = field(EntityType.SAVED_PLACE, "s-tempel", "dossier").toString()
        assertTrue(dossier.contains("Geheimtipp bleibt") && dossier.contains("crowds") && dossier.contains("Schön, aber voll."))
        assertTrue(job.steps.any { it.text == "1 Ort mit der Einschätzung aktualisiert" })
    }
}
