package de.wanderlogplus.importer

import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.applyChanges

object ImportRunner {
    /** parse -> map -> merge -> apply. Throws before any store change if parsing or mapping fails. */
    fun run(json: String, store: EntityStore, clock: HlcClock): ImportResult {
        val snapshot = WanderlogMapper.map(WanderlogParser.parse(json))
        val result = ReimportMerger(clock).merge(snapshot, store)
        store.applyChanges(result.changes)
        return result
    }
}
