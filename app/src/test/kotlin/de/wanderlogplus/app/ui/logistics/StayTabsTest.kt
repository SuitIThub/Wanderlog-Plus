package de.wanderlogplus.app.ui.logistics

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
}
