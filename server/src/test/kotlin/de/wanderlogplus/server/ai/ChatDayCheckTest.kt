package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.OpeningPeriod
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.model.Trip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatDayCheckTest {
    private fun daily(open: String, close: String) = (0..6).map { OpeningPeriod(it, open, it, close) }

    private val trip = ChatTrip(
        trip = Trip("t", "Japan", "2027-05-20", "2027-05-22"),
        regions = listOf(Region("r-kyoto", "Kyoto")),
        places = listOf(
            Place("p-hotel", name = "Hotel Gion", lat = 35.0037, lng = 135.7788),
            Place("p-kiyo", name = "Kiyomizu-dera", lat = 34.9949, lng = 135.7850, openingPeriods = daily("06:00", "18:00"), minMinutes = 60, maxMinutes = 60),
            // Closed on Fridays (2027-05-21).
            Place("p-garden", name = "Moosgarten", lat = 35.0, lng = 135.78, openingPeriods = (0..6).filter { it != 5 }.map { OpeningPeriod(it, "09:00", it, "17:00") }),
            Place("p-deer", name = "Nara-Park", lat = 34.6851, lng = 135.8430),
            Place("p-museum", name = "Museum", lat = 35.0040, lng = 135.7790, openingPeriods = daily("09:00", "16:00"), minMinutes = 120),
            Place("p-station", name = "Bahnhof Kyoto", lat = 34.9858, lng = 135.7588),
        ).associateBy { it.id },
        saved = listOf(
            SavedPlace("s-kiyo", "p-kiyo", "r-kyoto"), SavedPlace("s-garden", "p-garden", "r-kyoto"),
            SavedPlace("s-deer", "p-deer", "r-kyoto"), SavedPlace("s-museum", "p-museum", "r-kyoto", PlaceCategory.SIGHT),
        ),
        stays = listOf(Stay("st", "p-hotel", "r-kyoto", "2027-05-20", "2027-05-22")),
        days = listOf(Day("d21", "2027-05-21"), Day("d22", "2027-05-22")),
        dayItems = listOf(
            DayItem("i1", "d21", "a", placeId = "p-kiyo", time = "09:00"),
            DayItem("i2", "d21", "b", placeId = "p-garden"),
            DayItem("i3", "d21", "c", placeId = "p-deer", time = "10:30"),
            DayItem("i4", "d22", "a", placeId = "p-kiyo", time = "10:00"),
            DayItem("i5", "d22", "b", placeId = "p-museum"),
        ),
        trains = listOf(Train("tr", carrier = "JR", depart = Endpoint("Kyoto", placeId = "p-station", date = "2027-05-22", time = "12:00"), arrive = Endpoint("Tokyo", date = "2027-05-22", time = "14:15"))),
    )

    private val check = ChatDayCheck(trip)

    @Test
    fun `program check finds closed days, impossible times and returns home`() {
        val r = check.check("2027-05-21", check.programStops("2027-05-21"))
        assertTrue(r.startsWith("Zeitplan Freitag, 21.05.2027"), r)
        assertTrue("- 08:40 ab Unterkunft Hotel Gion" in r, r) // leaves in time for 09:00
        assertTrue("- 09:00–10:00 Kiyomizu-dera (Weg ca. 20 Min. zu Fuß)" in r, r)
        assertTrue("Moosgarten hat am Freitag, 21.05.2027 geschlossen (Ruhetag)." in r, r)
        assertTrue("Nara-Park: geplant 10:30, frühestens ca. 13:16 erreichbar (Weg ca. 108 Min. ÖPNV, grob)." in r, r)
        assertTrue("zurück: Unterkunft Hotel Gion" in r, r)
    }

    @Test
    fun `a train of the day ends the program before it and closing times count`() {
        val r = check.check("2027-05-22", check.programStops("2027-05-22"))
        // The museum (2 h, closes 16:00) is planned after the temple; the train leaves at 12:00.
        assertTrue("- 10:00–11:00 Kiyomizu-dera" in r && "- 11:20–13:20 Museum" in r, r)
        assertTrue("Zug JR um 12:00: Das Programm davor endet erst ca. 13:20; mit Weg (ca. 23 Min.) und 15 Min. Puffer müsstet ihr spätestens 11:22 los." in r, r)
        assertTrue("- 12:00 Zug JR ab Kyoto → an 14:15" in r, r)

        val plan = check.check("2027-05-21", listOf(PlanStop("Museum", "p-museum", time = "15:00")))
        assertTrue("Museum schließt ca. 16:00; bei Ankunft ca. 15:00 bleibt nur 60 von ca. 120 Min." in plan, plan)

        val fine = check.check("2027-05-20", listOf(PlanStop("Kiyomizu-dera", "p-kiyo")))
        assertTrue("Keine Probleme gefunden." in fine, fine)
        assertEquals("Ungültiges Datum.", check.check("x", emptyList()))
    }
}
