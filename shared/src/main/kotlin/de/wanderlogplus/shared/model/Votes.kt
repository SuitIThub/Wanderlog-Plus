package de.wanderlogplus.shared.model

import kotlinx.serialization.Serializable

/** One person's (access token's) vote on a saved place; [value] is [Votes.LIKE], [Votes.DOUBLE], [Votes.DISLIKE] or 0 (withdrawn). */
@Serializable
data class Vote(val id: String, val savedPlaceId: String = "", val voter: String = "", val value: Int = 0)

/** Votes per access token and what they do to a place's status. */
object Votes {
    const val LIKE = 1
    const val DOUBLE = 2
    const val DISLIKE = -2

    /** From this score an idea becomes „Vorgemerkt“; below it a shortlisted place goes back to an idea. */
    const val THRESHOLD = 2

    /** One vote per place and voter. */
    fun id(savedPlaceId: String, voter: String) = "$savedPlaceId|$voter"

    /** Only records stored under their own id ("<place>|<voter>") count: one vote per voter and place. */
    fun valid(v: Vote): Boolean = v.voter.isNotBlank() && v.savedPlaceId.isNotBlank() && v.id == id(v.savedPlaceId, v.voter)

    fun score(votes: List<Vote>, savedPlaceId: String): Int = votes.filter { it.savedPlaceId == savedPlaceId && valid(it) }.sumOf { it.value }

    /** IDEA with enough votes → SHORTLIST, SHORTLIST without → IDEA; every other status stays. */
    fun nextStatus(current: PlaceStatus, score: Int): PlaceStatus = when {
        current == PlaceStatus.IDEA && score >= THRESHOLD -> PlaceStatus.SHORTLIST
        current == PlaceStatus.SHORTLIST && score < THRESHOLD -> PlaceStatus.IDEA
        else -> current
    }

    /** A planned place that someone voted on and that has too little approval now. */
    fun planDoubt(current: PlaceStatus, votes: List<Vote>, savedPlaceId: String): Boolean {
        val mine = votes.filter { it.savedPlaceId == savedPlaceId && it.value != 0 && valid(it) }
        return current == PlaceStatus.PLANNED && mine.isNotEmpty() && mine.sumOf { it.value } < THRESHOLD
    }
}
