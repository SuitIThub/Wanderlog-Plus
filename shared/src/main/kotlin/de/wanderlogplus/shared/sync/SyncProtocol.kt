package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 1
const val PROTOCOL_HEADER = "X-Protocol-Version"

/** Body of `POST /api/sync/push`. */
@Serializable
data class PushRequest(val changes: List<FieldChange>)

/** [accepted] counts changes that were newer than the stored state; [serverSeq] is the server's sequence after the push. */
@Serializable
data class PushResponse(val accepted: Int, val serverSeq: Long)

/** One page of changed records; continue pulling from [nextSeq] while [hasMore]. */
@Serializable
data class PullResponse(val records: List<EntityRecord>, val nextSeq: Long, val hasMore: Boolean)

@Serializable
data class ErrorResponse(val error: String, val message: String? = null, val serverProtocol: Int? = null)
