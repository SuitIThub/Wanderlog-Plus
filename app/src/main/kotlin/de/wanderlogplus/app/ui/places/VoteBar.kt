package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Vote
import de.wanderlogplus.shared.model.Votes
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.JsonPrimitive

/** "👍", "👍👍" or "👎". */
fun voteSymbol(value: Int): String = when {
    value >= Votes.DOUBLE -> "👍👍"
    value > 0 -> "👍"
    value < 0 -> "👎"
    else -> ""
}

/** "daniel 👍👍 · selina 👍" (withdrawn votes left out). */
fun voteNames(votes: List<Vote>): String =
    votes.filter { it.value != 0 }.sortedWith(compareByDescending<Vote> { it.value }.thenBy { it.voter.lowercase() })
        .joinToString(" · ") { "${it.voter} ${voteSymbol(it.value)}" }

/**
 * The edit for [me] voting [value] on [savedPlaceId] (0 withdraws): writes the vote and moves the place between
 * „Idee“ and „Vorgemerkt“ by the new score ([Votes.nextStatus]); the server checks the same again.
 */
fun voteEdits(snapshot: TripSnapshot, savedPlaceId: String, me: String, value: Int): (LocalEditor) -> Unit = { e ->
    val id = Votes.id(savedPlaceId, me)
    val vote = Vote(id, savedPlaceId, me, value)
    if (snapshot.votes.any { it.id == id }) {
        e.set(EntityType.VOTE, id, mapOf("value" to JsonPrimitive(value)))
    } else {
        e.create(EntityType.VOTE, EntityCodec.fields(vote), id = id)
    }
    val saved = snapshot.savedPlaces.firstOrNull { it.id == savedPlaceId }
    if (saved != null) {
        val after = snapshot.votes.filter { it.id != id } + vote
        val next = Votes.nextStatus(saved.status, Votes.score(after, savedPlaceId))
        if (next != saved.status) e.set(EntityType.SAVED_PLACE, savedPlaceId, mapOf("status" to JsonPrimitive(next.name)))
    }
}

/** Buttons 👍 / 👍👍 / 👎 (tapping one's own vote again withdraws it), the score and who voted how. */
@Composable
fun VoteBar(votes: List<Vote>, me: String?, onVote: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shown = votes.filter { it.value != 0 }
    val mine = me?.let { m -> votes.firstOrNull { it.voter == m }?.value } ?: 0
    val score = shown.sumOf { it.value }
    Column(modifier.fillMaxWidth().testTag("vote-bar"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (me != null) {
                listOf(Triple(Votes.LIKE, "👍", "vote-like"), Triple(Votes.DOUBLE, "👍👍", "vote-double"), Triple(Votes.DISLIKE, "👎", "vote-dislike")).forEach { (v, label, tag) ->
                    FilterChip(selected = mine == v, onClick = { onVote(if (mine == v) 0 else v) }, label = { Text(label) }, modifier = Modifier.testTag(tag))
                }
            }
            if (shown.isNotEmpty()) {
                Text(if (score > 0) "+$score" else "$score", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("vote-score"))
            }
        }
        if (shown.isNotEmpty()) {
            Text(voteNames(votes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("vote-names"))
        }
    }
}

/** A planned place with too little approval: its first planned day from [from] on and who voted how. */
data class PlanDoubt(val savedPlaceId: String, val name: String, val date: String, val votes: String)

/** Planned places with too little approval ([Votes.planDoubt]), planned from [from] on; only those on [onDate] when given. */
fun planDoubts(snapshot: TripSnapshot, from: String, onDate: String? = null): List<PlanDoubt> {
    if (snapshot.votes.isEmpty()) return emptyList()
    val dateOf = snapshot.days.associate { it.id to it.date }
    return snapshot.savedPlaces.mapNotNull { s ->
        if (!Votes.planDoubt(s.status, snapshot.votes, s.id)) return@mapNotNull null
        val dates = snapshot.dayItems.filter { it.kind == DayItemKind.PLACE && it.placeId == s.placeId }.mapNotNull { dateOf[it.dayId] }.filter { it >= from }
        val date = (if (onDate != null) dates.firstOrNull { it == onDate } else dates.minOrNull()) ?: return@mapNotNull null
        val name = snapshot.places[s.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Ort"
        PlanDoubt(s.id, name, date, voteNames(snapshot.votes.filter { it.savedPlaceId == s.id }))
    }.sortedBy { it.date }
}

/** "Kaum noch Zustimmung": planned places the travellers voted down; a tap opens the place. */
@Composable
fun PlanDoubtCard(doubts: List<PlanDoubt>, onOpen: ((String) -> Unit)?, modifier: Modifier = Modifier, showDate: Boolean = true) {
    if (doubts.isEmpty()) return
    SurfaceCard(modifier.fillMaxWidth().testTag("plan-doubts")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Kaum noch Zustimmung", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            doubts.forEach { d ->
                Column(
                    Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable { onOpen(d.savedPlaceId) } else Modifier).testTag("plan-doubt-${d.savedPlaceId}"),
                ) {
                    Text(d.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(if (showDate) "Eingeplant ${de.wanderlogplus.app.ui.dayLabel(d.date)}" else null, d.votes).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
