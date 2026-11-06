package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.shared.model.Amenity
import de.wanderlogplus.shared.model.AmenityGroup
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.StayInfo
import de.wanderlogplus.shared.model.StayInfoMerge

/** [old] (or an empty info) changed by [update] and [field] marked as changed by hand, so research keeps it. */
fun stayInfoEdit(old: StayInfo?, field: String, update: (StayInfo) -> StayInfo): StayInfo =
    StayInfoMerge.edited(update(old ?: StayInfo()), field)

/** The label a photo gets from "Zimmerfoto" in its menu: marked, or unmarked again. */
fun toggledRoomLabel(a: Attachment): String? = if (a.label == Attachment.LABEL_ROOM) null else Attachment.LABEL_ROOM

/** The amenity groups offered for new entries. */
val AMENITY_GROUPS = listOf("Bad", "Schlafen", "Technik", "Essen & Trinken", "Onsen & Spa", "Service", "Sonstiges")

/** "Zimmer" and "Ausstattung" of the stay form; every change marks its field as changed by hand. */
@Composable
internal fun StayInfoEditor(info: StayInfo?, onChange: (StayInfo) -> Unit) {
    val i = info ?: StayInfo()
    fun text(label: String, value: String, key: String, tag: String, lines: Int = 1, set: (StayInfo, String) -> StayInfo) =
        @Composable {
            OutlinedTextField(
                value, { v -> onChange(stayInfoEdit(info, key) { set(it, v) }) },
                label = { Text(label) }, singleLine = lines == 1, minLines = lines,
                modifier = Modifier.fillMaxWidth().testTag(tag),
            )
        }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Zimmer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        text("Zimmertyp", i.room.type, "room.type", "stay-room-type") { s, v -> s.copy(room = s.room.copy(type = v)) }()
        text("Betten", i.room.beds, "room.beds", "stay-room-beds") { s, v -> s.copy(room = s.room.copy(beds = v)) }()
        text("Bad", i.room.bath, "room.bath", "stay-room-bath") { s, v -> s.copy(room = s.room.copy(bath = v)) }()
        text("Aussicht", i.room.view, "room.view", "stay-room-view") { s, v -> s.copy(room = s.room.copy(view = v)) }()
        text("Größe (m²)", i.room.sizeSqm?.let { "%.0f".format(it) }.orEmpty(), "room.sizeSqm", "stay-room-size") { s, v ->
            s.copy(room = s.room.copy(sizeSqm = v.replace(',', '.').trim().toDoubleOrNull()))
        }()
        text("Beschreibung", i.room.description, "room.description", "stay-room-description", lines = 2) { s, v -> s.copy(room = s.room.copy(description = v)) }()
        AmenityGroups(i.room.amenities, "room") { groups, key -> onChange(stayInfoEdit(info, key) { it.copy(room = it.room.copy(amenities = groups)) }) }

        Text("Unterkunft", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        text("Beschreibung", i.property.description, "property.description", "stay-property-description", lines = 2) { s, v -> s.copy(property = s.property.copy(description = v)) }()
        text("Verpflegung", i.property.meals, "property.meals", "stay-property-meals") { s, v -> s.copy(property = s.property.copy(meals = v)) }()
        text("Hausregeln", i.property.houseRules, "property.houseRules", "stay-property-rules", lines = 2) { s, v -> s.copy(property = s.property.copy(houseRules = v)) }()
        AmenityGroups(i.property.amenities, "property") { groups, key -> onChange(stayInfoEdit(info, key) { it.copy(property = it.property.copy(amenities = groups)) }) }
    }
}

/** Amenity groups with their entries: edit, delete, move, add; [onChange] gets the groups and the changed key. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AmenityGroups(groups: List<AmenityGroup>, prefix: String, onChange: (List<AmenityGroup>, String) -> Unit) {
    groups.forEachIndexed { gi, g ->
        val key = "$prefix.amenities.${g.name}"
        fun setItems(items: List<Amenity>) = onChange(groups.toMutableList().also { it[gi] = g.copy(items = items) }, key)
        Text(g.name, style = MaterialTheme.typography.labelLarge)
        g.items.forEachIndexed { ii, a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(a.name, { v -> setItems(g.items.toMutableList().also { it[ii] = a.copy(name = v) }) }, label = { Text("Eintrag") }, singleLine = true, modifier = Modifier.weight(1f).testTag("$prefix-amenity-$gi-$ii"))
                OutlinedTextField(a.note, { v -> setItems(g.items.toMutableList().also { it[ii] = a.copy(note = v) }) }, label = { Text("Notiz") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (ii > 0) setItems(g.items.toMutableList().also { java.util.Collections.swap(it, ii, ii - 1) }) }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Nach oben") }
                IconButton(onClick = { if (ii < g.items.lastIndex) setItems(g.items.toMutableList().also { java.util.Collections.swap(it, ii, ii + 1) }) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Nach unten") }
                IconButton(onClick = { setItems(g.items.filterIndexed { j, _ -> j != ii }) }) { Icon(Icons.Filled.Close, contentDescription = "Entfernen") }
            }
        }
        TextButton(onClick = { setItems(g.items + Amenity("")) }) { Icon(Icons.Filled.Add, contentDescription = null); Text("Eintrag") }
    }
    val missing = AMENITY_GROUPS.filter { name -> groups.none { it.name == name } }
    if (missing.isNotEmpty()) {
        androidx.compose.foundation.layout.FlowRow {
            missing.forEach { name ->
                TextButton(onClick = { onChange(groups + AmenityGroup(name, listOf(Amenity(""))), "$prefix.amenities.$name") }) { Text("+ $name") }
            }
        }
    }
}
