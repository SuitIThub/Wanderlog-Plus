package de.wanderlogplus.app.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import de.wanderlogplus.shared.sync.ChatLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** The device's position for "Standort mitsenden"; null when unknown (no permission, location off, timeout). */
fun interface ChatLocator {
    suspend fun locate(): ChatLocation?
}

/** True when the app may read the position (precise or approximate). */
fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Asks the system's LocationManager for a current fix (fused provider where available, else network, else GPS);
 * a fix from the last [MAX_AGE_MILLIS] is used when no new one comes within [TIMEOUT_MILLIS].
 */
class AndroidChatLocator(private val context: Context) : ChatLocator {
    private val executor = Executors.newSingleThreadExecutor()

    @SuppressLint("MissingPermission") // checked by hasLocationPermission
    override suspend fun locate(): ChatLocation? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val provider = providers.firstOrNull() ?: return null
        val fresh = withTimeoutOrNull(TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(lm, provider, signal, executor) { loc -> if (cont.isActive) cont.resume(loc) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        val loc = fresh ?: providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < MAX_AGE_MILLIS * 1_000_000 }
            .maxByOrNull { it.elapsedRealtimeNanos }
        return loc?.let { ChatLocation(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toInt() else null) }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 8_000L
        const val MAX_AGE_MILLIS = 10 * 60_000L
    }
}
