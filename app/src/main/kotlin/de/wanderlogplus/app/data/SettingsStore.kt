package de.wanderlogplus.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.wanderlogplus.client.SyncStateStore
import de.wanderlogplus.shared.model.Criteria
import de.wanderlogplus.shared.sync.AssessConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

data class ServerSettings(val url: String, val token: String)

private val Context.serverDataStore by preferencesDataStore(name = "server_settings")

/**
 * Server URL/token live in an app-private DataStore; the sync cursor state ([SyncStateStore]) lives in
 * SharedPreferences so the sync engine can read and write it synchronously.
 */
class SettingsStore(context: Context) : SyncStateStore {
    private val appContext = context.applicationContext ?: context
    private val prefs = appContext.getSharedPreferences("sync_state", Context.MODE_PRIVATE)

    private val urlKey = stringPreferencesKey("server_url")
    private val tokenKey = stringPreferencesKey("server_token")

    val server: Flow<ServerSettings?> = appContext.serverDataStore.data.map { p ->
        val url = p[urlKey]
        val token = p[tokenKey]
        if (url.isNullOrBlank() || token == null) null else ServerSettings(url, token)
    }

    private val tripZoneKey = stringPreferencesKey("trip_zone")

    val tripZone: Flow<TripZone> = appContext.serverDataStore.data.map { p ->
        p[tripZoneKey]?.let { n -> TripZone.entries.firstOrNull { it.name == n } } ?: TripZone.JAPAN
    }

    suspend fun setTripZone(z: TripZone) {
        appContext.serverDataStore.edit { it[tripZoneKey] = z.name }
    }

    private val remindersKey = booleanPreferencesKey("reminders_enabled")

    val remindersEnabled: Flow<Boolean> = appContext.serverDataStore.data.map { it[remindersKey] ?: true }

    suspend fun setRemindersEnabled(v: Boolean) {
        appContext.serverDataStore.edit { it[remindersKey] = v }
    }

    private val aiCriteriaKey = stringPreferencesKey("ai_criteria")
    private val aiExtraKey = stringPreferencesKey("ai_extra_prompt")

    /** Criteria and extra instructions for the assistant's judgements and research (per device). */
    val assessConfig: Flow<AssessConfig> = appContext.serverDataStore.data.map { p -> assessConfigOf(p[aiCriteriaKey], p[aiExtraKey]) }

    suspend fun setAssessConfig(c: AssessConfig) {
        appContext.serverDataStore.edit {
            it[aiCriteriaKey] = c.criteria.joinToString(",")
            it[aiExtraKey] = c.extraPrompt
        }
    }

    suspend fun saveServer(s: ServerSettings) {
        appContext.serverDataStore.edit {
            it[urlKey] = s.url
            it[tokenKey] = s.token
        }
    }

    override var nodeId: String?
        get() = synchronized(prefs) {
            prefs.getString("nodeId", null) ?: newNodeId().also { prefs.edit().putString("nodeId", it).commit() }
        }
        set(v) = putString("nodeId", v)

    override var since: Long
        get() = prefs.getLong("since", 0L)
        set(v) { prefs.edit().putLong("since", v).apply() }

    override var epoch: String?
        get() = prefs.getString("epoch", null)
        set(v) = putString("epoch", v, commit = true)

    override var lastSyncAt: Long?
        get() = if (prefs.contains("lastSyncAt")) prefs.getLong("lastSyncAt", 0L) else null
        set(v) { prefs.edit().apply { if (v == null) remove("lastSyncAt") else putLong("lastSyncAt", v) }.apply() }

    override var lastError: String?
        get() = prefs.getString("lastError", null)
        set(v) = putString("lastError", v)

    override var pendingReset: Boolean
        get() = prefs.getBoolean("pendingReset", false)
        set(v) { prefs.edit().putBoolean("pendingReset", v).commit() }

    private fun putString(key: String, v: String?, commit: Boolean = false) {
        val e = prefs.edit().apply { if (v == null) remove(key) else putString(key, v) }
        if (commit) e.commit() else e.apply()
    }

    private companion object {
        fun newNodeId(): String {
            val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
            return "dev-" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Stored criteria (comma list; null = never set → defaults) and extra prompt; unknown keys are dropped. */
fun assessConfigOf(criteria: String?, extra: String?): AssessConfig = AssessConfig(
    criteria = criteria?.split(',')?.map { it.trim() }?.filter { Criteria.byKey(it) != null }?.distinct() ?: Criteria.DEFAULT,
    extraPrompt = extra.orEmpty(),
)
