package de.wanderlogplus.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.EmptyState
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.ratingLabel
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.app.ui.trip.RegionChip
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.query.TodayQueries
import de.wanderlogplus.shared.query.TodayState
import de.wanderlogplus.shared.query.isOpenAt
import de.wanderlogplus.shared.query.sortSuggestions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val MAX_OPEN_NOW = 5
private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

/** [now] is injected so tests can pin "today"; production passes the device's local time. */
@Composable
fun TodayScreen(
    snapshot: TripSnapshot,
    now: LocalDateTime,
    onOpenDay: (date: String) -> Unit,
    onOpenTrip: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trip = snapshot.trip
    val queries = remember(snapshot) { snapshot.queries() }
    val state = remember(snapshot, now.toLocalDate()) {
        if (trip == null || queries == null) TodayState.Unknown
        else TodayQueries(queries, trip, snapshot.stays, snapshot.flights, snapshot.trains).state(now.toLocalDate())
    }
    when (state) {
        TodayState.Unknown -> EmptyState(onSyncNow, modifier)
        is TodayState.Before -> Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                if (state.daysUntilStart == 1L) "Noch 1 Tag" else "Noch ${state.daysUntilStart} Tage",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp).testTag("countdown"),
            )
            if (state.deadlines.isNotEmpty()) {
                Section("Fristen") {
                    state.deadlines.forEachIndexed { i, d ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("deadline-$i"),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(dayLabel(d.date), style = MaterialTheme.typography.labelLarge)
                            Text(snapshot.places[d.stay.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft")
                            Text("Stornofrist", color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
        TodayState.After -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Willkommen zurück", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onOpenTrip) { Text("Zur Reise") }
        }
        is TodayState.During -> During(snapshot, state, now, onOpenDay, modifier)
    }
}

@Composable
private fun During(
    snapshot: TripSnapshot,
    state: TodayState.During,
    now: LocalDateTime,
    onOpenDay: (String) -> Unit,
    modifier: Modifier,
) {
    val view = state.day
    val date = view.date
    val region = view.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id } }
    val stay = view.stay
    val stayName = stay?.let { snapshot.places[it.placeId]?.name }?.takeIf { it.isNotBlank() }
    val stayHint = when {
        stay == null -> null
        stay.checkIn == date -> "Check-in heute"
        stay.checkOut == date -> "Check-out heute"
        else -> null
    }
    val items = remember(snapshot, view.day) { view.day?.let { snapshot.dayItemsFor(it.id) }.orEmpty() }
    val openNow = remember(snapshot, date, now) {
        val dow = now.dayOfWeek.value % 7 // 0 = Sunday
        val time = now.format(HH_MM)
        val all = snapshot.queries()?.suggestionsFor(date).orEmpty().map { Suggestion(it, snapshot.places[it.placeId]) }
        sortSuggestions(all.filter { isOpenAt(it.place?.openingPeriods.orEmpty(), dow, time) != false }).take(MAX_OPEN_NOW)
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Column(
            Modifier.fillMaxWidth().clickable { onOpenDay(date) }.padding(16.dp).testTag("today-header"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(dayLabel(date), style = MaterialTheme.typography.titleLarge)
            view.day?.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            region?.let { RegionChip(it.name, regionColor(it.color)) }
            if (stayName != null || stayHint != null) {
                Text(
                    listOfNotNull(stayName?.let { "Unterkunft: $it" }, stayHint).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.movements.isNotEmpty()) {
            Section("Fahrten heute") {
                state.movements.forEachIndexed { i, m ->
                    Text(
                        movementLine(m),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("movement-$i"),
                    )
                }
            }
        }

        Section("Programm") {
            if (items.isEmpty()) {
                Text("Noch nichts geplant", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.testTag("today-program")) {
                items.forEach { item ->
                    val name = when (item.kind) {
                        DayItemKind.PLACE -> snapshot.places[item.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort"
                        DayItemKind.NOTE -> item.text
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenDay(date) }.padding(vertical = 6.dp).testTag("today-item-${item.id}"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item.time?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        if (openNow.isNotEmpty()) {
            Section("Jetzt geöffnet in der Nähe") {
                Column(Modifier.testTag("open-now-list")) {
                    openNow.forEach { s ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("open-now-${s.saved.id}")) {
                            Text(s.place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort", style = MaterialTheme.typography.bodyLarge)
                            s.place?.rating?.let {
                                Text(
                                    ratingLabel(it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun movementLine(m: Movement): String {
    val time = m.time?.takeIf { it.isNotBlank() } ?: "--:--"
    return when (m) {
        is Movement.FlightMove -> {
            val f = m.flight
            val id = listOfNotNull(f.airlineCode ?: f.airline, f.number).joinToString(" ")
            "$time  Flug${if (id.isNotBlank()) " $id" else ""}: ${endpoint(f.depart.name, f.depart.code)} → ${endpoint(f.arrive.name, f.arrive.code)}"
        }
        is Movement.TrainMove -> {
            val t = m.train
            "$time  Zug${t.carrier?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}: ${endpoint(t.depart.name, t.depart.code)} → ${endpoint(t.arrive.name, t.arrive.code)}"
        }
    }
}

private fun endpoint(name: String, code: String?) = name.takeIf { it.isNotBlank() } ?: code ?: "?"

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}
