package de.wanderlogplus.app.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.testing.recordScreenshot
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.regionColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DesignGalleryScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun gallery(dark: Boolean) {
        rule.setContent { WanderlogPlusTheme(darkTheme = dark) { DesignGallery() } }
        rule.onRoot().recordScreenshot(if (dark) "design-gallery-dark" else "design-gallery-light")
    }

    @Test fun galleryLight() = gallery(dark = false)

    @Test fun galleryDark() = gallery(dark = true)

    @Test fun fallbacksWithoutImages() {
        rule.setContent {
            WanderlogPlusTheme {
                Column {
                    HeroHeader(title = "Tag", kicker = "Tag 1", imageUrl = null, accent = regionColor("#C0603A"), onBack = {}, onEdit = {})
                    PhotoCard(title = "Ort", subtitle = null, imageUrl = null, placeholderSymbol = "⛩", accent = regionColor("#2E86AB"), onClick = {})
                }
            }
        }
        rule.onNodeWithTag("hero", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("hero-fallback", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("hero-back", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("hero-edit", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("photo-fallback", useUnmergedTree = true).assertExists()
    }

    @Test fun editSheetShowsTitleAndContent() {
        rule.setContent {
            WanderlogPlusTheme { EditSheet(title = "Tag bearbeiten", onDismiss = {}) { Text("Inhalt") } }
        }
        rule.onNodeWithTag("edit-sheet").assertExists()
        rule.onNodeWithText("Tag bearbeiten").assertExists()
        rule.onNodeWithText("Inhalt").assertExists()
    }

    @Test fun actionCircleIsOneButtonWithItsLabel() {
        var clicks = 0
        rule.setContent { WanderlogPlusTheme { ActionCircle(Icons.Filled.Navigation, "Navigation", { clicks++ }, "act-nav") } }
        rule.onNodeWithTag("act-nav").assertHasClickAction().assertTextEquals("Navigation")
        // The icon carries no description of its own, so the label is not announced twice.
        rule.onAllNodesWithContentDescription("Navigation", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("act-nav").performClick()
        assertEquals(1, clicks)
    }

    @Test fun heroButtonsHave48dpTouchTargets() {
        rule.setContent {
            WanderlogPlusTheme { HeroHeader(title = "Tag", kicker = null, imageUrl = null, accent = regionColor("#C0603A"), onBack = {}, onEdit = {}) }
        }
        rule.onNodeWithTag("hero-back").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("hero-edit").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun fallbackGradientIsDarkEnoughForWhiteText() {
        // Light accents (e.g. the dark-theme primary, a pale peach) must still give white text >= 4.5:1 contrast.
        listOf(Color(0xFFFFB68B), Color(0xFFFFFFFF), Color(0xFFF5D76E), regionColor("#3498db"), regionColor("#C0603A")).forEach { accent ->
            heroGradientColors(accent).forEach { c ->
                val contrast = 1.05 / (c.luminance() + 0.05)
                assertTrue("contrast $contrast for $c from $accent", contrast >= 4.5)
            }
        }
    }
}

@Composable
private fun DesignGallery() {
    val berg = regionColor("#C0603A")
    val kueste = regionColor("#2E86AB")
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
        HeroHeader(
            title = "Tempel und Gärten",
            kicker = "Fr, 02.04. · Tag 2",
            imageUrl = null,
            accent = berg,
            onBack = {},
            onEdit = {},
            chips = listOf("Bergstadt", "3 geplant"),
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoCard(
                title = "Gasthaus am Hang mit einem ausgesprochen langen Namen, der sicher umbricht und abgeschnitten wird",
                subtitle = "01.04. – 04.04. · 3 Nächte",
                imageUrl = null,
                placeholderSymbol = "🏨",
                accent = berg,
                badges = listOf(BadgeSpec("Storno bis 20.03.", BadgeTone.Warning)),
                onClick = {},
            )
            PhotoCard(
                title = "Fischmarkt",
                subtitle = "Essen · ★ 4,3",
                imageUrl = null,
                placeholderSymbol = "🍜",
                accent = kueste,
                style = PhotoCardStyle.Large,
                onClick = {},
            )
            SectionLabel("Tagesablauf", color = berg)
            Timeline(accent = berg) {
                item("09:00") {
                    Text("Alter Bergtempel", style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(BadgeSpec("offen", BadgeTone.Positive))
                        Badge(BadgeSpec("🎫 Ticket", BadgeTone.Accent))
                        Badge(BadgeSpec("nicht offline", BadgeTone.Warning))
                        Badge(BadgeSpec("Idee", BadgeTone.Neutral))
                    }
                }
                item("12:30") { Text("Nudelküche Weizenhalm", style = MaterialTheme.typography.bodyLarge) }
                item("Nacht") { EmptyHint("Noch keine Unterkunft") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionCircle(Icons.Filled.Navigation, "Navigation", {}, "act-nav")
                ActionCircle(Icons.Filled.DirectionsTransit, "ÖPNV", {}, "act-transit")
                ActionCircle(Icons.Filled.Public, "Website", {}, "act-web")
                ActionCircle(Icons.Filled.Call, "Anruf", {}, "act-call")
            }
        }
        }
    }
}
