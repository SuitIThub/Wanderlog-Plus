package de.wanderlogplus.app.ui.transit

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.sync.TransitPoint

/** The way to [toItem] from the place before it in the day's program, with both positions. */
data class TransitWay(val fromItem: DayItem, val toItem: DayItem, val from: TransitPoint, val to: TransitPoint)

/** The way that ends at [itemId] on [date]; null when the item or the place before it has no position. */
fun transitWay(snapshot: TripSnapshot, date: String, itemId: String): TransitWay? {
    val day = snapshot.queries()?.dayFor(date) ?: return null
    val places = snapshot.dayItemsFor(day.id).filter { it.kind == DayItemKind.PLACE }.mapNotNull { item ->
        snapshot.places[item.placeId]?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }?.let { item to it }
    }
    val i = places.indexOfFirst { it.first.id == itemId }
    if (i <= 0) return null
    val (fromItem, fromPlace) = places[i - 1]
    val (toItem, toPlace) = places[i]
    return TransitWay(
        fromItem, toItem,
        TransitPoint(fromPlace.lat, fromPlace.lng, fromPlace.name),
        TransitPoint(toPlace.lat, toPlace.lng, toPlace.name),
    )
}
