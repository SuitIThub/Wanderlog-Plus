package de.wanderlogplus.app.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * [clock] truncated to minutes, refreshed on every ON_RESUME and at each full minute. Equal values do not
 * invalidate readers, so normal recompositions never recompute anything keyed on the result.
 */
@Composable
fun rememberMinuteNow(clock: () -> LocalDateTime): LocalDateTime {
    val currentClock by rememberUpdatedState(clock)
    var now by remember { mutableStateOf(clock().truncatedTo(ChronoUnit.MINUTES)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = currentClock().truncatedTo(ChronoUnit.MINUTES) }
    LaunchedEffect(Unit) {
        while (true) {
            val t = currentClock()
            delay((60_000L - t.second * 1000L - t.nano / 1_000_000).coerceAtLeast(1L))
            now = currentClock().truncatedTo(ChronoUnit.MINUTES)
        }
    }
    return now
}
