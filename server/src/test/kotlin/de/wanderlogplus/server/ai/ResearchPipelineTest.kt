package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.sync.AssessConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResearchPipelineTest {
    private val query = ResearchQuery("Fushimi Inari Taisha", "伏見稲荷大社", "Kyoto", "Japan", "sight", "Torii-Tunnel", tripContext = "Region Kyoto; dort auch hinterlegt: Kiyomizu-dera")

    private val facts = FactSource { _, _ ->
        Facts(
            listOf(Fact("Früh morgens ist es leer.", listOf("https://a.jp")), Fact("Der Aufstieg dauert 2 Stunden.", listOf("https://b.jp"))),
            listOf("https://a.jp", "https://b.jp"),
        )
    }

    private val good = """{"description":"Shinto-Schrein mit Torii.","category":"SIGHT","criteria":[{"key":"crowds","score":2,"note":"voll"}],
        "worth":"MUST","verdict":"Ikonisch, früh hingehen.","insiderTips":["Hinterer Pfad"],"hiddenSpots":[],"goodToKnow":[],"tips":[],"hints":[],"nearbyPoints":[]}"""

    private class Scripted(private val answers: List<String>) : ChatModel {
        val asked = mutableListOf<String>()
        override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String {
            asked += (parts.single() as AiPart.Text).text
            return answers[minOf(asked.size - 1, answers.size - 1)]
        }
    }

    @Test
    fun `facts are judged by the text model into the usual outcome`() {
        val model = Scripted(listOf(good))
        val r = PipelineResearcher(facts, model).research(query, AssessConfig())!!
        assertEquals("MUST", r.worth)
        assertEquals("Ikonisch, früh hingehen.", r.dossier.verdict)
        assertEquals(listOf("https://a.jp", "https://b.jp"), r.dossier.sources)
        val asked = model.asked.single()
        assertTrue("Früh morgens ist es leer." in asked && "Der Aufstieg dauert 2 Stunden." in asked, asked)
        assertTrue("Kiyomizu-dera" in asked && "Fushimi Inari Taisha" in asked, asked)
    }

    @Test
    fun `a bad answer is asked again once`() {
        val model = Scripted(listOf("kein json", good))
        assertEquals("MUST", PipelineResearcher(facts, model).research(query, AssessConfig())!!.worth)
        assertEquals(2, model.asked.size)
    }

    @Test
    fun `two bad answers fail`() {
        val model = Scripted(listOf("kein json", """{"description":"ohne Urteil"}"""))
        assertEquals("ai_bad_answer", assertFailsWith<AiFailed> { PipelineResearcher(facts, model).research(query, AssessConfig()) }.code)
        assertEquals(2, model.asked.size)
    }

    @Test
    fun `a japanese answer is asked again in german`() {
        val japanese = """{"description":"浜松町にある大衆酒場。中華そばが人気で、仕事帰りに一杯飲むのに最適です。","worth":"WORTH","verdict":"気軽に立ち寄れる良い店です。"}"""
        val model = Scripted(listOf(japanese, good))
        val r = PipelineResearcher(facts, model).research(query, AssessConfig())!!
        assertEquals("Ikonisch, früh hingehen.", r.dossier.verdict)
        assertEquals(2, model.asked.size)
        assertTrue("auf Deutsch" in model.asked[1], model.asked[1])
    }

    @Test
    fun `japanese names inside german text are fine`() {
        assertEquals(false, mostlyJapanese("Stand Fuji (浜松町 スタンド富士) ist eine moderne Kneipen-Kantine in Hamamatsucho."))
        assertEquals(true, mostlyJapanese("浜松町にある大衆酒場。中華そばが人気です。"))
        assertEquals(false, mostlyJapanese(""))
    }

    @Test
    fun `no facts means no outcome`() {
        val model = Scripted(listOf(good))
        assertNull(PipelineResearcher({ _, _ -> null }, model).research(query, AssessConfig()))
        assertTrue(model.asked.isEmpty())
    }
}
