package de.wanderlogplus.client

import de.wanderlogplus.shared.sync.FieldChange

data class OutboxEntry(val seq: Long, val change: FieldChange)

interface Outbox {
    fun add(changes: List<FieldChange>)
    fun peek(limit: Int): List<OutboxEntry>
    fun remove(seqs: Collection<Long>)
    fun size(): Int
}

interface SyncStateStore {
    var nodeId: String?
    var since: Long
    var epoch: String?
    var lastSyncAt: Long?
    var lastError: String?

    /** Set by [SyncEngine.resetLocalCursor]; consumed by the next sync. */
    var pendingReset: Boolean
}

class InMemoryOutbox : Outbox {
    private val entries = java.util.TreeMap<Long, FieldChange>()
    private var next = 1L

    @Synchronized override fun add(changes: List<FieldChange>) {
        for (c in changes) entries[next++] = c
    }

    @Synchronized override fun peek(limit: Int): List<OutboxEntry> =
        entries.entries.take(limit).map { OutboxEntry(it.key, it.value) }

    @Synchronized override fun remove(seqs: Collection<Long>) {
        seqs.forEach { entries.remove(it) }
    }

    @Synchronized override fun size(): Int = entries.size
}

class InMemorySyncState : SyncStateStore {
    override var nodeId: String? = null
    override var since: Long = 0
    override var epoch: String? = null
    override var lastSyncAt: Long? = null
    override var lastError: String? = null
    override var pendingReset: Boolean = false
}
