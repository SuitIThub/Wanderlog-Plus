package de.wanderlogplus.server

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlightLiveServiceTest {
    private val store = SqliteStore(Files.createTempDirectory("wlp-flights").resolve("wanderlog-plus.db"))
    private var clock = Instant.parse("2027-05-06T20:00:00Z") // 2027-05-07 05:00 Tokyo, 26 h before
    private val urls = mutableListOf<String>()
    private var status = "scheduled"
    private var day = "2027-05-08"
    private val http = WeatherHttp { url ->
        urls += url
        200 to """{"response":{"flight_iata":"NH7","dep_time":"$day 07:00","dep_estimated":"$day 07:25","dep_gate":"G6","dep_terminal":"2",
            "arr_time":"$day 08:05","arr_baggage":"12","status":"$status","dep_delayed":25,"updated":1809000000}}"""
    }
    private val service = FlightLiveService(store, http, "KEY", { clock })

    private fun seed(f: Flight) {
        val fields = EntityCodec.fields(f) + ("deleted" to JsonPrimitive(false))
        store.transaction("test") { store.put(EntityRecord(EntityType.FLIGHT, f.id, fields.mapValues { (_, v) -> FieldValue(v, "0000000000001:0000:test") })) }
    }

    @Test
    fun `asked only around the flight, cached, final once landed and never for another day`() {
        seed(Flight("f1", airlineCode = "NH", number = "7", depart = Endpoint("Haneda", "HND", date = "2027-05-08", time = "07:00"), arrive = Endpoint("Itami", "ITM")))
        val early = service.forFlight("f1")
        assertFalse(early.available)
        assertTrue(urls.isEmpty())

        clock = Instant.parse("2027-05-07T12:00:00Z") // 19 h before
        val live = service.forFlight("f1")
        assertTrue(live.available)
        assertEquals("G6", live.depGate)
        assertEquals(25, live.depDelayMinutes)
        assertEquals("12", live.arrBaggage)
        assertTrue(urls.single().contains("flight_iata=NH7"))
        service.forFlight("f1")
        assertEquals(1, urls.size) // cached for an hour
        clock = clock.plus(Duration.ofMinutes(61))
        status = "landed"
        service.forFlight("f1")
        assertEquals(2, urls.size)
        clock = clock.plus(Duration.ofDays(1))
        assertEquals("landed", service.forFlight("f1").status)
        assertEquals(2, urls.size) // landed: never asked again
    }

    @Test
    fun `an answer for another day is not taken and numbers are normalised`() {
        seed(Flight("f2", airlineCode = "NH", number = "7", depart = Endpoint("Haneda", date = "2027-05-08", time = "07:00")))
        clock = Instant.parse("2027-05-07T20:00:00Z")
        day = "2027-05-06"
        val r = service.forFlight("f2")
        assertFalse(r.available)
        assertTrue(r.reason!!.contains("2027-05-06"))

        assertEquals("NH7", FlightLiveService.flightIata(Flight("x", airlineCode = "NH", number = "007")))
        assertEquals("JL123", FlightLiveService.flightIata(Flight("x", number = "jl 123")))
        assertEquals(null, FlightLiveService.flightIata(Flight("x", number = "123")))
        assertFailsWith<FlightLiveNotConfigured> { FlightLiveService(store, null, null).forFlight("f2") }
    }
}
