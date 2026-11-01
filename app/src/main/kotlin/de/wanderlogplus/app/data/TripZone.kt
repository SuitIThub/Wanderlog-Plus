package de.wanderlogplus.app.data

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/** Time zone used for "now" on the Heute tab. All trip times are Japanese local time, so JST is the default. */
enum class TripZone(val zoneId: ZoneId?) {
    JAPAN(ZoneId.of("Asia/Tokyo")),
    DEVICE(null),
}

/** DEVICE uses the zone of [clock] (the device zone for the system clock). */
fun TripZone.now(clock: Clock): LocalDateTime = LocalDateTime.now(clock.withZone(zoneId ?: clock.zone))

fun TripZone.now(): LocalDateTime = now(Clock.systemDefaultZone())
