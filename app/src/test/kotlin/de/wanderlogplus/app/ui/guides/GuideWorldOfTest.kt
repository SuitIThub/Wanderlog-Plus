package de.wanderlogplus.app.ui.guides

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.guide.GuideMatch
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideTarget
import de.wanderlogplus.shared.model.GuideTargetType
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideWorldOfTest {
    private fun rec(type: EntityType, id: String, fields: Map<String, JsonElement>) =
        EntityRecord(type, id, fields.mapValues { (_, v) -> FieldValue(v, Hlc(1, 0, "a").encode()) })

    @Test
    fun snapshotWorldHasEverythingMatchingNeeds() {
        val snapshot = TripSnapshot.from(
            listOf(
                rec(EntityType.PLACE, "p-ryo", EntityCodec.fields(Place("p-ryo", name = "Tsutaya Ryokan"))),
                rec(EntityType.STAY, "st", EntityCodec.fields(Stay("st", "p-ryo", checkIn = "2027-05-19", checkOut = "2027-05-21"))),
                rec(EntityType.TRAIN, "tr", EntityCodec.fields(Train("tr", number = "Nozomi 21", depart = Endpoint("Tokyo", date = "2027-05-19")))),
                rec(EntityType.GUIDE, "g-ryo", EntityCodec.fields(Guide("g-ryo", topic = "ryokan", title = "Im Ryokan"))),
                rec(EntityType.GUIDE, "g-shin", EntityCodec.fields(Guide("g-shin", topic = "shinkansen", title = "Shinkansen"))),
            ),
        )
        val w = snapshot.guideWorld()
        assertEquals(listOf("g-ryo"), GuideMatch.guidesFor(GuideTarget(GuideTargetType.STAY, "st"), w).map { it.id })
        assertEquals(listOf("g-shin"), GuideMatch.guidesFor(GuideTarget(GuideTargetType.TRAIN, "tr"), w).map { it.id })
    }
}
