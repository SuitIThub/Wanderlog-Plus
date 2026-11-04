package de.wanderlogplus.app.weather

import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.TripWeather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The trip weather from the sync server, kept in [file] so the last forecast stays visible offline. [refresh] asks
 * the server only when the stored one is older than [MAX_AGE_MILLIS] (the server caches Open-Meteo for 3 hours).
 */
class WeatherRepository(
    private val api: suspend () -> SyncApi?,
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _weather = MutableStateFlow<TripWeather?>(null)
    val weather: StateFlow<TripWeather?> = _weather

    private var loaded = false
    private var askedAt: Long? = null

    private fun load() {
        if (loaded) return
        loaded = true
        _weather.value = runCatching { json.decodeFromString(TripWeather.serializer(), file.readText()) }.getOrNull()
    }

    /** Loads the stored weather and asks the server when it is old (or [force]). Errors keep the stored one. */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            load()
            val t = now()
            val fresh = _weather.value?.let { t - it.fetchedAt < MAX_AGE_MILLIS } == true
            if (!force && (fresh || askedAt?.let { t - it < RETRY_MILLIS } == true)) return@withLock
            askedAt = t
            val got = try {
                api()?.weather()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@withLock
            _weather.value = got.copy(fetchedAt = if (got.fetchedAt > 0) got.fetchedAt else t)
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(TripWeather.serializer(), _weather.value!!))
            }
        }
    }

    companion object {
        const val MAX_AGE_MILLIS = 3 * 60 * 60 * 1000L

        /** After a failed or empty try, wait this long before asking again (unless forced). */
        const val RETRY_MILLIS = 10 * 60 * 1000L
    }
}
