package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.shared.model.Amenity
import de.wanderlogplus.shared.model.AmenityGroup
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.PropertyInfo
import de.wanderlogplus.shared.model.RoomInfo
import de.wanderlogplus.shared.model.StayInfo
import de.wanderlogplus.shared.sync.EntityType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StayTabsTest {
    @get:Rule val rule = createComposeRule()

    private val info = StayInfo(
        room = RoomInfo(type = "Japanisches Zimmer", beds = "2 Futons", bath = "eigenes Rotenburo", amenities = listOf(AmenityGroup("Bad", listOf(Amenity("Rotenburo", "privat"))))),
        property = PropertyInfo(meals = "Kaiseki 18:00", amenities = listOf(AmenityGroup("Service", listOf(Amenity("Wäscherei"), Amenity("Rotenburo", "privat"))))),
        sources = listOf("https://ryokan.example/rooms"),
    )

    @Test
    fun checkInAndOutShowTheirTimes() {
        assertEquals(dayLabel("2027-05-14") + " ab 15:00", stayTimeText("2027-05-14", "15:00", checkIn = true))
        assertEquals(dayLabel("2027-05-16") + " bis 10:00", stayTimeText("2027-05-16", "10:00", checkIn = false))
        assertEquals(dayLabel("2027-05-16"), stayTimeText("2027-05-16", null, checkIn = false))
        assertEquals("–", stayTimeText("", "10:00", checkIn = false))
    }

    @Test
    fun stayPageHasTabsPhotosUpTopAndFoldedSources() {
        val base = Fixtures.trip()
        val stay = base.stays.first().copy(info = info, checkInTime = "15:00", checkOutTime = "10:00")
        val photo = Attachment("ph1", EntityType.STAY, stay.id, "abc", "zimmer.jpg", "image/jpeg", 10, kind = "photo", label = Attachment.LABEL_ROOM)
        val snapshot = base.copy(stays = listOf(stay) + base.stays.drop(1), attachments = listOf(photo))
        var opened: Int? = null
        rule.setContent {
            WanderlogPlusTheme { LogisticsDetailScreen(snapshot, EntityType.STAY, stay.id, onBack = {}, onOpenPhoto = { opened = it }) }
        }
        rule.onNodeWithText(stayTimeText(stay.checkIn, "15:00", checkIn = true)).assertExists()
        rule.onNodeWithTag("stay-photo-strip").assertExists()
        rule.onNodeWithTag("stay-photo-strip-0").performClick()
        assertEquals(0, opened)
        rule.onNodeWithText("Japanisches Zimmer").assertDoesNotExist()
        rule.onNodeWithTag("stay-tab-room").performClick()
        rule.onNodeWithText("Auf einen Blick").assertExists()
        rule.onNodeWithText("Wäscherei").assertExists()
        rule.onNodeWithTag("amenity-panel-room-BATH").assertExists()
        rule.onNodeWithText("ryokan.example").assertDoesNotExist()
        rule.onNodeWithTag("stay-sources").performScrollTo().performClick()
        rule.onNodeWithText("ryokan.example").assertExists()
        rule.onNodeWithTag("stay-tab-photos").performClick()
        rule.onNodeWithTag("photo-gallery").assertExists()
    }

    @Test
    fun photosOpenDirectlyAndRoomPhotosArePickedTogether() {
        val base = Fixtures.trip()
        val stay = base.stays.first().copy(info = info)
        val p0 = Attachment("ph1", EntityType.STAY, stay.id, "abc", "zimmer.jpg", "image/jpeg", 10, kind = "photo", label = Attachment.LABEL_ROOM)
        val p1 = Attachment("ph2", EntityType.STAY, stay.id, "def", "lobby.jpg", "image/jpeg", 10, kind = "photo")
        val p2 = Attachment("ph3", EntityType.STAY, stay.id, "ghi", "bad.jpg", "image/jpeg", 10, kind = "photo")
        val snapshot = base.copy(stays = listOf(stay) + base.stays.drop(1), attachments = listOf(p0, p1, p2))
        var opened: Int? = null
        val labels = mutableListOf<Pair<String, String?>>()
        val actions = de.wanderlogplus.app.ui.attachments.AttachmentActions({ true }, { _, _ -> }, {}, {}, {})
        rule.setContent {
            WanderlogPlusTheme {
                LogisticsDetailScreen(
                    snapshot, EntityType.STAY, stay.id, onBack = {}, attachments = actions,
                    onOpenPhoto = { opened = it }, onSetPhotoLabel = { id, l -> labels += id to l },
                )
            }
        }
        rule.onNodeWithTag("stay-tab-photos").performClick()
        // A tap only opens the photo.
        rule.onNodeWithTag("photo-1").performScrollTo().performClick()
        assertEquals(1, opened)
        rule.onNodeWithTag("photo-room-toggle").assertDoesNotExist()
        // "Zimmerfotos wählen": tick the lobby, untick the old room photo, save.
        rule.onNodeWithTag("room-photos-pick").performScrollTo().performClick()
        rule.onNodeWithTag("photo-0").performScrollTo().performClick()
        rule.onNodeWithTag("photo-2").performScrollTo().performClick()
        rule.onNodeWithTag("room-photos-done").performScrollTo().performClick()
        assertEquals(listOf("ph1" to null, "ph3" to Attachment.LABEL_ROOM), labels)
        assertEquals(1, opened)
        rule.onNodeWithTag("room-photos-done").assertDoesNotExist()
    }

    @Test
    fun roomPhotosInTheRoomCardOpen() {
        val base = Fixtures.trip()
        val stay = base.stays.first().copy(info = info)
        val p0 = Attachment("ph1", EntityType.STAY, stay.id, "abc", "zimmer.jpg", "image/jpeg", 10, kind = "photo")
        val p1 = Attachment("ph2", EntityType.STAY, stay.id, "def", "zimmer2.jpg", "image/jpeg", 10, kind = "photo", label = Attachment.LABEL_ROOM)
        val snapshot = base.copy(stays = listOf(stay) + base.stays.drop(1), attachments = listOf(p0, p1))
        var opened: Int? = null
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(snapshot, EntityType.STAY, stay.id, onBack = {}, onOpenPhoto = { opened = it }) } }
        rule.onNodeWithTag("stay-tab-room").performClick()
        rule.onNodeWithTag("stay-room").performScrollTo()
        rule.onNodeWithTag("photo-0", useUnmergedTree = true).performClick()
        // The index is the stay gallery's (the room photo is the second photo there).
        assertEquals(1, opened)
    }

    @Test
    fun cancellationTextIsGerman() {
        assertEquals("Kostenlos stornierbar bis Fr, 07.05., 23:59", cancellationText("2027-05-07T23:59", "2027-05-01"))
        assertEquals("Kostenlos stornierbar bis Fr, 07.05.", cancellationText("2027-05-07", "2027-05-01"))
        assertEquals("Stornofrist abgelaufen (Fr, 07.05., 23:59)", cancellationText("2027-05-07T23:59", "2027-05-08"))
        assertEquals("Kostenlos stornierbar bis Fr, 07.05., 23:59", cancellationText("2027-05-07T23:59", null))
        // As imported from Wanderlog: a space instead of "T".
        assertEquals("Kostenlos stornierbar bis Fr, 07.05., 23:59", cancellationText("2027-05-07 23:59", "2027-05-01"))
        assertEquals("Stornofrist abgelaufen (Fr, 07.05., 23:59)", cancellationText("2027-05-07 23:59", "2027-05-20"))
    }

    @Test
    fun stayOverviewIsCompactAndKeepsBookingDataFolded() {
        val base = Fixtures.trip()
        val stay = base.stays.first().copy(
            checkInTime = "15:00", checkOutTime = "10:00", confirmationNumber = "2131509258", travelerNames = listOf("Anna Beispiel"),
            cancellationDeadline = "2027-03-30T23:59", cancellationPolicy = "Free cancellation until …",
        )
        val snapshot = base.copy(stays = listOf(stay) + base.stays.drop(1))
        val name = snapshot.places.getValue(stay.placeId).name
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(snapshot, EntityType.STAY, stay.id, onBack = {}, today = "2027-03-20") } }
        // The name stands once (in the hero), check-in and check-out side by side.
        rule.onAllNodes(androidx.compose.ui.test.hasText(name)).assertCountEquals(1)
        rule.onNodeWithTag("stay-checkin").assertExists()
        rule.onNodeWithTag("stay-checkout").assertExists()
        rule.onNodeWithText("Kostenlos stornierbar bis Di, 30.03., 23:59").assertExists()
        // Booking number, travellers and conditions only after "Buchung".
        rule.onNodeWithText("2131509258").assertDoesNotExist()
        rule.onNodeWithText("Anna Beispiel").assertDoesNotExist()
        rule.onNodeWithTag("stay-booking").performScrollTo().performClick()
        rule.onNodeWithText("2131509258").assertExists()
        rule.onNodeWithText("Anna Beispiel").assertExists()
        rule.onNodeWithText("Free cancellation until …").assertExists()
    }
}
