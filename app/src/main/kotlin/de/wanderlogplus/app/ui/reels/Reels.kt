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

/** Place search to set the position of candidate [index] of a reel analysis by hand. */
const val REEL_POSITION_ROUTE = "mehr/reels/{jobId}/position/{index}"
fun reelPositionRoute(jobId: String, index: Int) = "mehr/reels/${android.net.Uri.encode(jobId)}/position/$index"

/** A candidate with a position the user picked in the place search (not on any route check). */
fun withPosition(c: ReelCandidate, place: de.wanderlogplus.client.GeoPlace): ReelCandidate = c.copy(
    lat = place.lat,
    lng = place.lng,
    address = place.detail.ifBlank { null } ?: c.address,
    distanceKm = null,
    nearestAnchor = null,
    positionIssue = null,
    positionSetByHand = true,
)

/** Why a candidate has no position, for the line under it. */
fun missingPositionText(c: ReelCandidate): String = when (c.positionIssue) {
    "unavailable" -> "Ohne Kartenposition: Der Ortsdienst hat nicht geantwortet."
    else -> "Ohne Kartenposition: Der Ort wurde auf der Karte nicht gefunden."
}

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
    "DOWNLOADING" -> "Beitrag wird geladen …"
    "ANALYZING" -> "KI schaut sich Bilder und Ton an …"
    "RESEARCHING" -> "Orte werden recherchiert …"
    "CHECKING" -> "Tipps werden geprüft …"
    "ASSESSING" -> "Orte werden eingeschätzt …"
    "DONE" -> "Fertig"
    "FAILED" -> "Fehlgeschlagen"
    else -> status
}

fun isFinished(job: AiJobInfo) = job.status == "DONE" || job.status == "FAILED"

/** What went wrong, in words the user can act on. */
fun errorText(code: String?): String = when (code) {
    "download_failed" -> "Das Reel ließ sich nicht laden (privat oder von Instagram blockiert). Speichere es in Instagram und teile die Videodatei an die App."
    "login_required" -> "Instagram zeigt diesen Beitrag nur angemeldet. Auf dem Server WLP_INSTAGRAM_SESSIONID setzen oder die Session-ID erneuern (sie läuft ab, wenn man sich in Instagram abmeldet)."
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
    if (c.positionSetByHand) return "Position selbst gesetzt"
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

/** A tip can go into a guide unless a guide says it already or it turned out false. */
fun tipSelectable(t: de.wanderlogplus.shared.sync.ReelTip) = t.coveredBy == null && t.verdict != de.wanderlogplus.shared.sync.TipVerdict.FALSE

/** Checked as true or (corrected) partly true: picked from the start. */
fun tipPreselected(t: de.wanderlogplus.shared.sync.ReelTip) =
    tipSelectable(t) && (t.verdict == de.wanderlogplus.shared.sync.TipVerdict.TRUE || t.verdict == de.wanderlogplus.shared.sync.TipVerdict.PARTLY)

/** One guide job request per target (existing guide, catalog topic or new guide), with the tips' checked texts and sources. */
fun tipAsks(picked: List<de.wanderlogplus.shared.sync.ReelTip>): List<de.wanderlogplus.shared.sync.GuideAsk> =
    picked.filter { it.placeIds.isEmpty() }.groupBy { Triple(it.guideId, it.targetTopic, it.targetTitle) }.map { (target, tips) ->
        de.wanderlogplus.shared.sync.GuideAsk(
            topic = target.second,
            title = if (target.first == null && target.second == null) target.third.orEmpty().ifBlank { "Reisetipps" } else "",
            guideId = target.first,
            // Within what the server's guide job accepts (1000 characters, 20 sources per addition).
            additions = tips.take(20).map { de.wanderlogplus.shared.sync.GuideAddition(it.text.ifBlank { it.claim }.take(1000), it.sources.take(20)) },
        )
    }

/**
 * The place-facts request items: tips that go to a place (their checked text and sources) and the reel's own facts of
 * already saved places ([savedFacts]), one item per place, within the server's limits (12 facts per place, 20 places).
 */
fun placeFactItems(tips: List<de.wanderlogplus.shared.sync.ReelTip>, savedFacts: List<ReelCandidate>): List<de.wanderlogplus.shared.sync.PlaceFactsItem> {
    val facts = linkedMapOf<String, MutableList<String>>()
    val sources = linkedMapOf<String, MutableList<String>>()
    tips.filter { it.placeIds.isNotEmpty() }.forEach { t ->
        t.placeIds.forEach { id ->
            facts.getOrPut(id) { mutableListOf() } += t.text.ifBlank { t.claim }.take(1000)
            sources.getOrPut(id) { mutableListOf() } += t.sources
        }
    }
    savedFacts.forEach { c -> c.savedPlaceId?.let { id -> facts.getOrPut(id) { mutableListOf() } += c.reelFacts } }
    return facts.filterValues { it.isNotEmpty() }.entries.take(20).map { (id, f) ->
        de.wanderlogplus.shared.sync.PlaceFactsItem(id, f.distinct().take(12), sources[id].orEmpty().distinct().take(20))
    }
}

/** The note after an import: tips into guides and/or infos into places; null when neither. */
fun importMessage(guideTips: Int, places: Int): String? = when {
    guideTips > 0 && places > 0 -> "Die Tipps werden in Guides und Orte übernommen"
    guideTips == 1 -> "Der Tipp wird in die Guides übernommen"
    guideTips > 1 -> "$guideTips Tipps werden in die Guides übernommen"
    places == 1 -> "Die Infos werden in die Recherche des Orts übernommen"
    places > 1 -> "Die Infos werden in die Recherche von $places Orten übernommen"
    else -> null
}

/** A candidate chosen for import and the region it goes into. */
data class ReelPick(val candidate: ReelCandidate, val regionId: String?, val research: Boolean = false)

/** Places with coordinates only: the rest cannot be shown on maps or used for ways. */
fun importable(c: ReelCandidate) = c.lat != null && c.lng != null && c.savedPlaceId == null

/** Takes the picked candidates over as saved places (status "Idee"); returns their ids. */
fun importPicks(editor: LocalEditor, picks: List<ReelPick>, source: String?): List<String> =
    picks.filter { importable(it.candidate) }.map { (c, region, _) ->
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
            googlePlaceId = c.googlePlaceId,
            savedExtra = buildMap {
                c.worth?.let { put("worth", JsonPrimitive(it.name)) }
                if (c.worthReason.isNotBlank()) put("worthReason", JsonPrimitive(c.worthReason))
                c.dossier?.let { put("dossier", de.wanderlogplus.app.ui.places.DOSSIER_JSON.encodeToJsonElement(de.wanderlogplus.shared.model.PlaceDossier.serializer(), it)) }
            },
        )
    }
