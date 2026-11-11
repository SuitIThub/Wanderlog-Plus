package de.wanderlogplus.server.ai

import de.wanderlogplus.server.ASSISTANT_AUTHOR
import de.wanderlogplus.server.ServerEdit
import de.wanderlogplus.server.SqliteStore
import de.wanderlogplus.server.SyncService
import de.wanderlogplus.server.tabelog.TabelogReview
import de.wanderlogplus.server.tabelog.TabelogSummary
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.TabelogInfo
import de.wanderlogplus.shared.model.TabelogStatus
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslateTest {
    /** A fake model that "translates" by prefixing "DE:" and keeps texts that already look German. */
    private class Fake : ChatModel {
        val calls = mutableListOf<String>()
        override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String {
            val user = (parts.single() as AiPart.Text).text
            calls += user
            val texts = (Json.parseToJsonElement(user.substringAfter("\n")).jsonObject["texts"] as JsonArray).map { it.jsonPrimitive.content }
            return """{"texts":[${texts.joinToString(",") { t -> Json.encodeToString(kotlinx.serialization.serializer<String>(), if (looksGerman(t)) t else "DE: $t") }}]}"""
        }
    }

    @Test
    fun `translator keeps order and falls back to the original`() {
        val t = Translator(Fake())
        assertEquals(listOf("DE: A quiet temple.", "Ein stiller Tempel."), t.toGerman(listOf("A quiet temple.", "Ein stiller Tempel.")))
        val broken = Translator(ChatModel { _, _, _ -> """{"texts":["nur eins"]}""" })
        assertEquals(listOf("a", "b"), broken.toGerman(listOf("a", "b")))
        assertEquals(emptyList(), t.toGerman(emptyList()))
    }

    private val tmp = Files.createTempDirectory("wlp-translate")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))
    private val sync = SyncService(store)

    private fun put(p: Place) = sync.writeAsServer(ASSISTANT_AUTHOR, listOf(ServerEdit(EntityType.PLACE, p.id, EntityCodec.fields(p), create = true)))
    private fun place(id: String) = EntityCodec.decode<Place>(store.get(EntityType.PLACE, id)!!)

    @Test
    fun `translate job turns descriptions and Tabelog fields into German`() {
        put(Place("en", name = "Yuamiya", description = "Yuamiya is a beloved spot in Gero, offering onsen puddings."))
        put(Place("de", name = "Tempel", description = "Ein stiller Tempel mit Moosgarten."))
        put(
            Place(
                "tb", name = "Curry Station Niagara",
                tabelog = TabelogInfo(TabelogStatus.FOUND, "https://tabelog.com/tokyo/A1317/A131701/13001234/", genre = "カレー", reservation = "予約不可", hours = "11:00 - 20:00", station = "祐天寺駅から200m"),
            ),
        )
        val fake = Fake()
        val jobs = AiJobs(
            store, null, object : ReelMedia {
                override fun download(url: String, dir: java.nio.file.Path) = throw DownloadFailed()
                override fun extract(d: Downloaded, dir: java.nio.file.Path) = throw DownloadFailed()
            },
            tmp.resolve("work"), executor = Executors.newSingleThreadExecutor(),
            writeResults = { edits -> sync.writeAsServer(ASSISTANT_AUTHOR, edits) },
            translator = Translator(fake),
        )
        val started = jobs.startTranslate()
        assertEquals("translate", started.kind)
        jobs.drain()
        assertEquals("DONE", jobs.get(started.id)!!.status)
        assertEquals("DE: Yuamiya is a beloved spot in Gero, offering onsen puddings.", place("en").description)
        assertEquals("Ein stiller Tempel mit Moosgarten.", place("de").description) // German one never sent
        assertTrue(fake.calls.none { "Moosgarten" in it })
        val tb = place("tb").tabelog!!
        assertEquals("DE: カレー", tb.genre)
        assertEquals("DE: 予約不可", tb.reservation)
        assertEquals("11:00 - 20:00", tb.hours) // no Japanese: untouched
        assertEquals(TabelogStatus.FOUND, tb.status)
    }

    @Test
    fun `tabelog fields are translated together with the summary`() {
        val model = ChatModel { _, parts, _ ->
            val user = (parts.single() as AiPart.Text).text
            assertTrue("予約不可" in user && "カレー" in user)
            """{"summary":"Würziges Curry.","genre":"Curry","reservation":"keine Reservierung","hours":"11–20 Uhr","station":"200 m vom Bahnhof Yūtenji"}"""
        }
        val info = TabelogInfo(TabelogStatus.FOUND, genre = "カレー", reservation = "予約不可", hours = "11:00 - 20:00", station = "祐天寺駅から200m")
        val out = TabelogSummary(model).describe(listOf(TabelogReview("最高", "とても辛い", "2026/10")), info)
        assertEquals("Würziges Curry.", out.summary)
        assertEquals("Curry", out.genre)
        assertEquals("keine Reservierung", out.reservation)
        assertEquals("200 m vom Bahnhof Yūtenji", out.station)
        // A failing model leaves everything as it was.
        val failed = TabelogSummary(ChatModel { _, _, _ -> throw RuntimeException("down") }).describe(emptyList(), info)
        assertEquals(info, failed)
    }
}
