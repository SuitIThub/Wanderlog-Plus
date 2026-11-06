package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Elevator
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HotTub
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RoomService
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.SmokeFree
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.shared.model.Amenity
import de.wanderlogplus.shared.model.AmenityGroup
import de.wanderlogplus.shared.model.StayInfo

/** Fixed groups for the amenities, in this order, whatever the research called its groups. */
enum class AmenityCategory(val label: String, val iconKey: String) {
    BATH("Bad & Onsen", "hottub"),
    SLEEP("Schlafen & Zimmer", "bed"),
    TECH("Technik & Internet", "wifi"),
    FOOD("Essen & Trinken", "restaurant"),
    SERVICE("Service", "service"),
    OTHER("Sonstiges", "more"),
}

/** Amenities regrouped into one [category], each once. */
data class AmenityPanel(val category: AmenityCategory, val items: List<Amenity>)

/** One fact at the top of the room tab: icon key and text. */
data class StayFact(val iconKey: String, val text: String)

object AmenityCategories {
    private val ITEM_KEYS: List<Pair<AmenityCategory, List<String>>> = listOf(
        AmenityCategory.TECH to listOf("wlan", "wifi", "wi-fi", "internet", "tv", "fernseh", "steckdose", "usb", "klima", "heizung", "ladeg", "telefon", "air con"),
        AmenityCategory.FOOD to listOf("frühstück", "abendessen", "kaiseki", "restaurant", "bar", "kühlschrank", "wasserkocher", "tee", "kaffee", "minibar", "mikrowelle", "küche", "essen", "getränk", "snack"),
        AmenityCategory.BATH to listOf("bad", "dusche", "onsen", "rotenburo", "wanne", "toilette", "washlet", "föhn", "haartrockner", "sento", "spa", "sauna", "handtuch", "shampoo", "pflege", "bath", "shower"),
        AmenityCategory.SERVICE to listOf("rezeption", "gepäck", "wäsche", "laundry", "reinigung", "concierge", "shuttle", "parkplatz", "parken", "aufzug", "lift", "service", "check-in", "check-out", "safe", "schließfach"),
        AmenityCategory.SLEEP to listOf("bett", "futon", "kissen", "pyjama", "yukata", "schlaf", "verdunkel", "schrank", "hausschuh", "slipper", "tatami", "sofa", "schreibtisch", "zimmer"),
    )

    private val ICON_KEYS: List<Pair<String, List<String>>> = listOf(
        "wifi" to listOf("wlan", "wifi", "wi-fi", "internet"),
        "tv" to listOf("tv", "fernseh"),
        "ac" to listOf("klima", "air con"),
        "heat" to listOf("heizung"),
        "kettle" to listOf("wasserkocher", "tee", "kaffee"),
        "fridge" to listOf("kühlschrank", "minibar"),
        "breakfast" to listOf("frühstück"),
        "restaurant" to listOf("abendessen", "kaiseki", "restaurant", "bar", "essen"),
        "hottub" to listOf("onsen", "rotenburo", "sento", "wanne"),
        "shower" to listOf("dusche", "shower"),
        "dryer" to listOf("föhn", "haartrockner"),
        "wc" to listOf("toilette", "washlet"),
        "spa" to listOf("spa", "sauna"),
        "bath" to listOf("bad", "bath", "handtuch", "shampoo", "pflege"),
        "bed" to listOf("bett", "futon", "kissen", "tatami"),
        "clothes" to listOf("yukata", "pyjama", "schrank", "hausschuh", "slipper"),
        "lock" to listOf("safe", "schließfach"),
        "laundry" to listOf("wäsche", "laundry", "reinigung"),
        "luggage" to listOf("gepäck"),
        "parking" to listOf("parkplatz", "parken"),
        "elevator" to listOf("aufzug", "lift"),
        "reception" to listOf("rezeption", "concierge", "check-in", "check-out"),
        "smokefree" to listOf("nichtraucher", "rauchfrei"),
    )

    private fun find(text: String, table: List<Pair<AmenityCategory, List<String>>>) =
        text.lowercase().let { t -> table.firstOrNull { (_, keys) -> keys.any { it in t } }?.first }

    /** The group of [item]: by the item's words, else by the research's [group] name, else "Sonstiges". */
    fun of(group: String, item: String): AmenityCategory = find(item, ITEM_KEYS) ?: find(group, ITEM_KEYS) ?: AmenityCategory.OTHER

    /** An icon key for [item] ("check" when nothing fits). */
    fun iconKey(item: String): String = item.lowercase().let { t -> ICON_KEYS.firstOrNull { (_, keys) -> keys.any { it in t } }?.first } ?: "check"

    /** [groups] sorted into the fixed categories, every item once (first one wins), categories in their order. */
    fun panels(groups: List<AmenityGroup>): List<AmenityPanel> {
        val seen = mutableSetOf<String>()
        val byCategory = LinkedHashMap<AmenityCategory, MutableList<Amenity>>()
        groups.forEach { g ->
            g.items.filter { it.name.isNotBlank() }.forEach { a ->
                if (seen.add(a.name.trim().lowercase())) byCategory.getOrPut(of(g.name, a.name)) { mutableListOf() } += a
            }
        }
        return AmenityCategory.entries.mapNotNull { c -> byCategory[c]?.let { AmenityPanel(c, it) } }
    }
}

/** Room type, size, beds, bath, view, guests and meals, each with an icon. */
fun stayFacts(info: StayInfo): List<StayFact> {
    val r = info.room
    return listOfNotNull(
        r.type.ifBlank { null }?.let { StayFact("room", it) },
        r.sizeSqm?.let { StayFact("size", "%.0f m²".format(it)) },
        r.beds.ifBlank { null }?.let { StayFact("bed", it) },
        r.bath.ifBlank { null }?.let { StayFact("bath", it) },
        r.view.ifBlank { null }?.let { StayFact("view", it) },
        r.maxGuests?.let { StayFact("guests", "bis $it Personen") },
        info.property.meals.ifBlank { null }?.let { StayFact("restaurant", it) },
    )
}

internal fun amenityIcon(key: String): ImageVector = when (key) {
    "wifi" -> Icons.Filled.Wifi
    "tv" -> Icons.Filled.Tv
    "ac" -> Icons.Filled.AcUnit
    "heat" -> Icons.Filled.Thermostat
    "kettle" -> Icons.Filled.LocalCafe
    "fridge" -> Icons.Filled.Kitchen
    "breakfast" -> Icons.Filled.FreeBreakfast
    "restaurant" -> Icons.Filled.Restaurant
    "hottub" -> Icons.Filled.HotTub
    "shower" -> Icons.Filled.Shower
    "dryer" -> Icons.Filled.Air
    "wc" -> Icons.Filled.Wc
    "spa" -> Icons.Filled.Spa
    "bath" -> Icons.Filled.Bathtub
    "bed" -> Icons.Filled.Bed
    "clothes" -> Icons.Filled.Checkroom
    "lock" -> Icons.Filled.Lock
    "laundry" -> Icons.Filled.LocalLaundryService
    "luggage" -> Icons.Filled.Luggage
    "parking" -> Icons.Filled.LocalParking
    "elevator" -> Icons.Filled.Elevator
    "reception" -> Icons.Filled.RoomService
    "smokefree" -> Icons.Filled.SmokeFree
    "service" -> Icons.Filled.RoomService
    "more" -> Icons.Filled.MoreHoriz
    "room" -> Icons.Filled.MeetingRoom
    "size" -> Icons.Filled.SquareFoot
    "view" -> Icons.Filled.Landscape
    "guests" -> Icons.Filled.Group
    else -> Icons.Filled.Check
}

/** Cells in rows of [columns], each cell the same width. */
@Composable
private fun <T> Grid(items: List<T>, columns: Int, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item -> Column(Modifier.weight(1f)) { cell(item) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Two columns on phones, three on wide screens. */
@Composable
private fun AdaptiveGrid(content: @Composable (columns: Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) { content(if (maxWidth >= 560.dp) 3 else 2) }
}

@Composable
private fun IconLine(iconKey: String, text: String, note: String = "") {
    Row(verticalAlignment = Alignment.Top) {
        Icon(amenityIcon(iconKey), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp, end = 8.dp).size(18.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Auf einen Blick": the room's facts as icon tiles. */
@Composable
fun StayFactTiles(facts: List<StayFact>, modifier: Modifier = Modifier) {
    if (facts.isEmpty()) return
    SurfaceCard(modifier.testTag("stay-glance")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Auf einen Blick", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            AdaptiveGrid { columns -> Grid(facts, columns) { f -> IconLine(f.iconKey, f.text) } }
        }
    }
}

/** Rows shown per group before "+ n weitere". */
private const val PANEL_FOLDED = 6

/** Amenities of the room ("Im Zimmer") and of the property ("In der Unterkunft"), grouped, with icons, in a grid. */
@Composable
fun AmenityPanels(info: StayInfo, modifier: Modifier = Modifier) {
    val room = remember(info) { AmenityCategories.panels(info.room.amenities) }
    val property = remember(info) { AmenityCategories.panels(info.property.amenities) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (room.isNotEmpty()) PanelSection("Im Zimmer", "room", room)
        if (property.isNotEmpty()) PanelSection("In der Unterkunft", "property", property)
    }
}

@Composable
private fun PanelSection(title: String, key: String, panels: List<AmenityPanel>) {
    Column(Modifier.testTag("amenities-$key"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        panels.forEach { p ->
            var all by remember(p) { mutableStateOf(false) }
            SurfaceCard(Modifier.testTag("amenity-panel-$key-${p.category.name}")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(amenityIcon(p.category.iconKey), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                        Text(p.category.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    val shown = if (all || p.items.size <= PANEL_FOLDED) p.items else p.items.take(PANEL_FOLDED)
                    AdaptiveGrid { columns -> Grid(shown, columns) { a -> IconLine(AmenityCategories.iconKey(a.name), a.name, a.note) } }
                    if (p.items.size > PANEL_FOLDED) {
                        TextButton(onClick = { all = !all }) { Text(if (all) "Weniger" else "+ ${p.items.size - PANEL_FOLDED} weitere") }
                    }
                }
            }
        }
    }
}
