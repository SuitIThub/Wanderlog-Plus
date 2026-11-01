package de.wanderlogplus.client

import kotlinx.serialization.json.JsonNull

fun LocalEditor.keepApp(c: Conflict) =
    set(c.type, c.id, mapOf("_conflict.${c.field}" to JsonNull))

fun LocalEditor.takeWanderlog(c: Conflict) =
    set(c.type, c.id, mapOf(c.field to c.wanderlogValue, "_conflict.${c.field}" to JsonNull))
