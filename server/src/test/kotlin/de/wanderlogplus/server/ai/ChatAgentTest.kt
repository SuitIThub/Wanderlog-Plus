package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.OpeningPeriod
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.sync.ChatCard
import de.wanderlogplus.shared.sync.ChatContext
import de.wanderlogplus.shared.sync.ChatMessage
import de.wanderlogplus.shared.sync.ChatScreens
import de.wanderlogplus.shared.sync.Worth
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatAgentTest {
    private val trip = ChatTrip(
        trip = Trip("t", "Japan 2027", "2027-05-18", "2027-05-22"),
        regions = listOf(Region("r-kyoto", "Kyoto"), Region("r-nara", "Nara", dayTripFromRegionId = "r-kyoto"), Region("r-tokyo", "Tokyo")),
        places = listOf(
            Place("p-hotel", name = "Hotel Gion", lat = 35.0037, lng = 135.7788, address = "Gionmachi, Higashiyama, Kyoto"),
            Place("p-kiyo", name = "Kiyomizu-dera", lat = 34.9949, lng = 135.7850, rating = 4.6, description = "Buddhistischer Tempel am Hang"),
            Place("p-ramen", name = "Ramen Ichiran", lat = 35.0050, lng = 135.7700, rating = 4.2),
            // Open Monday only (day 1).
            Place("p-market", name = "Nishiki Markt", lat = 35.0050, lng = 135.7650, openingPeriods = listOf(OpeningPeriod(1, "09:00", 1, "17:00"))),
            Place("p-deer", name = "Nara-Park", lat = 34.6851, lng = 135.8430, description = "Hirsche"),
            Place("p-shib", name = "Shibuya Crossing"),
        ).associateBy { it.id },
        saved = listOf(
            SavedPlace("s-kiyo", "p-kiyo", "r-kyoto", PlaceCategory.SIGHT, worth = Worth.MUST, dossier = PlaceDossier(verdict = "Ikonisch, früh hingehen.", insiderTips = listOf("Hinterer Pfad ist leer"))),
            SavedPlace("s-ramen", "p-ramen", "r-kyoto", PlaceCategory.FOOD, worth = Worth.WORTH),
            SavedPlace("s-market", "p-market", "r-kyoto", PlaceCategory.FOOD),
            SavedPlace("s-deer", "p-deer", "r-nara", PlaceCategory.SIGHT, worth = Worth.OPTIONAL),
            SavedPlace("s-shib", "p-shib", "r-tokyo", PlaceCategory.SIGHT),
        ),
        stays = listOf(Stay("st", "p-hotel", "r-kyoto", "2027-05-18", "2027-05-22", confirmationNumber = "SECRET-123", travelerNames = listOf("Erika Muster"))),
        days = listOf(Day("d1", "2027-05-19", title = "Tempeltag")),
        dayItems = listOf(DayItem("i1", "d1", "a", placeId = "p-ramen", time = "12:00")),
        trains = listOf(Train("tr", carrier = "JR", depart = Endpoint("Kyoto", date = "2027-05-22", time = "09:10"), arrive = Endpoint("Tokyo", date = "2027-05-22", time = "11:20"), confirmationNumber = "SECRET-TRAIN")),
    )

    /** Plays back scripted turns and records the messages it was sent. */
    private class ScriptedModel(private val turns: List<ToolTurn>) : ToolChatModel {
        val seen = mutableListOf<List<JsonObject>>()
        override fun turn(messages: List<JsonObject>, tools: JsonArray?): ToolTurn {
            seen += messages.toList()
            return turns.getOrElse(seen.size - 1) { turns.last() }
        }
    }

    private fun calls(vararg c: Pair<String, String>) =
        ToolTurn(null, c.mapIndexed { i, (n, a) -> ToolCall("c$i", n, a) }, buildJsonObject { put("role", "assistant"); put("content", "") })

    private fun text(t: String) = ToolTurn(t, emptyList(), buildJsonObject { put("role", "assistant"); put("content", t) })

    private fun user(text: String, ctx: ChatContext? = null) = ChatMessage("m", ChatMessage.ROLE_USER, text, 0, ctx)

    private fun tools(web: WebSearch? = null) = ChatTools(trip, web) {}

    @Test
    fun `search ranks by match and worth, filters region with day trips and skips planned places`() {
        val r = tools().search(buildJsonObject { put("regionId", "r-kyoto") })
        assertTrue("s-kiyo" in r && "s-deer" in r && "s-market" in r)
        assertFalse("s-ramen" in r, "planned on 19.05.")
        assertFalse("s-shib" in r)
        assertTrue(r.indexOf("s-kiyo") < r.indexOf("s-market"), "MUST first")

        val q = tools().search(buildJsonObject { put("query", "Tempel") })
        assertTrue(q.lines().drop(1).first().contains("s-kiyo"))

        val withPlanned = tools().search(buildJsonObject { put("regionId", "Kyoto"); put("includePlanned", true) })
        assertTrue("s-ramen" in withPlanned && "eingeplant 2027-05-19" in withPlanned, "region by name works too")
    }

    @Test
    fun `openOn drops places known to be closed that weekday and unknown query falls back`() {
        // 2027-05-19 is a Wednesday: the market (Mondays only) is closed.
        val r = tools().search(buildJsonObject { put("regionId", "r-kyoto"); put("openOn", "2027-05-19") })
        assertFalse("s-market" in r)
        assertTrue("s-kiyo" in r)
        val none = tools().search(buildJsonObject { put("query", "Onsen"); put("regionId", "r-kyoto") })
        assertTrue(none.startsWith("Keine Treffer"))
        assertEquals("Unbekannte Region r-x. Gültige ids stehen in der Reiseübersicht.", tools().search(buildJsonObject { put("regionId", "r-x") }))
    }

    @Test
    fun `place cards only for known ids and web cards only for found urls`() {
        val web = WebSearch { _, _ -> listOf(WebSource("https://example.org/onsen", "Onsen Guide", listOf("Beste Onsen in Kyoto"))) }
        val t = tools(web)
        t.run("show_place_cards", """{"ids":["s-kiyo","s-nope","s-kiyo"]}""")
        t.run("web_search", """{"objective":"Onsen in Kyoto","queries":["kyoto onsen"]}""")
        t.run("show_web_cards", """{"items":[{"title":"Onsen","url":"https://example.org/onsen","snippet":"Tipps"},{"title":"Fake","url":"https://evil.example/x"}]}""")
        assertEquals(
            listOf(
                ChatCard(ChatCard.KIND_PLACE, "Kiyomizu-dera", savedPlaceId = "s-kiyo", subtitle = "Kyoto · Muss man sehen"),
                ChatCard(ChatCard.KIND_WEB, "Onsen", subtitle = "Tipps", url = "https://example.org/onsen"),
            ),
            t.cards,
        )
    }

    @Test
    fun `failed web search is reported to the model, not thrown`() {
        val t = tools(WebSearch { _, _ -> throw AiFailed("research_no_credit") })
        assertTrue(t.run("web_search", """{"objective":"x"}""").startsWith("Websuche fehlgeschlagen (research_no_credit)"))
        assertEquals(listOf("search_saved_places", "get_place", "get_day", "show_place_cards"), tools(null).definitions.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `answer runs the tool loop and returns text with cards`() {
        val model = ScriptedModel(
            listOf(
                calls("search_saved_places" to """{"regionId":"r-kyoto"}"""),
                calls("show_place_cards" to """{"ids":["s-kiyo"]}"""),
                text("Geh zum **Kiyomizu-dera**."),
            ),
        )
        val lines = mutableListOf<String>()
        val a = ChatAgent(model, null) { "2027-05-19" }.answer(
            listOf(user("Was soll ich heute machen?", ChatContext(ChatScreens.DAY, date = "2027-05-19", label = "Tag 19.05."))), trip,
        ) { lines += it }
        assertEquals("Geh zum **Kiyomizu-dera**.", a.text)
        assertEquals(listOf("s-kiyo"), a.cards.map { it.savedPlaceId })
        assertTrue("Durchsuche hinterlegte Vorschläge …" in lines)
        // Tool results went back to the model with their call ids.
        val last = model.seen.last()
        assertTrue(last.any { it["role"]?.jsonPrimitive?.content == "tool" && it["tool_call_id"]?.jsonPrimitive?.content == "c0" })
        val system = model.seen.first().first()["content"]!!.jsonPrimitive.content
        assertTrue("Tag: Mittwoch, 19.05.2027" in system && "Region Kyoto (r-kyoto)" in system && "12:00 Ramen Ichiran" in system && "Titel: Tempeltag" in system)
        assertTrue("Nara (r-nara), Tagesausflug von Kyoto" in system)
        assertFalse("SECRET-123" in system || "Erika" in system, "no booking data")
        assertTrue(model.seen.first()[1]["content"]!!.jsonPrimitive.content.startsWith("[Geöffnet bei: Tag 19.05.] "))
    }

    @Test
    fun `place context includes the dossier and endless tool calls are cut off`() {
        val model = ScriptedModel(listOf(calls("search_saved_places" to "{}")))
        val answer = runCatching {
            ChatAgent(model, null) { "2027-05-19" }.answer(listOf(user("Tipps?", ChatContext(ChatScreens.PLACE, savedPlaceId = "s-kiyo"))), trip)
        }
        // The model never stops calling tools and gives no text: no usable answer after the last round.
        assertFailsWith<AiFailed> { answer.getOrThrow() }
        assertEquals(ChatAgent.MAX_ROUNDS + 1, model.seen.size)
        val system = model.seen.first().first()["content"]!!.jsonPrimitive.content
        assertTrue("Geheimtipps: Hinterer Pfad ist leer" in system && "Fazit: Ikonisch" in system)
    }

    @Test
    fun `day context knows the stay, the whole plan and distances`() {
        val system = ChatAgent(ScriptedModel(listOf(text("ok"))), null) { "2027-05-19" }.systemPrompt(trip, ChatContext(ChatScreens.DAY, date = "2027-05-19"))
        assertTrue("Übernachtung: Hotel Gion (Kyoto), 2027-05-18 bis 2027-05-22, Gionmachi, Higashiyama, Kyoto, Koordinaten 35.00370,135.77880" in system, system)
        assertTrue("Bezugspunkt für Entfernungen: Unterkunft Hotel Gion" in system)
        assertTrue("12:00 Ramen Ichiran [id=s-ramen], 810 m von der Unterkunft" in system, system)
        assertTrue("Offene Vorschläge für diesen Tag" in system && "Kiyomizu-dera | Kyoto | SIGHT | 1,1 km entfernt" in system, system)
        // The whole trip: every day with its night and the train on the last day (without booking numbers).
        assertTrue("Unterkünfte:\n- Hotel Gion" in system)
        assertTrue("Sa 22.05. (2027-05-22): Kyoto · Zug JR: Kyoto 09:10 → Tokyo 11:20" in system, system)
        assertFalse("SECRET" in system)
    }

    @Test
    fun `near search keeps the radius around the stay and sorts by distance`() {
        val t = ChatTools(trip, null, trip.dayAnchor("2027-05-19")) {}
        val r = t.search(buildJsonObject { put("near", "2027-05-19"); put("maxKm", 20) })
        assertTrue(r.startsWith("Umkreis 20 km um Unterkunft Hotel Gion (1 ohne Koordinaten ausgelassen)."), r)
        assertFalse("s-deer" in r, "Nara is 36 km away")
        assertTrue(r.indexOf("s-kiyo") < r.indexOf("s-market"))
        assertTrue("s-deer" in t.search(buildJsonObject { put("near", "s-kiyo"); put("maxKm", 50) }))
        assertTrue(t.search(buildJsonObject { put("near", "34.5,135.5"); put("maxKm", 1) }).startsWith("Keine passenden hinterlegten Vorschläge im Umkreis von 1,0 km"))
        assertTrue(t.search(buildJsonObject { put("near", "s-shib") }).startsWith("Unbekannter Bezugspunkt"))
        // Without near the rows still show the distance to where the chat was opened.
        assertTrue("Nara-Park | Nara | SIGHT | 36 km entfernt" in t.search(buildJsonObject { put("regionId", "r-kyoto") }))
        assertTrue("Übernachtung: Hotel Gion" in t.run("get_day", """{"date":"2027-05-20"}"""))
    }

    @Test
    fun `cards carry a checked plan suggestion and web finds get a location near the trip`() {
        val t = ChatTools(trip, null) {}
        t.run("show_place_cards", """{"items":[{"id":"s-kiyo","date":"2027-05-19","after":"s-ramen"},{"id":"s-market","date":"2030-01-01","after":"s-kiyo"}]}""")
        assertEquals(listOf("2027-05-19" to "s-ramen", null to null), t.cards.map { it.planDate to it.planAfter })

        val hits = mapOf(
            "Gion Onsen, Kyoto" to GeoHit(35.0040, 135.7760, "Gion, Kyoto"),
            "Fuji Onsen" to GeoHit(35.36, 138.73, "Fujiyoshida"), // 200 km away: not the trip's
            "Kiyomizu" to GeoHit(34.9950, 135.7851, null), // the saved temple
        )
        val web = WebSearch { _, _ -> hits.keys.map { WebSource("https://x.example/$it", it, listOf("…")) } }
        val w = ChatTools(trip, web, geocoder = { q -> hits[q] }) {}
        w.run("web_search", """{"objective":"Onsen"}""")
        val r = w.run(
            "show_web_cards",
            """{"items":[
              {"title":"Gion Onsen","url":"https://x.example/Gion Onsen, Kyoto","place":"Gion Onsen, Kyoto","category":"sight","date":"2027-05-20"},
              {"title":"Fuji","url":"https://x.example/Fuji Onsen","place":"Fuji Onsen"},
              {"title":"Tempel","url":"https://x.example/Kiyomizu","place":"Kiyomizu"}]}""",
        )
        assertTrue("Lage nicht gefunden (nur als Link): Fuji Onsen" in r, r)
        val (gion, fuji, temple) = w.cards
        assertTrue(gion.canAdd && gion.regionId == "r-kyoto" && gion.category == "SIGHT" && gion.planDate == "2027-05-20" && gion.address == "Gion, Kyoto")
        assertFalse(fuji.canAdd)
        assertEquals("s-kiyo", temple.savedPlaceId, "already saved: linked, not added twice")
    }

    @Test
    fun `transit and route tools answer with times and are limited per answer`() {
        val asked = mutableListOf<de.wanderlogplus.shared.sync.TransitRequest>()
        val ways = object : ChatWays {
            override fun transit(req: de.wanderlogplus.shared.sync.TransitRequest): de.wanderlogplus.shared.sync.TransitResult {
                asked += req
                if (req.toStation == "無い") throw de.wanderlogplus.server.NoRoute()
                val kyoto = de.wanderlogplus.shared.sync.TransitStation("京都", "Kyoto")
                val nara = de.wanderlogplus.shared.sync.TransitStation("奈良", "Nara")
                return de.wanderlogplus.shared.sync.TransitResult(
                    kyoto, nara, req.datetime, req.searchType, walkToStationMeters = 1200,
                    routes = listOf(
                        de.wanderlogplus.shared.sync.TransitRoute(
                            "17:42", "18:30", 48, transfers = 0, fareYen = 1340, fastest = true,
                            legs = listOf(de.wanderlogplus.shared.sync.TransitLeg("JR奈良線", "JR Nara Line", from = kyoto, to = nara, departure = "17:42", arrival = "18:30", departurePlatform = "9")),
                        ),
                    ),
                )
            }
            override fun route(req: de.wanderlogplus.shared.sync.RouteRequest) =
                if (req.mode == de.wanderlogplus.shared.sync.TravelMode.DRIVE) throw de.wanderlogplus.server.RoutesNotConfigured()
                else de.wanderlogplus.shared.sync.RouteInfo(1500, 1900, "")
        }
        val t = ChatTools(trip, null, trip.dayAnchor("2027-05-19"), ways = ways) {}
        val r = t.run("transit", """{"from":"2027-05-19","to":"s-deer","datetime":"2027-05-19T17:30"}""")
        assertTrue("Verbindungen Unterkunft Hotel Gion → Nara-Park, Abfahrt ab 2027-05-19 17:30" in r, r)
        assertTrue("zum Bahnhof ca. 1,2 km" in r && "1) 17:42–18:30, 48 Min., ohne Umstieg, 1.340 ¥ [schnellste]: JR Nara Line Kyoto (京都) 17:42 Gleis 9 → Nara (奈良) 18:30" in r, r)
        assertEquals(35.0037, asked.single().from!!.lat)
        assertTrue(t.run("transit", """{"from":"kontext","toStation":"無い","datetime":"2027-05-19T23:00","searchType":"arrive"}""").startsWith("Keine Verbindung"))
        assertEquals("arrive", asked.last().searchType)
        assertTrue(t.run("transit", """{"from":"kontext","to":"s-kiyo"}""").startsWith("datetime fehlt"))
        t.run("transit", """{"from":"kontext","to":"s-kiyo","datetime":"2027-05-19T10:00"}""")
        assertTrue(t.run("transit", """{"from":"kontext","to":"s-kiyo","datetime":"2027-05-19T11:00"}""").startsWith("Schon 3 Fahrplanauskünfte"))

        assertEquals("Unterkunft Hotel Gion → Kiyomizu-dera: Zu Fuß: 25 Min., 1,9 km", t.run("route", """{"from":"kontext","to":"s-kiyo","mode":"WALK"}"""))
        assertTrue(t.run("route", """{"from":"kontext","to":"s-kiyo","mode":"DRIVE"}""").startsWith("Routen sind auf dem Server nicht eingerichtet; Luftlinie 1,1 km"))
        assertTrue("transit" in t.definitions.toString() && "transit" !in tools().definitions.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `openrouter tool turn parsing keeps calls and a clean history message`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":null,"reasoning":"x",
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_place","arguments":"{\"id\":\"s-kiyo\"}"}}]}}]}"""
        val turn = OpenRouterModel.toolTurn(body)
        assertNull(turn.text)
        assertEquals(listOf(ToolCall("call_1", "get_place", """{"id":"s-kiyo"}""")), turn.calls)
        assertNull(turn.message["reasoning"])
        assertEquals("call_1", (turn.message["tool_calls"] as JsonArray)[0].jsonObject["id"]!!.jsonPrimitive.content)
        val req = OpenRouterModel.toolRequestBody("m", listOf(buildJsonObject { put("role", "user"); put("content", "hi") }), JsonArray(listOf(JsonPrimitive("t"))))
        assertTrue(""""tools":["t"]""" in req)
    }
}
