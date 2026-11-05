package de.wanderlogplus.server

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrainLiveServiceTest {
    private val store = SqliteStore(Files.createTempDirectory("wlp-trains").resolve("wanderlog-plus.db"))
    private var clock = Instant.parse("2027-05-21T21:00:00Z") // 2027-05-22 06:00 Tokyo
    private val urls = mutableListOf<String>()
    private val http = WeatherHttp { url ->
        urls += url
        val down = "directionId=D" in url
        val trains = if (down) """{"trainName":"Nozomi 21","detail":"Running between KYOTO and SHIN-OSAKA","status":"Delayed 5 min","tone":"warning","updatedAt":"08:31"}"""
        else """{"trainName":"Hikari 500","detail":"Stopped at NAGOYA","status":"On time","tone":"success","updatedAt":"08:31"}"""
        200 to """{"summary":[{"lineId":"1","lineName":"Tokaido, Sanyo Shinkansen","status":"Delayed","tone":"warning"}],"trains":[$trains]}"""
    }
    private val service = TrainLiveService(store, http, { clock })

    private fun seed(t: Train) {
        val fields = EntityCodec.fields(t) + ("deleted" to JsonPrimitive(false))
        store.transaction("test") { store.put(EntityRecord(EntityType.TRAIN, t.id, fields.mapValues { (_, v) -> FieldValue(v, "0000000000001:0000:test") })) }
    }

    @Test
    fun `finds the Shinkansen by name and number only around the ride`() {
        seed(Train("t1", "JR Shinkansen", Endpoint("Kyoto", date = "2027-05-22", time = "08:12"), Endpoint("Hakata", date = "2027-05-22", time = "10:56"), number = "Nozomi 21 / Shinano 7"))
        assertFalse(service.forTrain("t1").available) // 06:00: too early
        assertTrue(urls.isEmpty())

        clock = Instant.parse("2027-05-21T23:30:00Z") // 08:30 Tokyo
        val live = service.forTrain("t1")
        assertTrue(live.available)
        assertEquals("Nozomi 21", live.trains.single().name)
        assertEquals("Delayed 5 min", live.trains.single().status)
        assertEquals("Tokaido, Sanyo Shinkansen: Delayed", live.lineStatus)
        assertEquals(1, urls.size) // found going down, no second request
        service.forTrain("t1")
        assertEquals(1, urls.size) // cached for two minutes
        clock = clock.plus(Duration.ofHours(4))
        assertFalse(service.forTrain("t1").available) // long after arrival
    }

    @Test
    fun `only Shinkansen have live data`() {
        assertEquals(listOf("Nozomi" to "21"), TrainLiveService.shinkansen(Train("x", number = "Nozomi 21 / Shinano 7")))
        assertEquals(emptyList(), TrainLiveService.shinkansen(Train("x", number = "Haruka 15")))
        assertTrue(TrainLiveService.matches("Nozomi 021", "nozomi", "21"))
        seed(Train("t2", "JR", Endpoint("Kyoto", date = "2027-05-22", time = "08:12"), Endpoint("Nara"), number = "Miyakoji 3"))
        assertTrue(service.forTrain("t2").reason!!.contains("nur für Shinkansen"))
    }
}
