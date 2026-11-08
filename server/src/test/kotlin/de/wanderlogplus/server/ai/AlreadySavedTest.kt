package de.wanderlogplus.server.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlreadySavedTest {
    private val ctx = TripContext(
        emptyList(), emptyList(),
        listOf(SavedRef("s1", "Fushimi Inari Taisha", 34.9671, 135.7727, "g-inari"), SavedRef("s2", "Ichiran Shibuya", 35.6614, 139.7010)),
    )

    @Test fun sameGoogleIdMatchesEvenFarAway() = assertEquals("s1", ctx.alreadySaved("Inari Shrine", 0.0, 0.0, "g-inari")?.savedPlaceId)

    @Test fun similarNameNearbyMatches() = assertEquals("s2", ctx.alreadySaved("ICHIRAN", 35.6620, 139.7015, null)?.savedPlaceId)

    @Test fun similarNameFarAwayDoesNotMatch() = assertNull(ctx.alreadySaved("Ichiran", 34.6687, 135.5013, null))

    @Test fun otherNameNearbyDoesNotMatch() = assertNull(ctx.alreadySaved("Shibuya Sky", 35.6614, 139.7010, null))
}
