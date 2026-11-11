package de.wanderlogplus.shared.model

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import kotlin.test.Test
import kotlin.test.assertEquals

class GuidesTest {
    private fun roundTrip(type: EntityType, id: String, fields: Map<String, kotlinx.serialization.json.JsonElement>) =
        EntityRecord(type, id, fields.mapValues { (_, v) -> FieldValue(v, Hlc(1, 0, "a").encode()) })

    @Test
    fun `guide fields survive the codec`() {
        val g = Guide(
            id = "g1", topic = "onsen", parentId = null, title = "Onsen & Sentō", summary = "Erst waschen, dann baden.",
            sections = listOf(GuideSection("Vor dem Bad", "Gründlich waschen.", dos = listOf("Hocker benutzen"), donts = listOf("Handtuch ins Wasser"))),
            phrases = listOf(GuidePhrase("Darf ich mit Tattoo baden?", "タトゥーがあっても入れますか？", "tatū ga atte mo hairemasu ka?", "Rezeption")),
            sources = listOf("https://a.jp"),
            extraTriggers = listOf(
                GuideTrigger.Text(listOf("ryokan"), TriggerOn.STAY),
                GuideTrigger.Category(PlaceCategory.FOOD),
                GuideTrigger.AnyStay,
                GuideTrigger.Train(listOf("nozomi")),
                GuideTrigger.AnyFlight,
                GuideTrigger.Always,
            ),
            pinnedTo = listOf(GuideTarget(GuideTargetType.DAY, "2027-05-19"), GuideTarget(GuideTargetType.PLACE, "s-kiyo")),
            hiddenFrom = listOf(GuideTarget(GuideTargetType.TRAIN, "tr")),
        )
        val record = roundTrip(EntityType.GUIDE, g.id, EntityCodec.fields(g))
        assertEquals(g, EntityCodec.decode<Guide>(record))
        val linked = g.copy(links = listOf(GuideLink("Japan Transit", GuideLinkKind.APP, androidPackage = "jp.co.jorudan.jtp", note = "Fahrplan")))
        assertEquals(linked, EntityCodec.decode<Guide>(roundTrip(EntityType.GUIDE, g.id, EntityCodec.fields(linked))))
        val prefs = GuidePrefs(dismissedTopics = listOf("burg"))
        assertEquals(prefs, EntityCodec.decode<GuidePrefs>(roundTrip(EntityType.GUIDE_PREFS, prefs.id, EntityCodec.fields(prefs))))
    }

    @Test
    fun `old guide json without links decodes`() {
        val j = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val g = j.decodeFromString(Guide.serializer(), """{"id":"g1","title":"Onsen"}""")
        assertEquals(emptyList(), g.links)
    }

    @Test
    fun `old job and ask json without tips and additions decode`() {
        val j = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val job = j.decodeFromString(de.wanderlogplus.shared.sync.AiJobInfo.serializer(), """{"id":"j","kind":"reel","createdAt":1,"status":"DONE"}""")
        assertEquals(emptyList(), job.tips)
        val ask = j.decodeFromString(de.wanderlogplus.shared.sync.GuideAsk.serializer(), """{"topic":"onsen"}""")
        assertEquals(emptyList(), ask.additions)
        val tip = de.wanderlogplus.shared.sync.ReelTip("Trinkgeld ist unüblich", verdict = de.wanderlogplus.shared.sync.TipVerdict.TRUE, guideId = "g1")
        assertEquals(tip, j.decodeFromString(de.wanderlogplus.shared.sync.ReelTip.serializer(), j.encodeToString(de.wanderlogplus.shared.sync.ReelTip.serializer(), tip)))
    }
}
