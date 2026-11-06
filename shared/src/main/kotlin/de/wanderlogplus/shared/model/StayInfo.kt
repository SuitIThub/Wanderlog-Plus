package de.wanderlogplus.shared.model

import kotlinx.serialization.Serializable

/** A seat of a ride: [car] for trains ("12"), [seat] like "111" or "43E", optionally whose it is. */
@Serializable
data class Seat(val car: String? = null, val seat: String, val traveler: String? = null)

/**
 * What a stay offers: the booked room and the property. Filled by research, correctable by hand; the keys in
 * [editedByHand] ("room.type", "property.amenities.Bad" …) survive a new research ([StayInfoMerge]).
 */
@Serializable
data class StayInfo(
    val room: RoomInfo = RoomInfo(),
    val property: PropertyInfo = PropertyInfo(),
    val sources: List<String> = emptyList(),
    val researchedAt: Long = 0,
    val editedByHand: Set<String> = emptySet(),
)

@Serializable
data class RoomInfo(
    val type: String = "",
    val description: String = "",
    val sizeSqm: Double? = null,
    val beds: String = "",
    val bath: String = "",
    val view: String = "",
    val maxGuests: Int? = null,
    val amenities: List<AmenityGroup> = emptyList(),
)

@Serializable
data class PropertyInfo(
    val description: String = "",
    val meals: String = "",
    val houseRules: String = "",
    val amenities: List<AmenityGroup> = emptyList(),
)

/** A group of amenities: Bad, Schlafen, Technik, Essen & Trinken, Onsen & Spa, Service, Sonstiges. */
@Serializable
data class AmenityGroup(val name: String, val items: List<Amenity> = emptyList())

@Serializable
data class Amenity(val name: String, val note: String = "")

/** Combines a new research result with what the travellers changed by hand. */
object StayInfoMerge {
    /** [new] everywhere except the fields and amenity groups listed in [old]'s editedByHand, which keep their value. */
    fun merge(old: StayInfo?, new: StayInfo): StayInfo {
        if (old == null) return new
        val hand = old.editedByHand
        fun <T> pick(key: String, mine: T, theirs: T): T = if (key in hand) mine else theirs
        fun groups(prefix: String, mine: List<AmenityGroup>, theirs: List<AmenityGroup>): List<AmenityGroup> {
            val kept = mine.filter { "$prefix.${it.name}" in hand }
            return theirs.filterNot { g -> kept.any { it.name == g.name } } + kept
        }
        val r = old.room
        val n = new.room
        val p = old.property
        val q = new.property
        return new.copy(
            room = RoomInfo(
                type = pick("room.type", r.type, n.type),
                description = pick("room.description", r.description, n.description),
                sizeSqm = pick("room.sizeSqm", r.sizeSqm, n.sizeSqm),
                beds = pick("room.beds", r.beds, n.beds),
                bath = pick("room.bath", r.bath, n.bath),
                view = pick("room.view", r.view, n.view),
                maxGuests = pick("room.maxGuests", r.maxGuests, n.maxGuests),
                amenities = groups("room.amenities", r.amenities, n.amenities),
            ),
            property = PropertyInfo(
                description = pick("property.description", p.description, q.description),
                meals = pick("property.meals", p.meals, q.meals),
                houseRules = pick("property.houseRules", p.houseRules, q.houseRules),
                amenities = groups("property.amenities", p.amenities, q.amenities),
            ),
            editedByHand = hand,
        )
    }

    /** [info] with [key] marked as changed by hand. */
    fun edited(info: StayInfo, key: String): StayInfo = info.copy(editedByHand = info.editedByHand + key)
}
