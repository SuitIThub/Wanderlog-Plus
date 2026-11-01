package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.Serializable

/** Erweiterungspunkt für Live-Status (Verspätungen etc.). V1: keine Route, immer leer. */
@Serializable
data class LiveStatus(val summary: String, val delayMinutes: Int? = null, val updatedAt: String)

interface LiveStatusProvider {
    fun statusFor(type: EntityType, id: String): LiveStatus?
}

object NoLiveStatus : LiveStatusProvider {
    override fun statusFor(type: EntityType, id: String): LiveStatus? = null
}
