package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.LabeledDropdown
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.query.DayNow

/**
 * The editable part of a stay. [placeId] is the linked place; a place picked in the search ([pickedName] with
 * coordinates) replaces it with a new one. [regionId] null: chosen automatically from the position.
 */
data class StayDraft(
    val placeId: String = "",
    val name: String = "",
    val address: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val picked: Boolean = false,
    val regionId: String? = null,
    val checkIn: String = "",
    val checkOut: String = "",
    val cancellationDeadline: String = "",
    val cancellationPolicy: String = "",
    val confirmationNumber: String = "",
    val adults: String = "",
    val rooms: String = "",
    val note: String = "",
)

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

fun stayDraftOf(snapshot: TripSnapshot, id: String): StayDraft? {
    if (id == NEW_ID) return StayDraft()
    val s = snapshot.stays.firstOrNull { it.id == id } ?: return null
    val p = snapshot.places[s.placeId]
    return StayDraft(
        placeId = s.placeId, name = p?.name.orEmpty(), address = p?.address.orEmpty(),
        lat = p?.lat?.takeIf { it != 0.0 }, lng = p?.lng?.takeIf { it != 0.0 },
        regionId = s.regionId, checkIn = s.checkIn, checkOut = s.checkOut,
        cancellationDeadline = s.cancellationDeadline?.take(10).orEmpty(), cancellationPolicy = s.cancellationPolicy.orEmpty(),
        confirmationNumber = s.confirmationNumber.orEmpty(), adults = s.adults?.toString().orEmpty(), rooms = s.rooms?.toString().orEmpty(),
        note = s.note,
    )
}

/** First problem as German text, or null when the stay can be saved. */
fun validateStay(d: StayDraft): String? = when {
    d.name.isBlank() -> "Die Unterkunft braucht einen Namen"
    !DATE.matches(d.checkIn.trim()) || !DATE.matches(d.checkOut.trim()) -> "Check-in und Check-out bitte als JJJJ-MM-TT"
    d.checkOut.trim() <= d.checkIn.trim() -> "Der Check-out muss nach dem Check-in liegen"
    d.cancellationDeadline.isNotBlank() && !DATE.matches(d.cancellationDeadline.trim()) -> "Stornofrist bitte als JJJJ-MM-TT"
    d.adults.isNotBlank() && d.adults.trim().toIntOrNull()?.takeIf { it in 1..20 } == null -> "Erwachsene als Zahl eintragen"
    d.rooms.isNotBlank() && d.rooms.trim().toIntOrNull()?.takeIf { it in 1..20 } == null -> "Zimmer als Zahl eintragen"
    else -> null
}

/**
 * The region of a stay at a position: the one of the nearest stay or saved place with a region (within 60 km);
 * null when nothing is near.
 */
fun regionNear(snapshot: TripSnapshot, lat: Double?, lng: Double?, exceptStay: String? = null): String? {
    if (lat == null || lng == null) return null
    fun has(p: Place?) = p != null && !(p.lat == 0.0 && p.lng == 0.0)
    val candidates = snapshot.stays.filter { it.id != exceptStay && it.regionId != null }.mapNotNull { s -> snapshot.places[s.placeId]?.takeIf(::has)?.let { it to s.regionId!! } } +
        snapshot.savedPlaces.filter { it.regionId != null }.mapNotNull { s -> snapshot.places[s.placeId]?.takeIf(::has)?.let { it to s.regionId!! } }
    return candidates.map { (p, r) -> DayNow.km(lat, lng, p.lat, p.lng) to r }.filter { it.first <= 60 }.minByOrNull { it.first }?.second
}

/** A saved stay: its fields, and a new place to create first when one was picked in the search. */
data class StaySave(val stay: Stay, val newPlace: Place?)

/** [newPlaceId] is used for a picked place; [existing] keeps fields the form does not show (travellers). */
fun stayToSave(snapshot: TripSnapshot, d: StayDraft, existing: Stay?, newPlaceId: String): StaySave {
    val newPlace = if (d.picked || d.placeId.isBlank()) {
        Place(newPlaceId, name = d.name.trim(), lat = d.lat ?: 0.0, lng = d.lng ?: 0.0, address = d.address.trim().ifBlank { null })
    } else null
    val base = existing ?: Stay("")
    val stay = base.copy(
        placeId = newPlace?.id ?: d.placeId,
        regionId = d.regionId ?: regionNear(snapshot, d.lat, d.lng, existing?.id) ?: base.regionId,
        checkIn = d.checkIn.trim(),
        checkOut = d.checkOut.trim(),
        cancellationDeadline = d.cancellationDeadline.trim().ifBlank { null },
        cancellationPolicy = d.cancellationPolicy.trim().ifBlank { null },
        confirmationNumber = d.confirmationNumber.trim().ifBlank { null },
        adults = d.adults.trim().toIntOrNull(),
        rooms = d.rooms.trim().toIntOrNull(),
        note = d.note.trim(),
    )
    return StaySave(stay, newPlace)
}

/**
 * Create or edit a stay: the hotel (place search sets name, address and position), region (automatic from the
 * position or chosen), check-in/-out, cancellation deadline and terms, booking number, guests, rooms and a note.
 * Nothing is written before "Speichern".
 */
@Composable
fun StayEditScreen(
    snapshot: TripSnapshot,
    id: String,
    onBack: () -> Unit,
    onSave: (existing: Stay?, draft: StayDraft) -> Unit,
    search: PlaceSearch?,
    modifier: Modifier = Modifier,
) {
    val initial = remember(id) { stayDraftOf(snapshot, id) }
    if (initial == null) {
        Column(modifier.fillMaxSize().padding(16.dp)) {
            TextButton(onClick = onBack) { Text("Zurück") }
            EmptyHint("Diese Unterkunft existiert nicht mehr.")
        }
        return
    }
    var d by remember(id) { mutableStateOf(initial) }
    var searching by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }

    if (searching && search != null) {
        val near = d.lat?.let { la -> d.lng?.let { la to it } }
            ?: snapshot.stays.firstNotNullOfOrNull { s -> snapshot.places[s.placeId]?.takeIf { it.lat != 0.0 }?.let { it.lat to it.lng } }
        PlaceSearchPanel(
            search = search, near = near,
            onPick = { p ->
                d = d.copy(name = p.name, address = p.detail, lat = p.lat, lng = p.lng, picked = true)
                searching = false
            },
            onCancel = { searching = false },
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).testTag("stay-edit")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Abbrechen") }
            Text(if (id == NEW_ID) "Neue Unterkunft" else "Unterkunft bearbeiten", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val problem = validateStay(d)
                    if (problem != null) error = problem else {
                        onSave(snapshot.stays.firstOrNull { it.id == id }, d)
                        onBack()
                    }
                },
                modifier = Modifier.padding(end = 8.dp).testTag("stay-save"),
            ) { Text("Speichern") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("stay-error")) }
            SectionLabel("Unterkunft")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    d.name, { d = d.copy(name = it) }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.weight(1f).testTag("stay-name"),
                )
                if (search != null) {
                    IconButton(onClick = { searching = true }, modifier = Modifier.testTag("stay-search")) {
                        Icon(Icons.Filled.Search, contentDescription = "Ort suchen")
                    }
                }
            }
            OutlinedTextField(d.address, { d = d.copy(address = it) }, label = { Text("Adresse") }, modifier = Modifier.fillMaxWidth().testTag("stay-address"))
            if (d.lat == null) {
                Text("Ohne Position (über die Suche setzen) fehlen Karte, Wege und Wetter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val auto = regionNear(snapshot, d.lat, d.lng, id.takeIf { it != NEW_ID })?.let { r -> snapshot.regions.firstOrNull { it.id == r }?.name }
            LabeledDropdown(
                "stay-region", "Region",
                d.regionId?.let { r -> snapshot.regions.firstOrNull { it.id == r }?.name } ?: "Automatisch" + (auto?.let { " ($it)" } ?: ""),
                listOf<Pair<String?, String>>(null to "Automatisch" + (auto?.let { " ($it)" } ?: "")) + snapshot.regions.map { it.id to it.name },
                onSelect = { d = d.copy(regionId = it) },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Zeitraum")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(d.checkIn, { d = d.copy(checkIn = it) }, label = { Text("Check-in (JJJJ-MM-TT)") }, singleLine = true, modifier = Modifier.weight(1f).testTag("stay-checkin"))
                OutlinedTextField(d.checkOut, { d = d.copy(checkOut = it) }, label = { Text("Check-out") }, singleLine = true, modifier = Modifier.weight(1f).testTag("stay-checkout"))
            }

            SectionLabel("Buchung")
            OutlinedTextField(d.confirmationNumber, { d = d.copy(confirmationNumber = it) }, label = { Text("Buchungsnummer") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("stay-booking"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    d.adults, { d = d.copy(adults = it) }, label = { Text("Erwachsene") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("stay-adults"),
                )
                OutlinedTextField(
                    d.rooms, { d = d.copy(rooms = it) }, label = { Text("Zimmer") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("stay-rooms"),
                )
            }
            OutlinedTextField(d.cancellationDeadline, { d = d.copy(cancellationDeadline = it) }, label = { Text("Stornofrist (JJJJ-MM-TT)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("stay-deadline"))
            OutlinedTextField(d.cancellationPolicy, { d = d.copy(cancellationPolicy = it) }, label = { Text("Stornobedingungen") }, modifier = Modifier.fillMaxWidth().testTag("stay-policy"))
            OutlinedTextField(d.note, { d = d.copy(note = it) }, label = { Text("Notiz") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("stay-note"))
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}
