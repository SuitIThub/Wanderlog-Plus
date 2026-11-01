package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 1
const val PROTOCOL_HEADER = "X-Protocol-Version"

/** Body of `POST /api/sync/push`. */
@Serializable
data class PushRequest(val changes: List<FieldChange>)

/**
 * [accepted] counts changes that were newer than the stored state; [serverSeq] is the server's sequence after the push;
 * [maxHlc] is the highest HLC written by this push (including server-generated writes), null if nothing was written;
 * [epoch] identifies the server database (a changed epoch means local cursors are invalid).
 */
@Serializable
data class PushResponse(val accepted: Int, val serverSeq: Long, val maxHlc: String?, val epoch: String)

/** One page of changed records; continue pulling from [nextSeq] while [hasMore]. */
@Serializable
data class PullResponse(val records: List<EntityRecord>, val nextSeq: Long, val hasMore: Boolean, val epoch: String)

@Serializable
data class ErrorResponse(val error: String, val message: String? = null, val serverProtocol: Int? = null)
