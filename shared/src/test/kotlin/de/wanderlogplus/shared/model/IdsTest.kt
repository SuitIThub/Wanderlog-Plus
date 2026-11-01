package de.wanderlogplus.shared.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdsTest {
    @Test fun newIdIsUuidV7WithTimestamp() {
        val now = 1_800_000_000_123L
        val u = UUID.fromString(Ids.newId(now))
        assertEquals(7, u.version()); assertEquals(2, u.variant())
        assertEquals(now, u.mostSignificantBits ushr 16)
    }

    @Test fun idsSortByTime() {
        assertTrue(Ids.newId(1_000L) < Ids.newId(1_001L))
        assertTrue(Ids.newId(1_999_999_999_999L) < Ids.newId(2_000_000_000_000L))
    }

    @Test fun dayIdIsStableAndDistinct() {
        assertEquals(Ids.dayId("t", "2027-05-10"), Ids.dayId("t", "2027-05-10"))
        assertNotEquals(Ids.dayId("t", "2027-05-10"), Ids.dayId("t", "2027-05-11"))
    }
}
