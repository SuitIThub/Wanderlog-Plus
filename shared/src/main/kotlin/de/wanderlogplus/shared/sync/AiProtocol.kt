package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable

/** How worthwhile a place is, as judged by the assistant. */
enum class Worth { MUST, WORTH, OPTIONAL, SKIP }

/** Body of `POST /api/ai/reels`: an Instagram reel (or other video) link. */
@Serializable
data class ReelRequest(val url: String)

/** Body of `POST /api/ai/assess`: the saved places to judge (empty = all without a judgement yet). */
@Serializable
data class AssessRequest(val savedPlaceIds: List<String> = emptyList())

/** A place found in a reel, researched and described; [onRoute] when within reach of the trip. */
@Serializable
data class ReelCandidate(
    val name: String,
    val city: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val address: String? = null,
    /** PlaceCategory name (SIGHT, FOOD, SHOPPING, NIGHTLIFE, OTHER). */
    val category: String = "SIGHT",
    val description: String = "",
    val tips: List<String> = emptyList(),
    val hints: List<String> = emptyList(),
    val worth: Worth? = null,
    val worthReason: String = "",
    /** Distance to the nearest stay or saved place of the trip, in km; null without coordinates. */
    val distanceKm: Double? = null,
    val nearestAnchor: String? = null,
    val onRoute: Boolean = false,
    /** Why the model thinks the reel shows this place (caption, speech, sign in the video). */
    val evidence: String = "",
)

/** The judgement for one saved place. */
@Serializable
data class Assessment(val savedPlaceId: String, val worth: Worth, val reason: String = "")

/**
 * A long-running assistant job. [status]: QUEUED, DOWNLOADING, ANALYZING, RESEARCHING, ASSESSING, DONE, FAILED.
 * [error] is a fixed code (e.g. download_failed, ai_not_configured, ai_failed), never model or user text.
 */
@Serializable
data class AiJobInfo(
    val id: String,
    /** "reel" or "assess". */
    val kind: String,
    val createdAt: Long,
    val status: String,
    val source: String? = null,
    val error: String? = null,
    val summary: String? = null,
    val candidates: List<ReelCandidate> = emptyList(),
    val assessments: List<Assessment> = emptyList(),
)
