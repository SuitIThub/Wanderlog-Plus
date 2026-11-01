package de.wanderlogplus.app.ui.today

import de.wanderlogplus.app.data.TripZone
import de.wanderlogplus.app.data.now
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TripZoneTest {
    private val instant = Instant.parse("2027-05-19T20:00:00Z")

    @Test fun todayUsesTripZone() {
        val berlin = Clock.fixed(instant, ZoneId.of("Europe/Berlin"))
        assertEquals(LocalDate.of(2027, 5, 20), TripZone.JAPAN.now(berlin).toLocalDate())
        assertEquals(LocalDate.of(2027, 5, 19), TripZone.DEVICE.now(berlin).toLocalDate())
    }
}
