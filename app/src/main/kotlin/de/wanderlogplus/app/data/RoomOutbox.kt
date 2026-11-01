package de.wanderlogplus.app.data

import de.wanderlogplus.client.Outbox
import de.wanderlogplus.client.OutboxEntry
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange

class RoomOutbox(private val db: AppDatabase) : Outbox {
    private val dao get() = db.dao()

    override fun add(changes: List<FieldChange>) {
        if (changes.isEmpty()) return
        dao.insert(changes.map { OutboxEntity(type = it.type.name, id = it.id, field = it.field, value = encodeJson(it.value), hlc = it.hlc) })
    }

    override fun peek(limit: Int): List<OutboxEntry> =
        dao.peek(limit).map {
            OutboxEntry(it.seq, FieldChange(EntityType.valueOf(it.type), it.id, it.field, decodeJson(it.value), it.hlc))
        }

    override fun remove(seqs: Collection<Long>) {
        // Chunked to stay below the SQLite bound-variable limit.
        seqs.chunked(500).forEach { dao.delete(it) }
    }

    override fun size(): Int = dao.count()
}
