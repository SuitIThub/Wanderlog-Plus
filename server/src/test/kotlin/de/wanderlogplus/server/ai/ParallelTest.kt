package de.wanderlogplus.server.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParallelTest {
    private val query = ResearchQuery("Fushimi Inari Taisha", "伏見稲荷大社", "Kyoto", "Japan", "sight", "Torii-Tunnel")

    private val searchAnswer = """{"search_id":"s1","results":[
        {"url":"https://example.jp/fushimi","title":"Fushimi Inari guide","excerpts":["Go early to avoid crowds."]},
        {"url":"https://example.org/tips","title":null,"excerpts":[]}
    ]}"""

    private val resultAnswer = """{"run":{"run_id":"r1","status":"completed"},"output":{"type":"json",
        "content":{"description":"Shinto-Schrein mit tausenden Torii.","tips":["Früh kommen"," "],"hints":["Treppen"],"worth":"MUST","worthReason":"Ikonisch.","category":"SIGHT"},
        "basis":[{"field":"description","citations":[{"title":"Wiki","url":"https://de.wikipedia.org/wiki/Fushimi_Inari-taisha","excerpts":[]}],"confidence":"high"}]}}"""

    private class FakeTransport(val answers: MutableList<Pair<Int, String>>) : ParallelTransport {
        val calls = mutableListOf<Triple<String, String, String?>>()
        override fun send(method: String, path: String, body: String?, timeout: Duration): Pair<Int, String> {
            calls += Triple(method, path, body)
            return answers.removeAt(0)
        }
    }

    @Test fun searchThenTaskWithWaitingResult() {
        val t = FakeTransport(mutableListOf(200 to searchAnswer, 202 to """{"run_id":"r1","status":"queued"}""", 408 to "{}", 200 to resultAnswer))
        val r = ParallelResearcher(t).research(query)!!
        assertEquals("Shinto-Schrein mit tausenden Torii.", r.description)
        assertEquals(listOf("Früh kommen"), r.tips) // blank tip dropped
        assertEquals("MUST", r.worth)
        // Task citations first, then the search pages.
        assertEquals(listOf("https://de.wikipedia.org/wiki/Fushimi_Inari-taisha", "https://example.jp/fushimi", "https://example.org/tips"), r.sources)
        assertEquals(listOf("/v1/search", "/v1/tasks/runs", "/v1/tasks/runs/r1/result?timeout=240", "/v1/tasks/runs/r1/result?timeout=240"), t.calls.map { it.second })
        // The search excerpts are handed to the task.
        assertTrue(t.calls[1].third!!.contains("Go early to avoid crowds."))
    }

    @Test fun failedSearchStillRunsTheTask() {
        val t = FakeTransport(mutableListOf(500 to "", 202 to """{"run_id":"r1"}""", 200 to resultAnswer))
        val r = ParallelResearcher(t).research(query)!!
        assertEquals(listOf("https://de.wikipedia.org/wiki/Fushimi_Inari-taisha"), r.sources)
    }

    @Test fun missingCreditIsReported() {
        val t = FakeTransport(mutableListOf(200 to searchAnswer, 402 to "{}"))
        assertEquals("research_no_credit", assertFailsWith<AiFailed> { ParallelResearcher(t).research(query) }.code)
    }

    @Test fun neverFinishingTaskTimesOut() {
        val t = FakeTransport(mutableListOf(200 to searchAnswer, 202 to """{"run_id":"r1"}""", 408 to "", 408 to "", 408 to ""))
        assertEquals("research_timeout", assertFailsWith<AiFailed> { ParallelResearcher(t).research(query) }.code)
    }

    @Test fun textOutputAndEmptyOutput() {
        val text = """{"output":{"type":"text","content":"Ergebnis: {\"description\":\"D\",\"tips\":[],\"hints\":[],\"worth\":\"SKIP\",\"worthReason\":\"R\",\"category\":\"OTHER\"}"}}"""
        assertEquals("D", ParallelResearcher.parseResult(text)!!.first.description)
        assertNull(ParallelResearcher.parseResult("""{"run":{}}"""))
    }

    @Test fun requestBodies() {
        val search = Json.parseToJsonElement(ParallelResearcher.searchBody(query)).jsonObject
        assertEquals("fast", search["mode"]!!.jsonPrimitive.content)
        assertEquals("jp", search["advanced_settings"]!!.jsonObject["location"]!!.jsonPrimitive.content)
        assertTrue(search["search_queries"]!!.jsonArray.size in 2..3)
        val task = Json.parseToJsonElement(ParallelResearcher.taskBody(query, emptyList(), "base")).jsonObject
        assertEquals("base", task["processor"]!!.jsonPrimitive.content)
        val schema = task["task_spec"]!!.jsonObject["output_schema"]!!.jsonObject
        assertEquals("json", schema["type"]!!.jsonPrimitive.content)
        assertTrue(task["input"]!!.jsonPrimitive.content.contains("Deutsch"))
    }

    @Test fun assistantUsesTheResearcherAndKeepsSources() {
        val t = FakeTransport(mutableListOf(200 to searchAnswer, 202 to """{"run_id":"r1"}""", 200 to resultAnswer))
        val a = Assistant({ _, _, _ -> error("chat model must not research when Parallel is set") }, { GeoHit(34.9671, 135.7727, null) }, ParallelResearcher(t))
        val c = a.research(FoundPlace("Fushimi Inari Taisha", null, "Kyoto", "Japan", "sight", ""), TripContext(listOf("Kyoto"), listOf(Anchor("Hotel", 35.0, 135.77))))
        assertEquals(3, c.sources.size)
        assertTrue(c.onRoute)
    }
}
