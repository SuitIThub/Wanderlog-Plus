package de.wanderlogplus.client

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class EditorFixture {
    val store = InMemoryEntityStore()
    val outbox = InMemoryOutbox()
    val clock = HlcClock("dev-a", now = { 1000L })
    var changes = 0
    val editor = LocalEditor(store, outbox, clock, onChange = { changes++ })

    /** Seeds a record as if it came from the server (older HLC than the editor's). */
    fun seed(type: EntityType, id: String, fields: Map<String, JsonElement>) {
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    inline fun <reified T> seedEntity(type: EntityType, id: String, entity: T, extra: Map<String, JsonElement> = emptyMap()) =
        seed(type, id, EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false)) + extra)

    fun snapshot() = TripSnapshot.from(store.all())
}
