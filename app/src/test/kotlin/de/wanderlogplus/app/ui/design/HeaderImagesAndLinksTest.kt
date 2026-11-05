package de.wanderlogplus.app.ui.design

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.LinkKind
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.logistics.LogisticsDetailScreen
import de.wanderlogplus.app.ui.places.PlaceDetailScreen
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.CustomLink
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.sync.EntityType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class HeaderImagesAndLinksTest {
    @get:Rule val rule = createComposeRule()

    @After fun reset() { HeaderImages.resolver = { null } }

    private val place = Place("p", name = "Kiyomizu-dera", lat = 35.0, lng = 135.78, imageKeys = listOf("k1", "k2"), website = "https://kiyomizudera.or.jp")
    private val saved = SavedPlace("s", "p", links = listOf(CustomLink("Tickets", "tickets.example.com/kiyo")))
    private val stay = Stay("st", "p", checkIn = "2027-05-18", checkOut = "2027-05-22", headerImage = "att:abc")
    private val snapshot = TripSnapshot(null, emptyList(), mapOf("p" to place), listOf(saved), listOf(stay), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun tap(tag: String) = rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)

    @Test fun headerReferencesResolveWithFallback() {
        assertEquals(placeImageUrl("k1"), SavedPlace("x", "p").heroImage(place))
        assertEquals(placeImageUrl("k2"), SavedPlace("x", "p", headerImage = "wl:k2").heroImage(place))
        // An own photo that is not on this device: the default image.
        assertEquals(placeImageUrl("k1"), stay.heroImage(place))
        HeaderImages.resolver = { if (it == "att:abc") "file:///photos/abc" else null }
        assertEquals("file:///photos/abc", stay.heroImage(place))
        assertNull(SavedPlace("x", "p").heroImage(null))
    }

    @Test fun urlsGetAScheme() {
        assertEquals("https://booking.com/x", normalizeUrl(" booking.com/x "))
        assertEquals("http://a.b", normalizeUrl("http://a.b"))
        assertEquals("booking.com", linkLabel(CustomLink(url = "https://www.booking.com/x")))
    }

    @Test fun placeShowsOwnLinksBesideTheOthersAndEditsThem() {
        val opened = mutableListOf<ExternalLink>()
        var savedLinks: List<CustomLink>? = null
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(
                    snapshot, "s", onBack = null, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                    onOpenLink = { opened += it }, onSetLinks = { _, l -> savedLinks = l },
                )
            }
        }
        rule.onNodeWithTag("link-WEBSITE").assertExists()
        rule.onNodeWithTag("link-custom-0").performClick()
        assertEquals(ExternalLink(LinkKind.CUSTOM, "Tickets", "https://tickets.example.com/kiyo"), opened.single())
        rule.onNodeWithTag("link-edit").performClick()
        tap("link-add")
        rule.onNodeWithTag("link-1-label").performTextInput("Reservierung")
        rule.onNodeWithTag("link-1-url").performTextInput("tablecheck.com/r/1")
        tap("link-save")
        assertEquals(listOf(CustomLink("Tickets", "https://tickets.example.com/kiyo"), CustomLink("Reservierung", "https://tablecheck.com/r/1")), savedLinks)
    }

    @Test fun headerImageIsPickedAndReset() {
        val picks = mutableListOf<String?>()
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(snapshot, "s", onBack = null, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, onSetHeaderImage = { _, ref -> picks += ref })
            }
        }
        rule.onNodeWithTag("hero-image").performClick()
        tap("header-pick-1")
        assertEquals(listOf<String?>("wl:k2"), picks)
    }

    @Test fun stayDetailHasLinksAndHeaderImage() {
        val picks = mutableListOf<String?>()
        var links: List<CustomLink>? = null
        rule.setContent {
            WanderlogPlusTheme {
                LogisticsDetailScreen(
                    snapshot, EntityType.STAY, "st", onBack = {}, onOpenLink = {},
                    onSetHeaderImage = { _, _, ref -> picks += ref }, onSetLinks = { _, _, l -> links = l },
                )
            }
        }
        rule.onNodeWithTag("hero-image").performClick()
        tap("header-default") // the stay has an own image: back to default
        assertEquals(listOf<String?>(null), picks)
        rule.onNodeWithTag("link-edit").performClick()
        rule.onNodeWithTag("link-0-url").performTextInput("booking.com/h")
        tap("link-save")
        assertEquals(listOf(CustomLink("", "https://booking.com/h")), links)
    }
}
