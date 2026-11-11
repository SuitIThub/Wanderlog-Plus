package de.wanderlogplus.app.links

import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import de.wanderlogplus.shared.model.englishUrl
import java.util.Locale

class ExternalLinksTest {
    private val base = Place("p", googlePlaceId = "ChIJ123", name = "Schrein", lat = 34.9671, lng = 135.7727)
    private fun uri(p: Place, kind: LinkKind, cat: PlaceCategory? = null) =
        GoogleExternalLinks.linksFor(p, cat).firstOrNull { it.kind == kind }?.uri

    @Test fun navigateUsesCoordsAndPlaceId() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals(
                "https://www.google.com/maps/dir/?api=1&destination=34.9671,135.7727&destination_place_id=ChIJ123",
                uri(base, LinkKind.NAVIGATE),
            )
        } finally {
            Locale.setDefault(saved)
        }
        assertEquals("Navigation", GoogleExternalLinks.linksFor(base, null).first { it.kind == LinkKind.NAVIGATE }.label)
    }

    @Test fun navigateWithoutPlaceIdOmitsParam() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=34.9671,135.7727",
            uri(base.copy(googlePlaceId = ""), LinkKind.NAVIGATE),
        )
    }

    @Test fun transitAddsTravelmode() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=34.9671,135.7727&destination_place_id=ChIJ123&travelmode=transit",
            uri(base, LinkKind.TRANSIT),
        )
    }

    @Test fun websiteOnlyHttp() {
        assertEquals("https://x.jp", uri(base.copy(website = "https://x.jp"), LinkKind.WEBSITE))
        assertEquals("http://x.jp", uri(base.copy(website = "http://x.jp"), LinkKind.WEBSITE))
        assertNull(uri(base.copy(website = "javascript:x"), LinkKind.WEBSITE))
        assertNull(uri(base, LinkKind.WEBSITE))
    }

    @Test fun phoneSanitized() {
        assertEquals("tel:+81756417331", uri(base.copy(phone = "+81 75-641-7331"), LinkKind.PHONE))
        assertNull(uri(base.copy(phone = "12"), LinkKind.PHONE))
        assertNull(uri(base.copy(phone = "ab"), LinkKind.PHONE))
    }

    @Test fun tabelogOnlyForFood() {
        assertNull(uri(base, LinkKind.TABELOG, PlaceCategory.SIGHT))
        assertNull(uri(base, LinkKind.TABELOG, null))
        assertEquals("https://tabelog.com/rstLst/?vs=1&sw=Schrein", uri(base, LinkKind.TABELOG, PlaceCategory.FOOD))
    }

    @Test fun tabelogEncodesName() {
        assertEquals(
            "https://tabelog.com/rstLst/?vs=1&sw=%E4%B8%80%E8%98%AD%20%E4%BA%AC%E9%83%BD%E6%B2%B3%E5%8E%9F%E7%94%BA%E5%BA%97",
            uri(base.copy(name = "一蘭 京都河原町店"), LinkKind.TABELOG, PlaceCategory.FOOD),
        )
    }

    @Test fun zeroCoordsFallBackToName() {
        val p = Place("p", name = "Café Alpha", address = "Gion 1", googlePlaceId = "ChIJ9")
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=Caf%C3%A9%20Alpha%20Gion%201&destination_place_id=ChIJ9",
            uri(p, LinkKind.NAVIGATE),
        )
        assertNull(uri(Place("q"), LinkKind.NAVIGATE))
    }

    @Test fun tabelogGoesStraightToTheEntryWhenKnown() {
        val found = base.copy(tabelog = de.wanderlogplus.shared.model.TabelogInfo(de.wanderlogplus.shared.model.TabelogStatus.FOUND, "https://tabelog.com/kyoto/A2601/A260201/26032368/"))
        assertEquals("https://tabelog.com/en/kyoto/A2601/A260201/26032368/", uri(found, LinkKind.TABELOG, PlaceCategory.FOOD))
        val missing = base.copy(tabelog = de.wanderlogplus.shared.model.TabelogInfo(de.wanderlogplus.shared.model.TabelogStatus.NOT_FOUND))
        assertEquals(true, uri(missing, LinkKind.TABELOG, PlaceCategory.FOOD)!!.startsWith("https://tabelog.com/rstLst/"))
        assertEquals(true, uri(found, LinkKind.TABELOG, PlaceCategory.NIGHTLIFE) != null)
    }

    @Test fun tabelogTargetsPreferTheMultilingualApp() {
        val info = de.wanderlogplus.shared.model.TabelogInfo(de.wanderlogplus.shared.model.TabelogStatus.FOUND, "https://tabelog.com/kyoto/A2601/A260201/26032368/")
        val en = "https://tabelog.com/en/kyoto/A2601/A260201/26032368/"
        assertEquals(listOf(TabelogTarget.AppUrl(en), TabelogTarget.AppStart, TabelogTarget.Browser(en)), tabelogTargets(info.englishUrl(), installed = true))
        assertEquals(listOf(TabelogTarget.Browser(en)), tabelogTargets(info.englishUrl(), installed = false))
        assertEquals(emptyList<TabelogTarget>(), tabelogTargets("", installed = true))
    }
}
