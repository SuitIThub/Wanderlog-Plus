package de.wanderlogplus.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VotesTest {
    private fun v(voter: String, value: Int, place: String = "s1") = Vote(Votes.id(place, voter), place, voter, value)

    @Test
    fun `score and status rule`() {
        assertEquals("s1|daniel", Votes.id("s1", "daniel"))
        val two = listOf(v("daniel", Votes.LIKE), v("selina", Votes.LIKE), v("x", Votes.DOUBLE, place = "s2"))
        assertEquals(2, Votes.score(two, "s1"))
        assertEquals(PlaceStatus.SHORTLIST, Votes.nextStatus(PlaceStatus.IDEA, 2))
        assertEquals(PlaceStatus.IDEA, Votes.nextStatus(PlaceStatus.IDEA, 1))
        // A double like is countered by one dislike.
        assertEquals(0, Votes.score(listOf(v("daniel", Votes.DOUBLE), v("selina", Votes.DISLIKE)), "s1"))
        assertEquals(PlaceStatus.IDEA, Votes.nextStatus(PlaceStatus.SHORTLIST, 0))
        assertEquals(PlaceStatus.PLANNED, Votes.nextStatus(PlaceStatus.PLANNED, -2))
        assertEquals(PlaceStatus.VISITED, Votes.nextStatus(PlaceStatus.VISITED, 4))
        assertEquals(PlaceStatus.DROPPED, Votes.nextStatus(PlaceStatus.DROPPED, 4))
    }

    @Test
    fun `doubt only for planned places someone voted on`() {
        val down = listOf(v("daniel", Votes.DISLIKE))
        assertTrue(Votes.planDoubt(PlaceStatus.PLANNED, down, "s1"))
        assertFalse(Votes.planDoubt(PlaceStatus.PLANNED, emptyList(), "s1"))
        assertFalse(Votes.planDoubt(PlaceStatus.PLANNED, listOf(v("a", Votes.LIKE), v("b", Votes.LIKE)), "s1"))
        assertFalse(Votes.planDoubt(PlaceStatus.IDEA, down, "s1"))
        // Withdrawn votes (0) do not count as voting.
        assertFalse(Votes.planDoubt(PlaceStatus.PLANNED, listOf(v("daniel", 0)), "s1"))
    }

    @Test
    fun `only records under their own id count`() {
        val forged = listOf(Vote("a|daniel", "s1", "daniel", Votes.DOUBLE), Vote("s1|x", "s1", "", Votes.DOUBLE), v("daniel", Votes.LIKE))
        assertEquals(1, Votes.score(forged, "s1"))
    }
}
