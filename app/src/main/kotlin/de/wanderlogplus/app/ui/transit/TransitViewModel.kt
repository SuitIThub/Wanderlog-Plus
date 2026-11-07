package de.wanderlogplus.app.ui.transit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.TransitPoint
import de.wanderlogplus.shared.sync.TransitRequest
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitStationNear
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything "Verbindung abfragen" shows and edits. Stations are Japanese names (what the timetable understands). */
data class TransitForm(
    val fromStation: String = "",
    val toStation: String = "",
    val date: String = "",
    val time: String = "09:00",
    val searchType: String = "depart",
    val preference: String = "fastest",
    val useShinkansen: Boolean = true,
    val useLimitedExpress: Boolean = true,
    val useHighwayBus: Boolean = true,
    val useLocalBus: Boolean = true,
    /** Where the way starts: a place, hotel or the way's point; the server finds the station nearby (with the walk). */
    val fromPoint: TransitPoint? = null,
    val toPoint: TransitPoint? = null,
    /** Whether [fromPoint] is a place (hotel, sight) rather than just the station's position: it becomes the walk start. */
    val fromIsPlace: Boolean = false,
    val toIsPlace: Boolean = false,
)

/** [r] with the places of [f] that are real places (not just stations), for the walk before and after the stations. */
fun resultWithPlaces(r: TransitResult, f: TransitForm): TransitResult = r.copy(
    fromPlace = f.fromPoint?.takeIf { f.fromIsPlace && it.name.isNotBlank() },
    toPlace = f.toPoint?.takeIf { f.toIsPlace && it.name.isNotBlank() },
)

data class TransitScreenState(
    val form: TransitForm = TransitForm(),
    val nearFrom: List<TransitStationNear> = emptyList(),
    val nearTo: List<TransitStationNear> = emptyList(),
    val searching: Boolean = false,
    val result: TransitResult? = null,
    /** Fixed German text; null when fine. */
    val message: String? = null,
)

/** Why a form cannot be searched yet; null when it can. */
fun formProblem(f: TransitForm): String? = when {
    !Regex("""\d{4}-\d{2}-\d{2}""").matches(f.date.trim()) -> "Datum als JJJJ-MM-TT eingeben, z. B. 2027-05-20."
    normalizeTime(f.time) == null -> "Uhrzeit als HH:MM eingeben, z. B. 08:30."
    else -> null
}

/** The request for a form: stations by name when filled in, else found near the two places; always fresh data. */
fun requestFor(f: TransitForm): TransitRequest = TransitRequest(
    from = f.fromPoint,
    to = f.toPoint,
    fromStation = f.fromStation.trim().ifBlank { null },
    toStation = f.toStation.trim().ifBlank { null },
    datetime = "${f.date.trim()}T${normalizeTime(f.time)}",
    searchType = f.searchType,
    preference = f.preference,
    useShinkansen = f.useShinkansen,
    useLimitedExpress = f.useLimitedExpress,
    useHighwayBus = f.useHighwayBus,
    useLocalBus = f.useLocalBus,
    fresh = true,
)

fun transitError(e: ApiException): String = when {
    e.kind == ApiErrorKind.NOT_CONFIGURED -> "Auf dem Server ist kein Apify-Token eingetragen (WLP_APIFY_TOKEN)."
    e.kind == ApiErrorKind.NETWORK -> "Server nicht erreichbar."
    e.code == "no_station" -> "In der Nähe eines der Orte gibt es keinen Bahnhof. Bahnhof von Hand eintragen."
    e.code == "no_route" -> "Keine Verbindung gefunden. Andere Zeit oder andere Bahnhöfe versuchen."
    e.code == "transit_no_credit" -> "Das Apify-Guthaben ist aufgebraucht."
    e.code == "transit_key_rejected" -> "Apify hat den Token abgelehnt."
    e.code == "stations_unavailable" -> "Die Bahnhofssuche (OpenStreetMap) antwortet gerade nicht. Gleich noch einmal versuchen."
    e.code == "bad_request" -> "Die Angaben passen nicht (Datum, Uhrzeit oder Bahnhof prüfen)."
    e.code == "transit_no_timetable" -> "Für dieses Datum gibt es noch keinen Fahrplan – außerhalb Japans meist erst einige Wochen vorher. Später noch einmal suchen."
    else -> "Die Fahrplanauskunft antwortet gerade nicht. Später noch einmal versuchen."
}

/**
 * "Verbindung abfragen" for one way of a day: from [from] to [to], starting at [date]/[time]. A picked connection
 * ([chosen]) fills in its stations and time. [search] always asks for current data (not the cache).
 */
class TransitViewModel(
    private val api: suspend () -> SyncApi?,
    /** Where the way starts and ends at first; null when unknown (a place or station is entered then). */
    from: TransitPoint?,
    to: TransitPoint?,
    date: String,
    time: String?,
    chosen: TransitResult?,
    /** "depart" or "arrive" for a fresh search (a chosen connection keeps its own). */
    searchType: String = "depart",
    /** Pauses before asking again after a dropped connection (the server finishes and keeps the answer meanwhile). */
    private val retryDelaysMillis: List<Long> = listOf(5_000, 10_000),
    /** Whether [from]/[to] are places (program item, hotel) rather than station positions. */
    fromIsPlace: Boolean = true,
    toIsPlace: Boolean = true,
) : ViewModel() {
    private val _state = MutableStateFlow(
        TransitScreenState(
            form = TransitForm(
                fromStation = chosen?.fromStation?.nameJa.orEmpty(),
                toStation = chosen?.toStation?.nameJa.orEmpty(),
                date = chosen?.datetime?.take(10) ?: date,
                time = chosen?.datetime?.drop(11)?.takeIf { normalizeTime(it) != null } ?: normalizeTime(time) ?: "09:00",
                searchType = chosen?.searchType ?: searchType,
                fromPoint = from,
                toPoint = to,
                fromIsPlace = from != null && fromIsPlace,
                toIsPlace = to != null && toIsPlace,
            ),
        ),
    )
    val state: StateFlow<TransitScreenState> = _state
    private var searching: Job? = null

    init {
        viewModelScope.launch { loadStations() }
    }

    private suspend fun loadStations() {
        val a = api() ?: return
        try {
            val form = _state.value.form
            val nf = form.fromPoint?.let { a.transitStations(it.lat, it.lng) }.orEmpty()
            val nt = form.toPoint?.let { a.transitStations(it.lat, it.lng) }.orEmpty()
            _state.update { s ->
                s.copy(
                    nearFrom = nf,
                    nearTo = nt,
                    form = s.form.copy(
                        fromStation = s.form.fromStation.ifBlank { nf.firstOrNull()?.station?.nameJa.orEmpty() },
                        toStation = s.form.toStation.ifBlank { nt.firstOrNull()?.station?.nameJa.orEmpty() },
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without the list the stations are still found on the server, or typed in.
        }
    }

    fun edit(change: (TransitForm) -> TransitForm) = _state.update { it.copy(form = change(it.form), message = null) }

    /** A place or hotel as start: its point, no station yet; the stations near it are offered as chips. */
    fun pickFrom(hit: EndpointHit) {
        val p = TransitPoint(hit.lat, hit.lng, hit.name)
        _state.update { it.copy(form = it.form.copy(fromPoint = p, fromStation = "", fromIsPlace = true), nearFrom = emptyList(), message = null) }
        nearFor(p) { near -> _state.update { s -> if (s.form.fromPoint == p) s.copy(nearFrom = near) else s } }
    }

    fun pickTo(hit: EndpointHit) {
        val p = TransitPoint(hit.lat, hit.lng, hit.name)
        _state.update { it.copy(form = it.form.copy(toPoint = p, toStation = "", toIsPlace = true), nearTo = emptyList(), message = null) }
        nearFor(p) { near -> _state.update { s -> if (s.form.toPoint == p) s.copy(nearTo = near) else s } }
    }

    fun clearFrom() = _state.update { it.copy(form = it.form.copy(fromPoint = null, fromIsPlace = false), nearFrom = emptyList()) }
    fun clearTo() = _state.update { it.copy(form = it.form.copy(toPoint = null, toIsPlace = false), nearTo = emptyList()) }

    private fun nearFor(p: TransitPoint, apply: (List<TransitStationNear>) -> Unit) {
        viewModelScope.launch {
            try {
                apply(api()?.transitStations(p.lat, p.lng).orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No chips: the server still finds the nearest station.
            }
        }
    }

    fun search() {
        val form = _state.value.form
        (formProblem(form) ?: when {
            form.fromStation.isBlank() && form.fromPoint == null -> "Start eintragen (Ort, Hotel oder Bahnhof)."
            form.toStation.isBlank() && form.toPoint == null -> "Ziel eintragen (Ort, Hotel oder Bahnhof)."
            else -> null
        })?.let { msg -> _state.update { it.copy(message = msg) }; return }
        searching?.cancel()
        _state.update { it.copy(searching = true, message = null) }
        searching = viewModelScope.launch {
            val a = api() ?: run { _state.update { it.copy(searching = false, message = "Kein Server eingerichtet.") }; return@launch }
            try {
                var attempt = 0
                var r: TransitResult? = null
                while (r == null) {
                    try {
                        r = a.transitOrThrow(requestFor(form))
                    } catch (e: ApiException) {
                        // Only a dropped connection is asked again: the server finished meanwhile and answers from its store.
                        if (e.kind != ApiErrorKind.NETWORK || attempt >= retryDelaysMillis.size) throw e
                        _state.update { it.copy(message = "Verbindung unterbrochen, frage erneut …") }
                        kotlinx.coroutines.delay(retryDelaysMillis[attempt++])
                    }
                }
                _state.update { it.copy(searching = false, result = r?.let { res -> resultWithPlaces(res, form) }, message = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(searching = false, result = null, message = transitError(e)) }
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, result = null, message = "Die Suche ist fehlgeschlagen.") }
            }
        }
    }
}
