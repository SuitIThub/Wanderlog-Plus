package de.wanderlogplus.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.ServerUrl
import de.wanderlogplus.client.SyncApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val url: String = "",
    val token: String = "",
    val urlError: String? = null,
    val insecure: Boolean = false,
    val testing: Boolean = false,
    /** Name the server reported for the token; set only after a successful test of the current input. */
    val connectedAs: String? = null,
    val testError: String? = null,
    val saving: Boolean = false,
) {
    val canSave: Boolean get() = connectedAs != null && !saving
}

/** [testConnection] receives the normalized URL and the token and returns the account name. */
class SetupViewModel(
    private val testConnection: suspend (url: String, token: String) -> Result<String>,
    private val persist: suspend (url: String, token: String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state

    fun setUrl(v: String) = _state.update {
        it.copy(url = v, urlError = null, connectedAs = null, testError = null, insecure = isInsecure(v))
    }

    fun setToken(v: String) = _state.update { it.copy(token = v, connectedAs = null, testError = null) }

    fun test() {
        val s = _state.value
        if (s.testing) return
        val url = ServerUrl.normalize(s.url).getOrElse { e ->
            _state.update { it.copy(urlError = e.message ?: "Ungültige Server-Adresse", connectedAs = null) }
            return
        }
        _state.update { it.copy(testing = true, urlError = null, testError = null, connectedAs = null) }
        viewModelScope.launch {
            val result = try {
                testConnection(url, s.token.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _state.update {
                // Ignore the result if the input changed while testing.
                if (it.url != s.url || it.token != s.token) it.copy(testing = false)
                else it.copy(
                    testing = false,
                    connectedAs = result.getOrNull(),
                    testError = result.exceptionOrNull()?.let { e -> e.message ?: "Verbindung fehlgeschlagen" },
                )
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        val url = ServerUrl.normalize(s.url).getOrNull() ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                persist(url, s.token.trim())
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(testError = "Speichern fehlgeschlagen") }
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    private fun isInsecure(raw: String): Boolean {
        val url = ServerUrl.normalize(raw).getOrNull() ?: return false
        return ServerUrl.isInsecure(url)
    }

    companion object {
        /** Real connection test: health, then whoami; failures become readable German messages. */
        fun connectionTester(http: HttpClient): suspend (String, String) -> Result<String> = { url, token ->
            try {
                val api = SyncApi(http, url, token)
                api.health()
                Result.success(api.whoami())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                Result.failure(Exception(messageFor(e.kind)))
            } catch (e: Exception) {
                Result.failure(Exception("Verbindung fehlgeschlagen"))
            }
        }

        fun messageFor(kind: ApiErrorKind): String = when (kind) {
            ApiErrorKind.NETWORK -> "Server nicht erreichbar. Adresse und Netzwerk prüfen."
            ApiErrorKind.UNAUTHORIZED -> "Token abgelehnt."
            ApiErrorKind.PROTOCOL -> "Server und App passen nicht zusammen. Bitte App oder Server aktualisieren."
            ApiErrorKind.SERVER -> "Der Server meldet einen Fehler."
            else -> "Verbindung fehlgeschlagen."
        }
    }
}
