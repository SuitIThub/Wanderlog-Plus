package de.wanderlogplus.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.wanderlogplus.client.SyncStateStore
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
        set(v) = putString("epoch", v)

    override var lastSyncAt: Long?
        get() = if (prefs.contains("lastSyncAt")) prefs.getLong("lastSyncAt", 0L) else null
        set(v) { prefs.edit().apply { if (v == null) remove("lastSyncAt") else putLong("lastSyncAt", v) }.apply() }

    override var lastError: String?
        get() = prefs.getString("lastError", null)
        set(v) = putString("lastError", v)

    override var pendingReset: Boolean
        get() = prefs.getBoolean("pendingReset", false)
        set(v) { prefs.edit().putBoolean("pendingReset", v).apply() }

    private fun putString(key: String, v: String?) {
        prefs.edit().apply { if (v == null) remove(key) else putString(key, v) }.apply()
    }

    private companion object {
        fun newNodeId(): String {
            val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
            return "dev-" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
