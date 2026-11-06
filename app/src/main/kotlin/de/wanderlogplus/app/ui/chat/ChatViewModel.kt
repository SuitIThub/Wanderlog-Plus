package de.wanderlogplus.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.ChatContext
import de.wanderlogplus.shared.sync.ChatInfo
import de.wanderlogplus.shared.sync.ChatSendRequest
import de.wanderlogplus.shared.sync.ChatSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The server's chat endpoints; [SyncApiChatBackend] in the app, fakes in tests. */
interface ChatBackend {
    suspend fun list(): List<ChatSummary>
    suspend fun create(): ChatInfo
    suspend fun get(id: String): ChatInfo
    suspend fun send(id: String, req: ChatSendRequest): ChatInfo
    suspend fun retry(id: String): ChatInfo
    suspend fun delete(id: String)
}

/** No server is set up. */
class NoServer : RuntimeException()

class SyncApiChatBackend(private val api: suspend () -> SyncApi?) : ChatBackend {
    private suspend fun a() = api() ?: throw NoServer()
    override suspend fun list() = a().chats()
    override suspend fun create() = a().createChat()
    override suspend fun get(id: String) = a().chat(id)
    override suspend fun send(id: String, req: ChatSendRequest) = a().sendChat(id, req)
    override suspend fun retry(id: String) = a().retryChat(id)
    override suspend fun delete(id: String) = a().deleteChat(id)
}

/** Remembers the chat that was open last, so the chat button continues it. */
interface LastChat {
    suspend fun get(): String?
    suspend fun set(id: String?)
}

/**
 * The trip chat: one instance for the whole app. [openFrom] sets the context of the screen the chat was opened
 * from (sent with every message until cleared); the answer is polled while the chat is `pending`.
 */
class ChatViewModel(
    private val backend: ChatBackend,
    private val last: LastChat,
    private val pollMillis: Long = 1_500,
    /** Reads the position for "Standort mitsenden"; null: the switch is hidden. */
    private val locator: ChatLocator? = null,
) : ViewModel() {
    val canShareLocation: Boolean get() = locator != null

    /** "Standort mitsenden": every message carries the current position (for that answer only). */
    private val _shareLocation = MutableStateFlow(false)
    val shareLocation: StateFlow<Boolean> = _shareLocation

    fun setShareLocation(on: Boolean) {
        _shareLocation.value = on && locator != null
    }

    /** The permission was refused: the switch stays off. */
    fun locationDenied() {
        _shareLocation.value = false
        _message.value = "Ohne Standort-Berechtigung kann der Chat deinen Standort nicht nutzen."
    }

    private val _chat = MutableStateFlow<ChatInfo?>(null)
    val chat: StateFlow<ChatInfo?> = _chat

    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats: StateFlow<List<ChatSummary>> = _chats

    private val _context = MutableStateFlow<ChatContext?>(null)
    val context: StateFlow<ChatContext?> = _context

    /** True while a message is on its way to the server (before the chat says `pending`). */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    /** A fixed German message for the user; null when fine. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var restored = false
    private var polling: Job? = null

    fun openFrom(context: ChatContext?) {
        _context.value = context
        viewModelScope.launch {
            if (!restored) {
                restored = true
                last.get()?.let { id -> load(id) }
            } else {
                _chat.value?.let { if (it.pending) poll(it.id) else load(it.id) }
            }
        }
    }

    fun clearContext() {
        _context.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun newChat() {
        polling?.cancel()
        _chat.value = null
        viewModelScope.launch { last.set(null) }
    }

    fun select(id: String) {
        polling?.cancel()
        viewModelScope.launch { load(id) }
    }

    fun refreshList() {
        viewModelScope.launch { guard { _chats.value = backend.list() } }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            guard {
                backend.delete(id)
                _chats.value = _chats.value.filterNot { it.id == id }
                if (_chat.value?.id == id) newChat()
            }
        }
    }

    /** Sends [text]; [refs] are the objects picked with "@" (only those still in the text go out). */
    fun send(text: String, refs: List<de.wanderlogplus.shared.sync.ChatRef> = emptyList()) {
        val t = text.trim()
        if (t.isEmpty() || _sending.value || _chat.value?.pending == true) return
        _sending.value = true
        viewModelScope.launch {
            guard {
                val id = _chat.value?.id ?: backend.create().id.also { last.set(it) }
                val location = if (_shareLocation.value) {
                    val l = try { locator?.locate() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    if (l == null) _message.value = "Standort gerade nicht verfügbar – ohne Standort gesendet."
                    l
                } else {
                    null
                }
                val sent = backend.send(id, ChatSendRequest(t, _context.value ?: ChatContext(), location, Mentions.kept(t, refs)))
                _chat.value = sent
                poll(sent.id)
            }
            _sending.value = false
        }
    }

    fun retry() {
        val id = _chat.value?.id ?: return
        viewModelScope.launch {
            guard {
                _chat.value = backend.retry(id)
                poll(id)
            }
        }
    }

    private suspend fun load(id: String) {
        try {
            val c = backend.get(id)
            _chat.value = c
            last.set(c.id)
            if (c.pending) poll(c.id)
        } catch (e: ApiException) {
            if (e.status == 404) { _chat.value = null; last.set(null) } else _message.value = textFor(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = textFor(e)
        }
    }

    /** Asks for the chat until its answer is there; gives up after a few failed calls in a row. */
    private fun poll(id: String) {
        polling?.cancel()
        polling = viewModelScope.launch {
            var failures = 0
            while (true) {
                delay(pollMillis)
                val c = try {
                    backend.get(id).also { failures = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++failures >= 3) { _message.value = textFor(e); return@launch }
                    continue
                }
                if (_chat.value?.id != id) return@launch
                _chat.value = c
                if (!c.pending) return@launch
            }
        }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = textFor(e)
        }
    }

    companion object {
        fun textFor(e: Exception): String = when {
            e is NoServer -> "Kein Server eingerichtet."
            e is ApiException && e.kind == ApiErrorKind.NOT_CONFIGURED -> "Die KI ist auf dem Server nicht eingerichtet (OpenRouter-Key fehlt)."
            e is ApiException && e.code == "chat_busy" -> "Die letzte Antwort läuft noch."
            e is ApiException && e.kind == ApiErrorKind.NETWORK -> "Keine Verbindung zum Server."
            e is ApiException && e.kind == ApiErrorKind.UNAUTHORIZED -> "Der Server hat den Zugang abgelehnt."
            else -> "Der Chat hat gerade ein Problem."
        }
    }
}
