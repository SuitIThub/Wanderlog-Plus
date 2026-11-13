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
        val systems = mutableListOf<String>()
        override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String {
            val user = (parts.single() as AiPart.Text).text
            asked += user
            systems += system
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

    private fun withLinks(title: String, sections: Int = 1) = """{"title":"$title","summary":"Kurz.",
        "sections":[${(1..sections).joinToString(",") { """{"heading":"A$it","text":"t","dos":["Waschen"],"donts":["Handtuch ins Wasser"]}""" }}],
        "phrases":[{"german":"Wo ist das Bad?","japanese":"お風呂はどこですか？","romaji":"ofuro wa doko desu ka?"}],
        "links":[{"name":"Japan Transit","kind":"app","androidPackage":"jp.co.jorudan.jtp"},{"name":"Erfunden","kind":"web","url":"https://erfunden.example/"}]}"""

    private val probe = UrlProbe { it == "https://play.google.com/store/apps/details?id=jp.co.jorudan.jtp" }

    private val existing = Guide(
        "g1", topic = "onsen", title = "Onsen", summary = "Alt.", sources = listOf("https://alt.jp"),
        sections = listOf(
            de.wanderlogplus.shared.model.GuideSection("A1", "t", dos = listOf("Waschen"), donts = listOf("Handtuch ins Wasser")),
            de.wanderlogplus.shared.model.GuideSection("A2", "t", dos = listOf("Waschen"), donts = listOf("Handtuch ins Wasser")),
        ),
        phrases = listOf(de.wanderlogplus.shared.model.GuidePhrase("Wo ist das Bad?", "お風呂はどこですか？", "ofuro wa doko desu ka?")),
        pinnedTo = listOf(GuideTarget(GuideTargetType.PLACE, "s1")),
    )

    private val additions = listOf(de.wanderlogplus.shared.sync.GuideAddition("Tattoo-Sticker helfen in manchen Onsen.", listOf("https://neu.jp", "https://alt.jp")))

    @Test
    fun `written links are checked`() {
        val g = GuideWriter(facts, Scripted { withLinks("Onsen") }, LinkCheck(probe)).write(GuideAsk(topic = "onsen"), "", null)
        assertEquals(listOf("Japan Transit"), g.links.map { it.name })
    }

    @Test
    fun `without a link check no link is stored`() {
        val g = GuideWriter(facts, Scripted { withLinks("Onsen") }).write(GuideAsk(topic = "onsen"), "", null)
        assertTrue(g.links.isEmpty())
    }

    @Test
    fun `merging does not research and keeps pins`() {
        var researched = 0
        val counting = FactSource { q, s -> researched++; facts.facts(q, s) }
        val model = Scripted { withLinks("Onsen", sections = 3) }
        val g = GuideWriter(counting, model, LinkCheck(probe)).write(GuideAsk(guideId = "g1", additions = additions), "", existing)
        assertEquals(0, researched)
        assertTrue("Tattoo-Sticker helfen" in model.asked.single())
        assertTrue("A2" in model.asked.single())
        assertEquals("g1", g.id)
        assertEquals(existing.pinnedTo, g.pinnedTo)
        assertEquals(listOf("https://alt.jp", "https://neu.jp"), g.sources)
        assertEquals(3, g.sections.size)
        assertEquals(listOf("Japan Transit"), g.links.map { it.name })
    }

    @Test
    fun `a merge that loses content fails`() {
        val model = Scripted { withLinks("Onsen", sections = 1) }
        val e = kotlin.runCatching { GuideWriter(facts, model).write(GuideAsk(guideId = "g1", additions = additions), "", existing) }.exceptionOrNull()
        assertEquals("ai_bad_answer", (e as AiFailed).code)
        assertEquals(2, model.asked.size)
    }

    @Test
    fun `keepsContent compares sections dos donts and phrases`() {
        assertTrue(keepsContent(existing, existing.copy(sections = existing.sections + existing.sections)))
        assertTrue(!keepsContent(existing, existing.copy(phrases = emptyList())))
        assertTrue(!keepsContent(existing, existing.copy(sections = existing.sections.map { it.copy(dos = emptyList()) })))
    }

    @Test
    fun `a new guide with additions puts them into the prompt`() {
        val model = Scripted { withLinks("Geld") }
        val g = GuideWriter(facts, model).write(GuideAsk(title = "Geld", additions = additions), "", null)
        assertTrue("Geprüfte Ergänzungen" in model.asked.single() && "Tattoo-Sticker helfen" in model.asked.single())
        assertTrue("https://neu.jp" in g.sources)
    }

    private val tmp = Files.createTempDirectory("wlp-guides")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))
    private val sync = SyncService(store)

    private fun jobs(model: ChatModel) = AiJobs(
        store, null, object : ReelMedia {
            override fun download(url: String, dir: java.nio.file.Path) = throw DownloadFailed()
            override fun extract(d: Downloaded, dir: java.nio.file.Path) = throw DownloadFailed()
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
    fun `a failed save does not stop the other guides`() {
        var calls = 0
        val j = AiJobs(
            store, null, object : ReelMedia {
                override fun download(url: String, dir: java.nio.file.Path) = throw DownloadFailed()
                override fun extract(d: Downloaded, dir: java.nio.file.Path) = throw DownloadFailed()
            },
            tmp.resolve("work-x"), executor = Executors.newSingleThreadExecutor(),
            writeResults = { edits -> if (calls++ == 0) error("disk full") else sync.writeAsServer(ASSISTANT_AUTHOR, edits) },
            guideWriter = GuideWriter(facts, Scripted { answer("Guide") }),
        )
        val started = j.startGuides(listOf(GuideAsk(topic = "onsen"), GuideAsk(topic = "ryokan")))
        j.drain()
        val job = j.get(started.id)!!
        assertEquals("DONE", job.status)
        assertEquals(listOf("FAILED", "DONE"), job.items.map { it.state })
        assertEquals(listOf("ryokan"), storedGuides().map { it.topic })
    }

    @Test
    fun `the links job writes only links`() {
        sync.writeAsServer(ASSISTANT_AUTHOR, listOf(de.wanderlogplus.server.ServerEdit(EntityType.GUIDE, "g1", EntityCodec.fields(existing), create = true)))
        val other = Guide("g2", title = "Rolltreppen", summary = "Links stehen.", sections = listOf(de.wanderlogplus.shared.model.GuideSection("Tokio", "Links stehen.")))
        sync.writeAsServer(ASSISTANT_AUTHOR, listOf(de.wanderlogplus.server.ServerEdit(EntityType.GUIDE, "g2", EntityCodec.fields(other), create = true)))
        val model = Scripted { user ->
            if ("Rolltreppen" in user) """{"links":[]}"""
            else """{"links":[{"name":"Japan Transit","kind":"app","androidPackage":"jp.co.jorudan.jtp"}]}"""
        }
        val j = AiJobs(
            store, null, object : ReelMedia {
                override fun download(url: String, dir: java.nio.file.Path) = throw DownloadFailed()
                override fun extract(d: Downloaded, dir: java.nio.file.Path) = throw DownloadFailed()
            },
            tmp.resolve("work-l"), executor = Executors.newSingleThreadExecutor(),
            writeResults = { edits -> sync.writeAsServer(ASSISTANT_AUTHOR, edits) },
            guideWriter = GuideWriter(facts, model, LinkCheck(probe)),
        )
        val started = j.startGuideLinks(emptyList())
        assertEquals("guides", started.kind)
        assertEquals(setOf("g1", "g2"), started.guideIds.toSet())
        j.drain()
        val job = j.get(started.id)!!
        assertEquals("DONE", job.status)
        assertEquals(setOf("1 Link", "keine Links"), job.items.map { it.detail }.toSet())
        val g1 = storedGuides().single { it.id == "g1" }
        assertEquals(listOf("Japan Transit"), g1.links.map { it.name })
        assertEquals(existing.copy(links = g1.links), g1)
        assertEquals(other, storedGuides().single { it.id == "g2" })
    }

    @Test
    fun `a tip for a topic with a guide merges into that guide`() {
        val j = jobs(Scripted { answer("Onsen & Sentō") })
        j.startGuides(listOf(GuideAsk(topic = "onsen"))); j.drain()
        val first = storedGuides().single()
        val merge = jobs(Scripted { user -> if ("Ergänzungen" in user) answer("Onsen & Sentō (ergänzt)") else error("researched") })
        merge.startGuides(listOf(GuideAsk(topic = "onsen", additions = additions))); merge.drain()
        assertEquals(listOf(first.id), storedGuides().map { it.id })
        assertEquals("Onsen & Sentō (ergänzt)", storedGuides().single().title)
    }

    @Test
    fun `asking for an existing topic rewrites that guide`() {
        val j = jobs(Scripted { answer("Onsen & Sentō") })
        j.startGuides(listOf(GuideAsk(topic = "onsen"))); j.drain()
        val first = storedGuides().single()
        j.startGuides(listOf(GuideAsk(topic = "onsen", wish = "kürzer"))); j.drain()
        assertEquals(listOf(first.id), storedGuides().map { it.id })
    }
    @Test
    fun `a rewrite names its guide from the start and stamps when it was written`() {
        val j = jobs(Scripted { answer("Onsen & Sentō") })
        j.startGuides(listOf(GuideAsk(topic = "onsen"))); j.drain()
        val first = storedGuides().single()
        assertTrue(first.writtenAt > 0)
        val again = j.startGuides(listOf(GuideAsk(topic = "onsen", wish = "kürzer", guideId = first.id)))
        assertEquals(listOf(first.id), again.guideIds)
        j.drain()
        assertTrue(storedGuides().single().writtenAt >= first.writtenAt)
    }

    @Test
    fun `sections carry details`() {
        val model = Scripted { """{"title":"Onsen","summary":"Erst waschen.
Leise sein.","sections":[{"heading":"Ablauf","text":"So.","details":"Hintergrund"}]}""" }
        val g = GuideWriter(facts, model).write(GuideAsk(topic = "onsen"), "", null)
        assertEquals("Hintergrund", g.sections.single().details)
    }

    @Test
    fun `prompts carry the format rules and the title is taken as it is`() {
        val model = Scripted { answer("Shinkansen & Suica: Tokyo – Nagoya") }
        val g = GuideWriter(facts, model).write(GuideAsk(title = "Shinkansen"), "", null)
        assertEquals("Shinkansen & Suica: Tokyo – Nagoya", g.title)
        val merging = Scripted { withLinks("Onsen", sections = 3) }
        GuideWriter(facts, merging).write(GuideAsk(guideId = "g1", additions = additions), "", existing)
        for (system in listOf(model.systems.single(), merging.systems.single())) {
            for (needle in listOf("Shinkansen & Suica", "Onsen-Etikette", "kein Muster", "Auf einen Blick", "details")) {
                assertTrue(needle in system, "$needle fehlt")
            }
        }
    }

    @Test
    fun `merge keeps details`() {
        val old = existing.copy(sections = existing.sections.map { it.copy(details = "Hintergrund") })
        val model = Scripted { """{"title":"Onsen","summary":"Kurz.","sections":[
            {"heading":"A1","text":"t","dos":["Waschen"],"donts":["Handtuch ins Wasser"],"details":"Hintergrund"},
            {"heading":"A2","text":"t","dos":["Waschen"],"donts":["Handtuch ins Wasser"],"details":"Hintergrund"}],
            "phrases":[{"german":"Wo ist das Bad?","japanese":"お風呂はどこですか？","romaji":"ofuro wa doko desu ka?"}]}""" }
        val g = GuideWriter(facts, model).write(GuideAsk(guideId = "g1", additions = additions), "", old)
        assertTrue("Hintergrund" in model.asked.single())
        assertEquals(listOf("Hintergrund", "Hintergrund"), g.sections.map { it.details })
    }

    private val tidyAnswer = """{"title":"Onsen","summary":"Erst waschen.
Leise sein.
Kein Handtuch ins Wasser.","sections":[
        {"heading":"Ablauf","text":"Waschen, dann baden.","dos":["Waschen"],"donts":["Handtuch ins Wasser"],"details":"Früher …"},
        {"heading":"Tattoos","text":"Oft verboten.","details":"Sticker helfen manchmal."}],
        "phrases":[{"german":"Wo ist das Bad?","japanese":"お風呂はどこですか？","romaji":"ofuro wa doko desu ka?"}]}"""

    @Test
    fun `tidy reshapes and keeps the rest`() {
        val old = existing.copy(
            title = "Onsen & Sentō in Japan: Kyoto, Hakone", parentId = "p1", hiddenFrom = listOf(GuideTarget(GuideTargetType.STAY, "st")),
            extraTriggers = listOf(GuideTrigger.AnyStay), links = listOf(de.wanderlogplus.shared.model.GuideLink("Japan Transit", de.wanderlogplus.shared.model.GuideLinkKind.APP)),
            writtenAt = 7,
        )
        val g = GuideWriter(facts, Scripted { tidyAnswer }).tidy(old)
        assertEquals("Onsen", g.title)
        assertEquals(listOf("Ablauf", "Tattoos"), g.sections.map { it.heading })
        assertEquals("Sticker helfen manchmal.", g.sections[1].details)
        assertEquals(old.copy(title = g.title, summary = g.summary, sections = g.sections, phrases = g.phrases), g)
    }

    @Test
    fun `tidy rejects lost phrases`() {
        val model = Scripted { tidyAnswer.replace("""{"german":"Wo ist das Bad?","japanese":"お風呂はどこですか？","romaji":"ofuro wa doko desu ka?"}""", "") }
        val e = kotlin.runCatching { GuideWriter(facts, model).tidy(existing) }.exceptionOrNull()
        assertEquals("ai_bad_answer", (e as AiFailed).code)
    }

    @Test
    fun `tidy prompt asks to keep all content`() {
        val model = Scripted { tidyAnswer }
        GuideWriter(facts, model).tidy(existing)
        assertTrue("kein Inhalt geht verloren" in model.systems.first())
        assertTrue(GuideWriter.FORMAT_RULES in model.systems.first())
        assertTrue("A2" in model.asked.first())
    }

    @Test
    fun `tidyKeeps checks phrases sections and summary`() {
        assertTrue(tidyKeeps(existing, existing))
        assertTrue(!tidyKeeps(existing, existing.copy(phrases = emptyList())))
        assertTrue(!tidyKeeps(existing, existing.copy(sections = emptyList())))
        assertTrue(!tidyKeeps(existing, existing.copy(summary = "")))
    }

    private fun tidyJobs(model: ChatModel) = AiJobs(
        store, null, object : ReelMedia {
            override fun download(url: String, dir: java.nio.file.Path) = throw DownloadFailed()
            override fun extract(d: Downloaded, dir: java.nio.file.Path) = throw DownloadFailed()
        },
        tmp.resolve("work-t"), executor = Executors.newSingleThreadExecutor(),
        writeResults = { edits -> sync.writeAsServer(ASSISTANT_AUTHOR, edits) },
        guideWriter = GuideWriter(facts, model),
    )

    private fun storeTwoGuides(): Guide {
        sync.writeAsServer(ASSISTANT_AUTHOR, listOf(de.wanderlogplus.server.ServerEdit(EntityType.GUIDE, "g1", EntityCodec.fields(existing.copy(writtenAt = 7)), create = true)))
        val other = existing.copy(id = "g2", title = "Rolltreppen in Japan: Tokyo und Osaka", pinnedTo = emptyList())
        sync.writeAsServer(ASSISTANT_AUTHOR, listOf(de.wanderlogplus.server.ServerEdit(EntityType.GUIDE, "g2", EntityCodec.fields(other), create = true)))
        return other
    }

    @Test
    fun `guide tidy writes each guide`() {
        storeTwoGuides()
        val j = tidyJobs(Scripted { tidyAnswer })
        val started = j.startGuideTidy(emptyList())
        assertEquals("guides", started.kind)
        assertEquals(setOf("g1", "g2"), started.guideIds.toSet())
        j.drain()
        assertEquals("DONE", j.get(started.id)!!.status)
        val stored = storedGuides().associateBy { it.id }
        assertEquals(setOf("Onsen"), stored.values.map { it.title }.toSet())
        assertEquals(7, stored.getValue("g1").writtenAt)
        assertEquals(existing.pinnedTo, stored.getValue("g1").pinnedTo)
    }

    @Test
    fun `guide tidy keeps going after one failure`() {
        val other = storeTwoGuides()
        val noPhrases = tidyAnswer.replace("""{"german":"Wo ist das Bad?","japanese":"お風呂はどこですか？","romaji":"ofuro wa doko desu ka?"}""", "")
        val j = tidyJobs(Scripted { user -> if ("Rolltreppen" in user) noPhrases else tidyAnswer })
        val started = j.startGuideTidy(emptyList())
        j.drain()
        val job = j.get(started.id)!!
        assertEquals("DONE", job.status)
        assertEquals(setOf("DONE", "FAILED"), job.items.map { it.state }.toSet())
        assertEquals(other, storedGuides().single { it.id == "g2" })
        assertEquals("Onsen", storedGuides().single { it.id == "g1" }.title)
    }

    @Test
    fun `only the given guide is tidied`() {
        val other = storeTwoGuides()
        val j = tidyJobs(Scripted { tidyAnswer })
        j.startGuideTidy(listOf("g1")); j.drain()
        assertEquals(other, storedGuides().single { it.id == "g2" })
        assertEquals("Onsen", storedGuides().single { it.id == "g1" }.title)
    }
}
