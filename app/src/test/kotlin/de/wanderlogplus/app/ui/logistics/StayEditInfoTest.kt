package de.wanderlogplus.app.ui.logistics

import de.wanderlogplus.shared.model.Amenity
import de.wanderlogplus.shared.model.AmenityGroup
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.RoomInfo
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.StayInfo
import de.wanderlogplus.shared.sync.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StayEditInfoTest {
    @Test
    fun editingMarksTheFieldAsChangedByHand() {
        val typed = stayInfoEdit(null, "room.type") { it.copy(room = it.room.copy(type = "Twin")) }
        assertEquals("Twin", typed.room.type)
        assertEquals(setOf("room.type"), typed.editedByHand)
        val bath = stayInfoEdit(typed, "room.amenities.Bad") { it.copy(room = it.room.copy(amenities = listOf(AmenityGroup("Bad", listOf(Amenity("Dusche")))))) }
        assertEquals(setOf("room.type", "room.amenities.Bad"), bath.editedByHand)
        val removed = stayInfoEdit(bath, "room.amenities.Bad") { it.copy(room = it.room.copy(amenities = listOf(AmenityGroup("Bad")))) }
        assertTrue(removed.room.amenities.single().items.isEmpty())
    }

    @Test
    fun theDraftCarriesTheInfoIntoTheSave() {
        val s = de.wanderlogplus.app.testing.Fixtures.trip()
        val stay = s.stays.first().copy(info = StayInfo(room = RoomInfo(type = "Alt")))
        val snapshot = s.copy(stays = listOf(stay) + s.stays.drop(1))
        val d = stayDraftOf(snapshot, stay.id)!!
        assertEquals("Alt", d.info?.room?.type)
        val edited = d.copy(info = stayInfoEdit(d.info, "room.type") { it.copy(room = it.room.copy(type = "Neu")) })
        val saved = stayToSave(snapshot, edited, stay, "new").stay
        assertEquals("Neu", saved.info!!.room.type)
        assertEquals(setOf("room.type"), saved.info!!.editedByHand)
    }

    @Test
    fun onlyChangedRoomMarksAreSaved() {
        val a = Attachment("a", EntityType.STAY, "st", kind = "photo", label = Attachment.LABEL_ROOM)
        val b = Attachment("b", EntityType.STAY, "st", kind = "photo")
        val c = Attachment("c", EntityType.STAY, "st", kind = "photo")
        // a stays a room photo, b becomes one, c stays none.
        assertEquals(listOf("b" to Attachment.LABEL_ROOM), roomLabelChanges(listOf(a, b, c), setOf("a", "b")))
        // a is unmarked.
        assertEquals(listOf("a" to null), roomLabelChanges(listOf(a, b, c), emptySet()))
        assertEquals(emptyList<Pair<String, String?>>(), roomLabelChanges(listOf(a, b, c), setOf("a")))
    }
}
