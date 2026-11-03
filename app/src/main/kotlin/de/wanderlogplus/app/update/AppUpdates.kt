package de.wanderlogplus.app.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** GitHub repository whose releases carry the app (see .github/workflows/release.yml). */
const val UPDATE_REPO = "SuitIThub/Wanderlog-Plus"

/** A published app version: its changelog (Markdown) and the APK to download. [sha256] when GitHub lists a digest. */
data class AppRelease(val version: String, val notes: String, val apkUrl: String, val size: Long, val sha256: String?)

/** Numeric parts of a version ("v0.13.4-debug" -> [0, 13, 4]); non-numeric parts end it. */
fun versionParts(v: String): List<Int> =
    v.trim().removePrefix("v").removePrefix("V").split('.', '-', '+').map { it.toIntOrNull() }.takeWhile { it != null }.map { it!! }

/** True when [candidate] is a later version than [current] (missing parts count as 0). */
fun isNewer(candidate: String, current: String): Boolean {
    val a = versionParts(candidate)
    val b = versionParts(current)
    if (a.isEmpty()) return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** The newest release of [repo] from the GitHub API; null when it has none or no APK. Throws on network trouble. */
class ReleaseSource(private val http: HttpClient, private val repo: String = UPDATE_REPO, private val apiBase: String = "https://api.github.com") {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(): AppRelease? {
        val response = http.get("$apiBase/repos/$repo/releases/latest") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "WanderlogPlus")
        }
        if (response.status.value == 404) return null // no release yet
        if (response.status.value !in 200..299) throw IOException("GitHub HTTP ${response.status.value}")
        return parse(response.bodyAsText())
    }

    internal fun parse(body: String): AppRelease? {
        val o = json.parseToJsonElement(body).jsonObject
        if ((o["draft"] as? JsonPrimitive)?.booleanOrNull == true || (o["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val tag = (o["tag_name"] as? JsonPrimitive)?.content ?: return null
        val apk = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { (it["name"] as? JsonPrimitive)?.content?.endsWith(".apk") == true } ?: return null
        val url = (apk["browser_download_url"] as? JsonPrimitive)?.content ?: return null
        if (!url.startsWith("https://")) return null
        return AppRelease(
            version = tag.removePrefix("v"),
            notes = (o["body"] as? JsonPrimitive)?.content.orEmpty().trim(),
            apkUrl = url,
            size = (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            sha256 = (apk["digest"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
        )
    }

    /** Downloads the APK of [release] to [target] and checks size and (when known) its SHA-256; [onProgress] gets (done, total). */
    suspend fun download(release: AppRelease, target: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        http.prepareGet(release.apkUrl) { header(HttpHeaders.UserAgent, "WanderlogPlus") }.execute { response ->
            if (response.status.value !in 200..299) throw IOException("Download HTTP ${response.status.value}")
            val channel = response.bodyAsChannel()
            val total = release.size.takeIf { it > 0 } ?: response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
            var done = 0L
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = channel.readAvailable(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            if (release.size > 0 && done != release.size) throw IOException("Download unvollständig")
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !release.sha256.equals(hash, ignoreCase = true)) {
            part.delete()
            throw IOException("Prüfsumme stimmt nicht")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Datei nicht speicherbar")
        target
    }
}

/** Remembers when the last automatic check ran and which version the user chose to skip. */
interface UpdatePrefs {
    var lastCheckAt: Long
    var skippedVersion: String?
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Only after a check the user asked for. */
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val done: Long, val total: Long) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Update flow: [checkAuto] at start (at most every [autoIntervalMillis], never for a skipped version), [checkNow]
 * from Einstellungen, then [download] after the user agreed; the activity installs [UpdateState.ReadyToInstall].
 */
class UpdateViewModel(
    private val source: ReleaseSource,
    private val currentVersion: String,
    private val prefs: UpdatePrefs,
    private val downloadDir: File,
    /** Automatic checks only for release builds (a debug build is signed differently and cannot take the update). */
    private val autoCheck: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val autoIntervalMillis: Long = 6 * 60 * 60 * 1000L,
) : ViewModel() {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var downloading: Job? = null

    val version: String get() = currentVersion

    fun checkAuto() {
        if (!autoCheck || now() - prefs.lastCheckAt < autoIntervalMillis) return
        check(manual = false)
    }

    fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            _state.value = try {
                val r = source.latest()
                prefs.lastCheckAt = now()
                when {
                    r == null || !isNewer(r.version, currentVersion) -> if (manual) UpdateState.UpToDate else UpdateState.Idle
                    !manual && prefs.skippedVersion == r.version -> UpdateState.Idle
                    else -> UpdateState.Available(r)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) UpdateState.Failed("Konnte nicht nach Updates suchen. Bist du online?") else UpdateState.Idle
            }
        }
    }

    fun later() { _state.value = UpdateState.Idle }

    fun skip(release: AppRelease) {
        prefs.skippedVersion = release.version
        _state.value = UpdateState.Idle
    }

    fun download(release: AppRelease) {
        if (downloading?.isActive == true) return
        _state.value = UpdateState.Downloading(release, 0, release.size)
        downloading = viewModelScope.launch {
            _state.value = try {
                val file = source.download(release, File(downloadDir, "wanderlog-plus-${release.version}.apk")) { done, total ->
                    _state.value = UpdateState.Downloading(release, done, total)
                }
                UpdateState.ReadyToInstall(release, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed("Download fehlgeschlagen. Später noch einmal versuchen.")
            }
        }
    }

    fun cancelDownload() {
        downloading?.cancel()
        _state.value = UpdateState.Idle
    }

    fun dismiss() { if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle }
}
