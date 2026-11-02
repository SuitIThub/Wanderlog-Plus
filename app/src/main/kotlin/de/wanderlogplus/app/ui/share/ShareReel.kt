package de.wanderlogplus.app.ui.share

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import de.wanderlogplus.app.ui.reels.errorText
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.SyncApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** The first http(s) link in shared text ("Schau dir das an https://www.instagram.com/reel/…"). */
fun firstLinkIn(text: String?): String? = text?.let { Regex("""https?://\S+""").find(it)?.value?.trimEnd('.', ',', ')', '"') }

/** Links the reel analysis accepts: Instagram, TikTok, YouTube (https). */
fun isReelLink(url: String): Boolean {
    val u = url.trim().lowercase()
    if (!u.startsWith("https://")) return false
    val host = u.removePrefix("https://").substringBefore('/').removePrefix("www.").removePrefix("m.")
    return host in setOf("instagram.com", "youtube.com", "youtu.be", "tiktok.com", "vm.tiktok.com")
}

sealed interface ReelShareState {
    data object Idle : ReelShareState
    data object Working : ReelShareState
    data class Started(val jobId: String) : ReelShareState
    data class Failed(val message: String) : ReelShareState
}

/**
 * Hands a shared reel to the server: a link directly, a video file uploaded first. Runs on [scope] (process-wide)
 * so rotating or leaving does not lose the start; the content:// stream is copied before the activity finishes.
 */
class ReelShareViewModel(
    private val api: suspend () -> SyncApi?,
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val config: suspend () -> de.wanderlogplus.shared.sync.AssessConfig = { de.wanderlogplus.shared.sync.AssessConfig() },
) : ViewModel() {
    private val _state = MutableStateFlow<ReelShareState>(ReelShareState.Idle)
    val state: StateFlow<ReelShareState> = _state

    fun startLink(url: String) = run { a -> a.startReel(url, config()).id }

    fun startVideo(resolver: ContentResolver, uri: Uri, mime: String, caption: String?) = run { a ->
        val tmp = File.createTempFile("reel-", ".mp4", cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: throw ApiException(ApiErrorKind.BAD_REQUEST)
            a.uploadReel(tmp, mime, caption, config()).id
        } finally {
            tmp.delete()
        }
    }

    private fun run(block: suspend (SyncApi) -> String) {
        if (_state.value == ReelShareState.Working) return
        _state.value = ReelShareState.Working
        scope.launch(Dispatchers.IO) {
            _state.value = try {
                val a = api() ?: throw ApiException(ApiErrorKind.NOT_CONFIGURED, code = "no_server")
                ReelShareState.Started(block(a))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                ReelShareState.Failed(
                    when {
                        e.code == "no_server" -> MSG_SETUP_FIRST
                        e.kind == ApiErrorKind.NOT_CONFIGURED -> errorText("ai_not_configured")
                        e.kind == ApiErrorKind.NETWORK -> "Server nicht erreichbar"
                        e.code == "file_too_large" || e.code == "payload_too_large" -> "Das Video ist zu groß."
                        e.kind == ApiErrorKind.BAD_REQUEST -> "Das ist kein unterstützter Reel-Link."
                        else -> "Auswertung konnte nicht gestartet werden."
                    },
                )
            } catch (e: Throwable) {
                ReelShareState.Failed("Auswertung konnte nicht gestartet werden.")
            }
        }
    }
}
