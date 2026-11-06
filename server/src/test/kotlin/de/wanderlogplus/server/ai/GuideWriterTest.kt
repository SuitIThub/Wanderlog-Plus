package de.wanderlogplus.server.ai

import de.wanderlogplus.server.ASSISTANT_AUTHOR
import de.wanderlogplus.server.SqliteStore
import de.wanderlogplus.server.SyncService
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideTarget
import de.wanderlogplus.shared.model.GuideTargetType
import de.wanderlogplus.shared.model.GuideTrigger
import de.wanderlogplus.shared.model.TriggerOn
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.GuideAsk
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideWriterTest {
    private val facts = FactSource { _, _ -> Facts(listOf(Fact("Vor dem Baden gründlich waschen.", listOf("https://a.jp"))), listOf("https://a.jp", "https://b.jp")) }

    private fun answer(title: String, triggers: String = "[]") = """{"title":"$title","summary":"Erst waschen, dann baden.",
        "sections":[{"heading":"Ablauf","text":"So geht's.","dos":["Waschen"],"donts":["Handtuch ins Wasser"]}],
        "phrases":[{"german":"Wo ist das Bad?","japanese":"お風呂はどこですか？","romaji":"ofuro wa doko desu ka?","situation":"Rezeption"}],
        "extraTriggers":$triggers}"""

    private class Scripted(private val answer: (String) -> String) : ChatModel {
        val asked = mutableListOf<String>()
        override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String {
            val user = (parts.single() as AiPart.Text).text
            asked += user
            return answer(user)
        }
    }

    @Test
    fun `a topic guide is written from facts`() {
        val model = Scripted { answer("Onsen & Sentō") }
        val g = GuideWriter(facts, model).write(GuideAsk(topic = "onsen"), "Reise im Mai; Ryokan Kiso", null)
        assertEquals("onsen", g.topic)
        assertEquals("Onsen & Sentō", g.title)
        assertEquals(listOf("https://a.jp", "https://b.jp"), g.sources)
        assertEquals("お風呂はどこですか？", g.phrases.single().japanese)
        assertEquals(listOf("Waschen"), g.sections.single().dos)
        assertTrue(g.id.isNotBlank())
        val asked = model.asked.single()
        assertTrue("Vor dem Baden gründlich waschen." in asked && "Ryokan Kiso" in asked && "Onsen & Sentō" in asked, asked)
    }

    @Test
    fun `a free guide gets triggers from the model`() {
        val model = Scripted { answer("Kiso-Wanderwege", """[{"kind":"text","words":["nakasendo"],"on":"PLACE"},{"kind":"weird"}]""") }
        val g = GuideWriter(facts, model).write(GuideAsk(title = "Kiso-Wanderwege"), "", null)
        assertEquals(null, g.topic)
        assertEquals(listOf<GuideTrigger>(GuideTrigger.Text(listOf("nakasendo"), TriggerOn.PLACE)), g.extraTriggers)
    }

    @Test
    fun `rewriting keeps pins and hides`() {
        val old = Guide(
            "g1", topic = "onsen", title = "Alt", pinnedTo = listOf(GuideTarget(GuideTargetType.PLACE, "s1")),
            hiddenFrom = listOf(GuideTarget(GuideTargetType.STAY, "st")), extraTriggers = listOf(GuideTrigger.AnyStay),
        )
        val g = GuideWriter(facts, Scripted { answer("Neu") }).write(GuideAsk(guideId = "g1", wish = "mehr zu Tattoos"), "", old)
        assertEquals("g1", g.id)
        assertEquals("Neu", g.title)
        assertEquals(old.pinnedTo, g.pinnedTo)
        assertEquals(old.hiddenFrom, g.hiddenFrom)
        assertEquals(old.extraTriggers, g.extraTriggers)
    }

    private val tmp = Files.createTempDirectory("wlp-guides")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))
    private val sync = SyncService(store)

    private fun jobs(model: ChatModel) = AiJobs(
        store, null, object : ReelMedia {
            override fun download(url: String, dir: java.nio.file.Path) = throw DownloadFailed()
            override fun extract(video: java.nio.file.Path, dir: java.nio.file.Path) = throw DownloadFailed()
        },
        tmp.resolve("work"), executor = Executors.newSingleThreadExecutor(),
        writeResults = { edits -> sync.writeAsServer(ASSISTANT_AUTHOR, edits) },
        guideWriter = GuideWriter(facts, model),
    )

    private fun storedGuides() = store.all(EntityType.GUIDE).filter { !it.isDeleted }.map { EntityCodec.decode<Guide>(it) }

    @Test
    fun `guides job keeps going after a failed topic`() {
        val model = Scripted { user -> if ("Im Ryokan" in user) throw AiFailed("ai_failed") else answer(if ("Onsen" in user) "Onsen & Sentō" else "Shinkansen") }
        val j = jobs(model)
        val started = j.startGuides(listOf(GuideAsk(topic = "onsen"), GuideAsk(topic = "ryokan"), GuideAsk(topic = "shinkansen")))
        j.drain()
        val job = j.get(started.id)!!
        assertEquals("guides", job.kind)
        assertEquals("DONE", job.status)
        assertEquals(listOf("DONE", "FAILED", "DONE"), job.items.map { it.state })
        assertEquals(setOf("onsen", "shinkansen"), storedGuides().map { it.topic }.toSet())
        assertEquals(storedGuides().map { it.id }.toSet(), job.guideIds.toSet())
    }

    @Test
    fun `asking for an existing topic rewrites that guide`() {
        val j = jobs(Scripted { answer("Onsen & Sentō") })
        j.startGuides(listOf(GuideAsk(topic = "onsen"))); j.drain()
        val first = storedGuides().single()
        j.startGuides(listOf(GuideAsk(topic = "onsen", wish = "kürzer"))); j.drain()
        assertEquals(listOf(first.id), storedGuides().map { it.id })
    }
}
