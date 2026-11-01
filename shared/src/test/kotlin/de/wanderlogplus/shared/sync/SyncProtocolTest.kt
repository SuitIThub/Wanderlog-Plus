package de.wanderlogplus.shared.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncProtocolTest {
    private val json = Json

    @Test fun roundTrip() {
        val push = PushRequest(listOf(FieldChange(EntityType.PLACE, "p1", "notes", JsonPrimitive("ü ⚠️"), "000000000000001-000000-dev")))
        assertEquals(push, json.decodeFromString<PushRequest>(json.encodeToString(push)))

        val pull = PullResponse(
            records = listOf(
                EntityRecord(
                    EntityType.PLACE, "p1",
                    mapOf(
                        "name" to FieldValue(JsonPrimitive("Café"), "000000000000001-000000-dev"),
                        "notes" to FieldValue(JsonPrimitive("x"), "000000000000002-000000-dev"),
                    ),
                ),
            ),
            nextSeq = 42,
            hasMore = true,
            epoch = "e1",
        )
        assertEquals(pull, json.decodeFromString<PullResponse>(json.encodeToString(pull)))
    }

    @Test fun errorResponseOmitsNulls() {
        val out = Json { explicitNulls = false }.encodeToString(ErrorResponse("protocol_mismatch", serverProtocol = 1))
        assertEquals("""{"error":"protocol_mismatch","serverProtocol":1}""", out)
    }
}
