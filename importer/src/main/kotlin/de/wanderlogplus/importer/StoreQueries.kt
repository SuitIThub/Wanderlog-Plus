package de.wanderlogplus.importer

import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.query.TripQueries
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType

private inline fun <reified T> EntityStore.decodeAll(type: EntityType): List<T> =
    all(type).filter { !it.isDeleted }.map { EntityCodec.decode<T>(it) }

fun EntityStore.tripQueries(): TripQueries {
    val trip = decodeAll<Trip>(EntityType.TRIP).single()
    return TripQueries(
        trip = trip,
        stays = decodeAll(EntityType.STAY),
        days = decodeAll(EntityType.DAY),
        regions = decodeAll(EntityType.REGION),
        savedPlaces = decodeAll(EntityType.SAVED_PLACE),
        dayItems = decodeAll(EntityType.DAY_ITEM),
    )
}
