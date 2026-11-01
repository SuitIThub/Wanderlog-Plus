package de.wanderlogplus.app.ui.share

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShareTargetsTest {
    private fun snapshot(days: List<Day> = emptyList()) = TripSnapshot.from(emptyList()).copy(
        trip = Trip("trip", "Japan", "2027-05-08", "2027-05-10"),
        places = mapOf("h" to Place("h", name = "Hotel Kyoto"), "a" to Place("a", name = "Zoo"), "b" to Place("b", name = "aquarium")),
        savedPlaces = listOf(SavedPlace("sp1", "a"), SavedPlace("sp2", "b")),
        stays = listOf(Stay("s1", "h", checkIn = "2027-05-09", checkOut = "2027-05-10", confirmationNumber = "STAY42")),
        flights = listOf(
            Flight("f2", "ANA", "NH", "NH 204", Endpoint("Tokio", date = "2027-05-10", time = "10:00"), Endpoint("München", date = "2027-05-10", time = "20:00")),
            Flight("f1", "ANA", "NH", "NH 203", Endpoint("München", date = "2027-05-08", time = "10:00"), Endpoint("Tokio", date = "2027-05-08", time = "20:00"), "ABC123", listOf("Anna Beispiel")),
        ),
        trains = listOf(Train("t1", "JR", Endpoint("Kyoto", date = "2027-05-09", time = "09:00"), Endpoint("Tokio", date = "2027-05-09", time = "11:30"), "TRAIN99")),
        days = days,
    )

    @Test fun groupsAndOrders() {
        val t = shareTargets(snapshot(listOf(Day("day-x", "2027-05-09", "Kyoto"))))
        assertEquals(listOf("f1", "f2"), t.filter { it.group == ShareGroup.FLIGHT }.map { it.id })
        assertEquals(listOf("t1"), t.filter { it.group == ShareGroup.TRAIN }.map { it.id })
        assertEquals(listOf("s1"), t.filter { it.group == ShareGroup.STAY }.map { it.id })
        assertEquals(listOf("date:2027-05-08", "day-x", "date:2027-05-10"), t.filter { it.group == ShareGroup.DAY }.map { it.id })
        assertEquals(listOf("sp2", "sp1"), t.filter { it.group == ShareGroup.PLACE }.map { it.id })
        assertEquals(listOf(ShareGroup.FLIGHT, ShareGroup.TRAIN, ShareGroup.STAY, ShareGroup.DAY, ShareGroup.PLACE), t.map { it.group }.distinct())
        assertEquals(EntityType.SAVED_PLACE, t.first { it.id == "sp1" }.type)
        assertEquals(EntityType.DAY, t.first { it.id == "day-x" }.type)
        assertEquals("Hotel Kyoto", t.first { it.id == "s1" }.label)
    }

    @Test fun missingDayGetsDateId() {
        val t = shareTargets(snapshot()).filter { it.group == ShareGroup.DAY }
        assertEquals(listOf("date:2027-05-08", "date:2027-05-09", "date:2027-05-10"), t.map { it.id })
    }

    @Test fun labelsNeverContainConfirmationNumbers() {
        val t = shareTargets(snapshot())
        t.forEach {
            for (secret in listOf("ABC123", "STAY42", "TRAIN99", "Anna")) {
                assertFalse(it.label.contains(secret))
                assertFalse(it.subtitle.orEmpty().contains(secret))
            }
        }
    }

    @Test fun filterIgnoresCaseOnLabelAndSubtitle() {
        val t = shareTargets(snapshot())
        assertEquals(listOf("sp1"), filterTargets(t, "ZOO").map { it.id })
        assertEquals(t, filterTargets(t, "  "))
        assertEquals(listOf("f1"), filterTargets(t, "nh 203").map { it.id })
        assertEquals(true, filterTargets(t, "münchen").any { it.id == "f1" })
    }
}
