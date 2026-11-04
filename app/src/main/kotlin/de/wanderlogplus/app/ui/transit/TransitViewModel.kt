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
fun requestFor(f: TransitForm, from: TransitPoint, to: TransitPoint): TransitRequest = TransitRequest(
    from = from,
    to = to,
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
    else -> "Die Fahrplanauskunft antwortet gerade nicht. Später noch einmal versuchen."
}

/**
 * "Verbindung abfragen" for one way of a day: from [from] to [to], starting at [date]/[time]. A picked connection
 * ([chosen]) fills in its stations and time. [search] always asks for current data (not the cache).
 */
class TransitViewModel(
    private val api: suspend () -> SyncApi?,
    private val from: TransitPoint,
    private val to: TransitPoint,
    date: String,
    time: String?,
    chosen: TransitResult?,
) : ViewModel() {
    private val _state = MutableStateFlow(
        TransitScreenState(
            form = TransitForm(
                fromStation = chosen?.fromStation?.nameJa.orEmpty(),
                toStation = chosen?.toStation?.nameJa.orEmpty(),
                date = chosen?.datetime?.take(10) ?: date,
                time = chosen?.datetime?.drop(11)?.takeIf { normalizeTime(it) != null } ?: normalizeTime(time) ?: "09:00",
                searchType = chosen?.searchType ?: "depart",
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
            val nf = a.transitStations(from.lat, from.lng)
            val nt = a.transitStations(to.lat, to.lng)
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

    fun search() {
        val form = _state.value.form
        formProblem(form)?.let { msg -> _state.update { it.copy(message = msg) }; return }
        searching?.cancel()
        _state.update { it.copy(searching = true, message = null) }
        searching = viewModelScope.launch {
            val a = api() ?: run { _state.update { it.copy(searching = false, message = "Kein Server eingerichtet.") }; return@launch }
            try {
                val r = a.transitOrThrow(requestFor(form, from, to))
                _state.update { it.copy(searching = false, result = r) }
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
