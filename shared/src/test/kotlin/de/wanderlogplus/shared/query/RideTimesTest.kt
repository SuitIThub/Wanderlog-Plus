package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RideTimesTest {
    private fun flight(dep: Endpoint, arr: Endpoint) = Movement.FlightMove(Flight("f", depart = dep, arrive = arr))
    private fun train(dep: Endpoint, arr: Endpoint) = Movement.TrainMove(Train("t", depart = dep, arrive = arr))

    @Test fun frankfurtToHanedaFollowsGermanSummerTime() {
        val may = flight(Endpoint("Frankfurt", code = "FRA", date = "2027-05-08", time = "13:30"), Endpoint("Haneda", code = "HND", date = "2027-05-09", time = "08:45"))
        val nov = flight(Endpoint("Frankfurt", code = "FRA", date = "2027-11-08", time = "13:30"), Endpoint("Haneda", code = "HND", date = "2027-11-09", time = "08:45"))
        assertEquals(12 * 60 + 15L, RideTimes.minutes(may)) // Berlin UTC+2 in May
        assertEquals(11 * 60 + 15L, RideTimes.minutes(nov)) // UTC+1 in November: one hour less
    }

    @Test fun ownZoneWinsOverTheAirportTable() {
        val f = flight(Endpoint("X", code = "FRA", date = "2027-05-08", time = "13:30", timeZone = "Europe/Helsinki"), Endpoint("Haneda", code = "HND", date = "2027-05-09", time = "08:45"))
        assertEquals(13 * 60 + 15L, RideTimes.minutes(f)) // Helsinki is UTC+3 in May
    }

    @Test fun unknownAirportHasNoDuration() {
        assertNull(RideTimes.minutes(flight(Endpoint("Irgendwo", code = "ZZZ", date = "2027-05-08", time = "10:00"), Endpoint("Haneda", code = "HND", date = "2027-05-08", time = "20:00"))))
        // An unreadable zone counts as missing: the airport's zone applies (Berlin UTC+2 → Tokyo UTC+9 in May).
        assertEquals(3 * 60L, RideTimes.minutes(flight(Endpoint("Frankfurt", code = "FRA", date = "2027-05-08", time = "10:00", timeZone = "Mars/Olympus"), Endpoint("Haneda", code = "HND", date = "2027-05-08", time = "20:00"))))
    }

    @Test fun trainsInJapanRunOnJapaneseTime() {
        assertEquals(46L, RideTimes.minutes(train(Endpoint("Kyoto", date = "2027-05-08", time = "09:12"), Endpoint("Nagoya", date = "2027-05-08", time = "09:58"))))
        // Over midnight without an arrival date: the next day.
        assertEquals(40L, RideTimes.minutes(train(Endpoint("A", date = "2027-05-08", time = "23:30"), Endpoint("B", time = "00:10"))))
    }

    @Test fun aTrainOutsideJapanNeedsItsZone() {
        val ice = train(Endpoint("Köln", lat = 50.94, lng = 6.96, date = "2027-05-08", time = "09:00"), Endpoint("Frankfurt", lat = 50.05, lng = 8.57, date = "2027-05-08", time = "10:05"))
        assertNull(RideTimes.minutes(ice))
        val zoned = train(ice.train.depart.copy(timeZone = "Europe/Berlin"), ice.train.arrive.copy(timeZone = "Europe/Berlin"))
        assertEquals(65L, RideTimes.minutes(zoned))
    }

    @Test fun zoneOfAnEndpoint() {
        assertEquals("Asia/Tokyo", RideTimes.zoneOf(Endpoint("Haneda", code = "hnd"), flight = true)?.id)
        assertEquals("Europe/Berlin", RideTimes.zoneOf(Endpoint("Frankfurt", code = "FRA"), flight = true)?.id)
        assertNull(RideTimes.zoneOf(Endpoint("Haneda"), flight = true))
        assertEquals("Asia/Tokyo", RideTimes.zoneOf(Endpoint("Kyoto"), flight = false)?.id)
    }

    @Test fun missingTimeHasNoDuration() {
        assertNull(RideTimes.minutes(train(Endpoint("A", date = "2027-05-08"), Endpoint("B", date = "2027-05-08", time = "10:00"))))
    }
}
