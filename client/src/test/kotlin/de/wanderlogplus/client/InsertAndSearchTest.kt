package de.wanderlogplus.client

import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Ids
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.sync.EntityType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InsertPositionTest {
    private val date = "2027-05-10"

    private fun fx() = EditorFixture().also {
        it.seedEntity(EntityType.TRIP, "t1", Trip("t1", "Reise", "2027-05-08", "2027-05-29"))
        it.seedEntity(EntityType.REGION, "r1", Region("r1", "Kiso"))
        it.seedEntity(EntityType.STAY, "st", Stay("st", "p-h", "r1", "2027-05-09", "2027-05-12"))
    }

    private fun order(f: EditorFixture) = f.snapshot().let { s -> s.dayItemsFor(s.days.single().id).map { it.text } }

    @Test fun notesGoToStartEndAndBetween() {
        val f = fx()
        f.editor.addNoteToDay(f.snapshot(), date, "B")
        val b = f.snapshot().dayItems.single().id
        f.editor.addNoteToDay(f.snapshot(), date, "D")
        f.editor.addNoteToDay(f.snapshot(), date, "A", ItemPosition.Start)
        f.editor.addNoteToDay(f.snapshot(), date, "C", ItemPosition.After(b))
        assertEquals(listOf("A", "B", "C", "D"), order(f))
    }

    @Test fun equalKeysAreReKeyedBeforeInserting() {
        val f = fx()
        f.editor.addNoteToDay(f.snapshot(), date, "X")
        val dayId = f.snapshot().days.single().id
        // Two items with the same key, as after concurrent adds on two phones.
        f.seed(EntityType.DAY_ITEM, "y", mapOf("dayId" to JsonPrimitive(dayId), "kind" to JsonPrimitive("NOTE"), "text" to JsonPrimitive("Y"), "sortKey" to JsonPrimitive(f.snapshot().dayItems.first().sortKey), "deleted" to JsonPrimitive(false)))
        val first = f.snapshot().dayItemsFor(dayId).first()
        f.editor.addNoteToDay(f.snapshot(), date, "M", ItemPosition.After(first.id))
        val items = f.snapshot().dayItemsFor(dayId)
        assertEquals("M", items[1].text)
        assertEquals(3, items.map { it.sortKey }.distinct().size)
    }

    @Test fun unknownAfterIdAppends() {
        val f = fx()
        f.editor.addNoteToDay(f.snapshot(), date, "A")
        f.editor.addNoteToDay(f.snapshot(), date, "Z", ItemPosition.After("gone"))
        assertEquals(listOf("A", "Z"), order(f))
    }

    @Test fun searchedPlaceBecomesPlaceSavedPlaceAndItem() {
        val f = fx()
        f.editor.addNoteToDay(f.snapshot(), date, "Start")
        val itemId = f.editor.addPlaceToDay(f.snapshot(), date, "Tsumago-juku", 35.5768, 137.5960, "Nagiso, Nagano", PlaceCategory.SIGHT, ItemPosition.Start)
        val s = f.snapshot()
        val item = s.dayItems.single { it.id == itemId }
        assertEquals(DayItemKind.PLACE, item.kind)
        val place = s.places[item.placeId]!!
        assertEquals("Tsumago-juku", place.name)
        assertEquals(35.5768, place.lat, 1e-9)
        val saved = s.savedPlaces.single { it.placeId == place.id }
        assertEquals("r1", saved.regionId)
        assertEquals(PlaceStatus.PLANNED, saved.status)
        assertEquals(PlaceCategory.SIGHT, saved.category)
        assertEquals(itemId, s.dayItemsFor(item.dayId).first().id)
        assertEquals(Ids.dayId("t1", date), item.dayId)
        assertEquals(1, f.changes - 1) // one notification for the whole insert (the note was the first)
    }

    @Test fun positionsEncodeAndDecode() {
        listOf(ItemPosition.Start, ItemPosition.End, ItemPosition.After("a:b")).forEach {
            assertEquals(it, ItemPosition.decode(it.encode()))
        }
        assertEquals(ItemPosition.End, ItemPosition.decode(null))
        assertEquals(ItemPosition.End, ItemPosition.decode("after:"))
    }
}

class GeoSearchAreaTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val hit = """{"features":[{"geometry":{"type":"Point","coordinates":[137.596,35.5768]},
        "properties":{"name":"Tsumago","street":"Nakasendo","city":"Nagiso","country":"Japan","osm_key":"tourism","osm_value":"attraction"}}]}"""

    @Test fun searchSendsBbox() = runTest {
        var seen: HttpRequestData? = null
        val g = GeoSearch(HttpClient(MockEngine { r -> seen = r; respond(hit, HttpStatusCode.OK, jsonHeaders) }))
        val r = g.search("Tsumago", bbox = BBox(137.5, 35.5, 137.7, 35.6))
        assertEquals("137.500000,35.500000,137.700000,35.600000", seen!!.url.parameters["bbox"])
        assertEquals(PlaceCategory.SIGHT, r.single().category)
        assertEquals("Nakasendo, Nagiso, Japan", r.single().detail)
    }

    @Test fun reverseUsesReverseEndpoint() = runTest {
        var seen: HttpRequestData? = null
        val g = GeoSearch(HttpClient(MockEngine { r -> seen = r; respond(hit, HttpStatusCode.OK, jsonHeaders) }))
        assertEquals("Tsumago", g.reverse(35.57, 137.59)?.name)
        assertEquals("/reverse", seen!!.url.encodedPath)
        val empty = GeoSearch(HttpClient(MockEngine { respond("""{"features":[]}""", HttpStatusCode.OK, jsonHeaders) }))
        assertNull(empty.reverse(0.0, 0.0))
    }

    @Test fun categoriesFromOsmTags() {
        fun c(k: String?, v: String?) = GeoPlace("x", "", 0.0, 0.0, k, v).category
        assertEquals(PlaceCategory.FOOD, c("amenity", "restaurant"))
        assertEquals(PlaceCategory.NIGHTLIFE, c("amenity", "bar"))
        assertEquals(PlaceCategory.SHOPPING, c("shop", "convenience"))
        assertEquals(PlaceCategory.SIGHT, c("historic", "castle"))
        assertEquals(PlaceCategory.OTHER, c("railway", "station"))
        assertEquals(PlaceCategory.OTHER, c(null, null))
    }

    @Test fun bboxContains() {
        val b = BBox(137.5, 35.5, 137.7, 35.6)
        assertEquals(true, b.contains(35.55, 137.6))
        assertEquals(false, b.contains(35.7, 137.6))
    }
}
