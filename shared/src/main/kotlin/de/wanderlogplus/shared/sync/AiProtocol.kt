package de.wanderlogplus.shared.sync

import de.wanderlogplus.shared.model.Criteria
import de.wanderlogplus.shared.model.CriterionScore
import de.wanderlogplus.shared.model.PlaceDossier
import kotlinx.serialization.Serializable

/** How worthwhile a place is, as judged by the assistant. */
enum class Worth { MUST, WORTH, OPTIONAL, SKIP }

/** The user's assessment settings: criteria keys (see `Criteria`) and an extra instruction for the model. */
@Serializable
data class AssessConfig(val criteria: List<String> = Criteria.DEFAULT, val extraPrompt: String = "")

/** Body of `POST /api/ai/reels`: an Instagram reel (or other video) link. */
@Serializable
data class ReelRequest(val url: String, val config: AssessConfig = AssessConfig())

/** Body of `POST /api/ai/assess`: the saved places to judge (empty = all without a judgement yet). */
@Serializable
data class AssessRequest(val savedPlaceIds: List<String> = emptyList(), val config: AssessConfig = AssessConfig())

/** Body of `POST /api/ai/research`: saved places to research on the web (at least one). */
@Serializable
data class ResearchRequest(val savedPlaceIds: List<String>, val config: AssessConfig = AssessConfig())

/**
 * One guide to write: a catalog [topic], or a free guide named [title]; [guideId] rewrites that guide (also when the
 * topic already has one). [wish] is what the user wants in or changed.
 */
@Serializable
data class GuideAsk(val topic: String? = null, val title: String = "", val parentId: String? = null, val wish: String = "", val guideId: String? = null)

/** `POST /api/ai/stay`: research the room and amenities of a stay; [fresh] also replaces what was changed by hand. */
@Serializable
data class StayResearchRequest(val stayId: String, val links: List<String> = emptyList(), val fresh: Boolean = false)

/** `POST /api/ai/guides`: 1 to 30 guides, written one after the other. */
@Serializable
data class GuideRequest(val asks: List<GuideAsk>)

/** The research result for one saved place. */
@Serializable
data class DossierResult(val savedPlaceId: String, val worth: Worth? = null, val description: String = "", val dossier: PlaceDossier = PlaceDossier())

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
    /** Web pages the description and tips rely on. */
    val sources: List<String> = emptyList(),
    /** Criteria scores, verdict, insider tips, hidden spots, good-to-know (the full research). */
    val dossier: PlaceDossier? = null,
    /** Why [lat]/[lng] are missing: "not_found" (no match) or "unavailable" (the geocoder did not answer). */
    val positionIssue: String? = null,
    /** Set in the app when the user picked the position in the place search. */
    val positionSetByHand: Boolean = false,
    /** What the reel is about; false for places only named for orientation (located, not researched). */
    val main: Boolean = true,
    /** Google's id when Google Places found the position; the imported place then gets photos and details. */
    val googlePlaceId: String? = null,
)

/** The judgement for one saved place. */
@Serializable
data class Assessment(val savedPlaceId: String, val worth: Worth, val reason: String = "", val criteria: List<CriterionScore> = emptyList())

/**
 * A long-running assistant job ([kind] reel, assess or research). [status]: QUEUED, DOWNLOADING, ANALYZING, RESEARCHING, ASSESSING, DONE, FAILED.
 * [error] is a fixed code (e.g. download_failed, ai_not_configured, ai_failed), never model or user text.
 */
@Serializable
data class AiJobInfo(
    val id: String,
    /** "reel", "assess" or "research". */
    val kind: String,
    val createdAt: Long,
    val status: String,
    val source: String? = null,
    val error: String? = null,
    val summary: String? = null,
    val candidates: List<ReelCandidate> = emptyList(),
    val assessments: List<Assessment> = emptyList(),
    val dossiers: List<DossierResult> = emptyList(),
    /** Places done / to do while a research job runs. */
    val progress: Int = 0,
    val total: Int = 0,
    /** What the job did so far, oldest first (at most the last 300 lines). */
    val steps: List<JobStep> = emptyList(),
    /** The places the job works through, with their state. */
    val items: List<JobItem> = emptyList(),
    /** Epoch millis when the job ended (done or failed); 0 while running. */
    val finishedAt: Long = 0,
    /** Research: the server writes each result into the place itself (the app only syncs). */
    val appliedOnServer: Boolean = false,
    /** Research: the places asked for and the criteria, so an interrupted job can continue. */
    val savedPlaceIds: List<String> = emptyList(),
    val config: AssessConfig? = null,
    /** Guides: the guides written so far. */
    val guideIds: List<String> = emptyList(),
    /** Stay research: the stay it is about. */
    val stayId: String? = null,
)

/** One line of a job's log; [level] is INFO, OK, WARN or ERROR. */
@Serializable
data class JobStep(val at: Long, val text: String, val level: String = "INFO")

/** One place a job works on; [state] is WAITING, RUNNING, DONE or FAILED, [detail] what happens with it right now. */
@Serializable
data class JobItem(
    val key: String,
    val name: String,
    val state: String = "WAITING",
    val detail: String = "",
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
)
