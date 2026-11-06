package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.shared.model.ReservationWindow

/** The reservation window as typed: "reservierbar ab", "reservieren bis" (yyyy-MM-dd or empty) and "erledigt". */
data class ReservationDraft(val opens: String = "", val by: String = "", val done: Boolean = false)

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** The window to store: null when nothing is set; anything set in the app counts as by hand (research keeps it). */
fun ReservationDraft.toWindow(old: ReservationWindow?): ReservationWindow? {
    val o = opens.trim().ifBlank { null }
    val b = by.trim().ifBlank { null }
    if (o == null && b == null && !done) return null
    return (old ?: ReservationWindow()).copy(opens = o, by = b, done = done, byHand = true)
}

fun ReservationWindow?.toDraft(): ReservationDraft =
    this?.let { ReservationDraft(it.opens.orEmpty(), it.by.orEmpty(), it.done) } ?: ReservationDraft()

/** A German message for a wrong entry, or null. */
fun validateReservation(d: ReservationDraft): String? {
    val o = d.opens.trim()
    val b = d.by.trim()
    return when {
        o.isNotEmpty() && !DATE.matches(o) -> "Reservierbar ab bitte als JJJJ-MM-TT"
        b.isNotEmpty() && !DATE.matches(b) -> "Reservieren bis bitte als JJJJ-MM-TT"
        o.isNotEmpty() && b.isNotEmpty() && o > b -> "„Reservierbar ab“ liegt nach „Reservieren bis“"
        else -> null
    }
}

/** "Reservierbar ab Do, 01.04. · Reservieren bis Di, 20.04." with " (geschätzt)" and " · erledigt". */
fun reservationText(w: ReservationWindow, estimated: Boolean): String {
    val parts = listOfNotNull(w.opens?.let { "Reservierbar ab " + dayLabel(it) }, w.by?.let { "Reservieren bis " + dayLabel(it) })
    return parts.joinToString(" · ") + (if (estimated) " (geschätzt)" else "") + (if (w.done) " · erledigt" else "")
}

/** Two date fields and, for rides and stays, the "erledigt" box. Tags: res-opens, res-by, res-done. */
@Composable
fun ReservationFields(d: ReservationDraft, showDone: Boolean, onChange: (ReservationDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            d.opens, { onChange(d.copy(opens = it)) }, label = { Text("Reservierbar ab (JJJJ-MM-TT)") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("res-opens"),
        )
        OutlinedTextField(
            d.by, { onChange(d.copy(by = it)) }, label = { Text("Reservieren bis (JJJJ-MM-TT)") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("res-by"),
        )
        if (showDone) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = d.done, onCheckedChange = { onChange(d.copy(done = it)) }, modifier = Modifier.testTag("res-done"))
                Text("Reservierung erledigt")
            }
        }
    }
}
