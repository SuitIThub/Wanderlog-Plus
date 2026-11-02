package de.wanderlogplus.server.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/** The reel could not be downloaded (private, login wall, Instagram changed, no network). */
class DownloadFailed : RuntimeException("download_failed")

/** A downloaded video and the text that came with it (caption/title), if any. */
data class Downloaded(val video: Path, val caption: String?)

/** What the model gets from a video: a few still frames and the sound track. */
data class Media(val frames: List<ByteArray>, val audio: ByteArray?)

/** Download and decomposition of videos; replaced by fakes in tests. */
interface ReelMedia {
    fun download(url: String, dir: Path): Downloaded
    fun extract(video: Path, dir: Path): Media
}

/**
 * yt-dlp for the download (with the caption from its info JSON) and ffmpeg for frames and audio. [cookies] is an
 * optional Netscape cookies file for yt-dlp, for reels Instagram shows only to logged-in users.
 */
class ToolReelMedia(
    private val ytDlp: String = "yt-dlp",
    private val ffmpeg: String = "ffmpeg",
    private val cookies: Path? = null,
) : ReelMedia {
    private val json = Json { ignoreUnknownKeys = true }

    override fun download(url: String, dir: Path): Downloaded {
        val args = mutableListOf(
            ytDlp, "--no-playlist", "--no-progress", "--no-warnings",
            "-f", "mp4/best[height<=720]/best",
            "--max-filesize", "200M",
            "--write-info-json",
            "-o", dir.resolve("video.%(ext)s").toString(),
        )
        cookies?.let { args += listOf("--cookies", it.toString()) }
        args += url
        if (!run(args, 180)) throw DownloadFailed()
        val video = dir.listDirectoryEntries("video.*").firstOrNull { it.isRegularFile() && !it.name.endsWith(".json") } ?: throw DownloadFailed()
        val info = dir.listDirectoryEntries("*.info.json").firstOrNull()
        val caption = info?.let { captionOf(it.readText()) }
        return Downloaded(video, caption)
    }

    internal fun captionOf(infoJson: String): String? = try {
        val o = json.parseToJsonElement(infoJson).jsonObject
        listOf("description", "title").mapNotNull { (o[it] as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }.distinct().joinToString("\n\n").ifBlank { null }
    } catch (e: Exception) {
        null
    }

    override fun extract(video: Path, dir: Path): Media {
        // One frame every 3 s, at most 12, scaled to 512 px width: enough to read signs, small enough for the model.
        run(listOf(ffmpeg, "-y", "-loglevel", "error", "-i", video.toString(), "-vf", "fps=1/3,scale=512:-2", "-frames:v", "12", "-q:v", "5", dir.resolve("frame_%02d.jpg").toString()), 120)
        val frames = dir.listDirectoryEntries("frame_*.jpg").sortedBy { it.name }.map { it.readBytes() }
        val audioPath = dir.resolve("audio.mp3")
        val hasAudio = run(listOf(ffmpeg, "-y", "-loglevel", "error", "-i", video.toString(), "-vn", "-ac", "1", "-ar", "16000", "-b:a", "48k", "-t", "180", audioPath.toString()), 120) &&
            audioPath.exists() && Files.size(audioPath) > 1000
        return Media(frames, if (hasAudio) audioPath.readBytes() else null)
    }

    /** Runs a tool without a shell; true on exit code 0 within [timeoutSeconds]. Output is discarded (may contain URLs). */
    private fun run(args: List<String>, timeoutSeconds: Long): Boolean {
        val p = try {
            ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            return false
        }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return false
        }
        return p.exitValue() == 0
    }
}

/** True for links the reel import accepts: Instagram reels/posts and short video platforms yt-dlp knows. */
fun isSupportedVideoUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    if (!u.startsWith("https://")) return false
    val host = u.removePrefix("https://").substringBefore('/').removePrefix("www.").removePrefix("m.")
    return host in setOf("instagram.com", "youtube.com", "youtu.be", "tiktok.com", "vm.tiktok.com")
}

/** The first http(s) link in shared text ("Schau dir das an https://www.instagram.com/reel/…"). */
fun firstUrlIn(text: String): String? = Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '"')

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
