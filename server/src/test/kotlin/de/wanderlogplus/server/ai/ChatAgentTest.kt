package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.Day
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
            Place("p-hotel", name = "Hotel Gion"),
            Place("p-kiyo", name = "Kiyomizu-dera", rating = 4.6, description = "Buddhistischer Tempel am Hang"),
            Place("p-ramen", name = "Ramen Ichiran", rating = 4.2),
            // Open Monday only (day 1).
            Place("p-market", name = "Nishiki Markt", openingPeriods = listOf(OpeningPeriod(1, "09:00", 1, "17:00"))),
            Place("p-deer", name = "Nara-Park", description = "Hirsche"),
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
        assertEquals(listOf("search_saved_places", "get_place", "show_place_cards"), tools(null).definitions.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content })
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
