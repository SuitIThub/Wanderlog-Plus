package de.wanderlogplus.app.ui.reels

import de.wanderlogplus.app.ui.places.nearestRegion
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.ReelCandidate
import de.wanderlogplus.shared.sync.Worth
import kotlinx.serialization.json.JsonPrimitive

const val REELS_ROUTE = "mehr/reels"
const val REEL_ROUTE = "mehr/reels/{jobId}"

fun reelRoute(jobId: String) = "mehr/reels/${android.net.Uri.encode(jobId)}"

/** Tag on places taken over from a reel. */
const val REEL_TAG = "📱 Reel"

fun worthLabel(w: Worth): String = when (w) {
    Worth.MUST -> "Muss man sehen"
    Worth.WORTH -> "Lohnt sich"
    Worth.OPTIONAL -> "Wenn Zeit ist"
    Worth.SKIP -> "Eher auslassen"
}

/** Status line of an assistant job while it runs. */
fun statusLabel(status: String): String = when (status) {
    "QUEUED" -> "Wartet …"
    "DOWNLOADING" -> "Video wird geladen …"
    "ANALYZING" -> "KI schaut sich Bilder und Ton an …"
    "RESEARCHING" -> "Orte werden recherchiert …"
    "ASSESSING" -> "Orte werden eingeschätzt …"
    "DONE" -> "Fertig"
    "FAILED" -> "Fehlgeschlagen"
    else -> status
}

fun isFinished(job: AiJobInfo) = job.status == "DONE" || job.status == "FAILED"

/** What went wrong, in words the user can act on. */
fun errorText(code: String?): String = when (code) {
    "download_failed" -> "Das Reel ließ sich nicht laden (privat oder von Instagram blockiert). Speichere es in Instagram und teile die Videodatei an die App."
    "ai_not_configured" -> "Auf dem Server ist kein OpenRouter-Key eingetragen (WLP_OPENROUTER_KEY)."
    "ai_key_rejected" -> "OpenRouter hat den Key abgelehnt."
    "ai_no_credit" -> "Das OpenRouter-Guthaben ist aufgebraucht."
    "ai_unreachable" -> "OpenRouter war nicht erreichbar. Später noch einmal versuchen."
    "interrupted" -> "Die Auswertung wurde durch einen Neustart des Servers unterbrochen. Bitte das Reel noch einmal teilen."
    "ai_bad_answer", "ai_failed" -> "Die KI hat keine verwertbare Antwort geliefert. Noch einmal versuchen."
    else -> "Die Auswertung ist fehlgeschlagen."
}

/** "auf der Route · 3,2 km von Hotel Gion" / "abseits · 370 km von …" / "ohne Kartenposition". */
fun routeLabel(c: ReelCandidate): String {
    val d = c.distanceKm ?: return "ohne Kartenposition"
    val km = if (d < 10) String.format(java.util.Locale.GERMAN, "%.1f km", d) else "${d.toInt()} km"
    val anchor = c.nearestAnchor?.let { " von $it" }.orEmpty()
    return (if (c.onRoute) "auf der Route · " else "abseits · ") + km + anchor
}

/** The note for a place from a reel: tips, hints and where it came from (Markdown). */
fun reelNote(c: ReelCandidate, source: String?): String = buildString {
    if (c.tips.isNotEmpty()) {
        append("**Tipps**\n")
        c.tips.forEach { append("- ").append(it).append('\n') }
        append('\n')
    }
    if (c.hints.isNotEmpty()) {
        append("**Hinweise**\n")
        c.hints.forEach { append("- ").append(it).append('\n') }
        append('\n')
    }
    if (c.sources.isNotEmpty()) {
        append("**Quellen**\n")
        c.sources.take(5).forEach { append("- ").append(it).append('\n') }
        append('\n')
    }
    source?.takeIf { it.startsWith("http") }?.let { append("Aus dem Reel: ").append(it) }
}.trim()

/** The region a candidate goes into by default: the nearest within 50 km of its position, else none. */
fun defaultRegion(snapshot: TripSnapshot, c: ReelCandidate): String? =
    if (c.lat != null && c.lng != null) nearestRegion(snapshot, c.lat!!, c.lng!!) else null

/** A candidate chosen for import and the region it goes into. */
data class ReelPick(val candidate: ReelCandidate, val regionId: String?)

/** Places with coordinates only: the rest cannot be shown on maps or used for ways. */
fun importable(c: ReelCandidate) = c.lat != null && c.lng != null

/** Takes the picked candidates over as saved places (status "Idee"); returns their ids. */
fun importPicks(editor: LocalEditor, picks: List<ReelPick>, source: String?): List<String> =
    picks.filter { importable(it.candidate) }.map { (c, region) ->
        editor.addPlaceToCollection(
            name = c.name,
            lat = c.lat!!,
            lng = c.lng!!,
            address = c.address,
            category = PlaceCategory.entries.firstOrNull { it.name == c.category } ?: PlaceCategory.OTHER,
            regionId = region,
            description = c.description.ifBlank { null },
            note = reelNote(c, source),
            tags = listOf(REEL_TAG),
            savedExtra = buildMap {
                c.worth?.let { put("worth", JsonPrimitive(it.name)) }
                if (c.worthReason.isNotBlank()) put("worthReason", JsonPrimitive(c.worthReason))
            },
        )
    }
