package de.wanderlogplus.server.ai

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactsTest {
    private val query = FactQuery("Wie verhält man sich im Onsen?", listOf("onsen etiquette", "温泉 マナー"), listOf("Tattoos", "Handtuch"))

    private class FakeTransport(val answers: MutableList<Pair<Int, String>>) : ParallelTransport {
        val calls = mutableListOf<Triple<String, String, String?>>()
        override fun send(method: String, path: String, body: String?, timeout: Duration): Pair<Int, String> {
            calls += Triple(method, path, body)
            return answers.removeAt(0)
        }
    }

    private val searchAnswer = """{"results":[
        {"url":"https://s1.jp","title":"Onsen guide","excerpts":["Wash before entering."]},
        {"url":"https://s2.jp","title":null,"excerpts":[]}]}"""

    @Test
    fun `parallel facts send search then a task whose schema only has facts`() {
        val result = """{"output":{"content":{"facts":[{"text":"Tattoos oft verboten","sources":["https://a.jp"]},{"text":" ","sources":[]}]},
            "basis":[{"citations":[{"url":"https://b.jp"}]}]}}"""
        val t = FakeTransport(mutableListOf(200 to searchAnswer, 200 to """{"run_id":"r1"}""", 200 to result))
        val f = ParallelFacts(t).facts(query)!!
        assertEquals(listOf(Fact("Tattoos oft verboten", listOf("https://a.jp"))), f.items)
        assertEquals(setOf("https://a.jp", "https://b.jp", "https://s1.jp", "https://s2.jp"), f.sources.toSet())
        assertEquals(f.sources.size, f.sources.distinct().size)
        val task = t.calls[1].third!!
        assertTrue("\"facts\"" in task && "Wash before entering." in task && "Tattoos" in task, task)
        assertFalse("\"worth\"" in task || "\"verdict\"" in task)
        assertEquals(listOf("/v1/search", "/v1/tasks/runs", "/v1/tasks/runs/r1/result?timeout=240"), t.calls.map { it.second })
    }

    @Test
    fun `parallel facts return null when the task has no facts`() {
        val t = FakeTransport(mutableListOf(200 to searchAnswer, 200 to """{"run_id":"r1"}""", 200 to """{"output":{"content":{"facts":[]}}}"""))
        assertNull(ParallelFacts(t).facts(query))
    }

    @Test
    fun `parallel facts map 402 to research_no_credit`() {
        val t = FakeTransport(mutableListOf(200 to searchAnswer, 402 to "{}"))
        assertEquals("research_no_credit", assertFailsWith<AiFailed> { ParallelFacts(t).facts(query) }.code)
    }

    @Test
    fun `model facts parse the model answer`() {
        var web: Boolean? = null
        var asked = ""
        val model = ChatModel { _, parts, webSearch ->
            web = webSearch
            asked = (parts.single() as AiPart.Text).text
            """{"facts":[{"text":"x","sources":["https://c.jp"]}]}"""
        }
        val f = ModelFacts(model).facts(query)!!
        assertEquals(true, web)
        assertEquals("x", f.items.single().text)
        assertEquals(listOf("https://c.jp"), f.sources)
        assertTrue("Wie verhält man sich im Onsen?" in asked && "Handtuch" in asked)
    }
}
