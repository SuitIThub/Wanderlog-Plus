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

/**
 * The reel or post could not be downloaded (private, login wall, Instagram changed, no network); [reasons] are the
 * tools' last error lines, links removed.
 */
class DownloadFailed(val reasons: List<String> = emptyList()) : RuntimeException("download_failed") {
    /** Instagram showed the post only to logged-in users (or wants a fresh login). */
    val loginRequired: Boolean get() = reasons.any { r -> LOGIN_HINTS.any { it in r.lowercase() } }

    private companion object {
        val LOGIN_HINTS = listOf("login", "log in", "logged in", "401", "unauthorized", "checkpoint")
    }
}

/** How a tool run went: [ok] on exit code 0; [error] its last error line, links removed (null when ok or silent). */
data class ToolRun(val ok: Boolean, val error: String? = null)

/** The line of a tool's output that says what went wrong: the last one with "error", else the last one; links removed, 200 characters at most. */
internal fun toolReason(output: String): String? {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val line = lines.lastOrNull { "error" in it.lowercase() } ?: lines.lastOrNull() ?: return null
    return line.replace(Regex("""https?://\S+"""), "<Link>").take(200)
}

/**
 * What came down for a reel or post: its videos and pictures in gallery order and the text that came with it
 * (caption/title), if any. A reel is one video; a picture post one or more images; a gallery may mix both.
 */
data class Downloaded(val videos: List<Path>, val images: List<Path>, val caption: String?)

/** What the model gets from a post: pictures (gallery images first, then video stills) and the sound track. */
data class Media(val frames: List<ByteArray>, val audio: ByteArray?)

/** Download and decomposition of posts; replaced by fakes in tests. */
interface ReelMedia {
    fun download(url: String, dir: Path): Downloaded
    fun extract(d: Downloaded, dir: Path): Media
}

/** Pictures the model gets from one post at most. */
const val MAX_POST_IMAGES = 20

/** Video stills per video at most. */
private const val MAX_FRAMES_PER_VIDEO = 12

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic")
private val VIDEO_EXT = setOf("mp4", "mov", "webm", "m4v")

/** Videos and images below [dir] (any depth), each sorted by file name, which is the gallery order of gallery-dl. */
internal fun collectGallery(dir: Path): Pair<List<Path>, List<Path>> {
    val files = Files.walk(dir).use { s -> s.filter { it.isRegularFile() }.toList() }.sortedBy { it.name }
    fun ext(p: Path) = p.name.substringAfterLast('.', "").lowercase()
    return files.filter { ext(it) in VIDEO_EXT } to files.filter { ext(it) in IMAGE_EXT }
}

/** The post's text from gallery-dl's metadata files (`description` or `caption`); null without one. */
internal fun galleryCaption(dir: Path): String? {
    val json = Json { ignoreUnknownKeys = true }
    val files = Files.walk(dir).use { s -> s.filter { it.isRegularFile() && it.name.endsWith(".json") }.toList() }.sortedBy { it.name }
    return files.firstNotNullOfOrNull { f ->
        try {
            val o = json.parseToJsonElement(f.readText()).jsonObject
            o.str("description") ?: o.str("caption")
        } catch (e: Exception) {
            null
        }
    }
}

/** Stills per video: what [images] leave of [MAX_POST_IMAGES], shared out evenly (first videos get the rest), at most 12 each. */
internal fun frameBudget(images: Int, videos: Int): List<Int> {
    if (videos <= 0) return emptyList()
    val budget = (MAX_POST_IMAGES - images).coerceAtLeast(0)
    return List(videos) { i -> (budget / videos + if (i < budget % videos) 1 else 0).coerceAtMost(MAX_FRAMES_PER_VIDEO) }
}

/**
 * yt-dlp for videos (with the caption from its info JSON), gallery-dl for picture posts and galleries, ffmpeg for
 * frames, scaled pictures and audio. [cookies] is an optional Netscape cookies file for both downloaders, for posts
 * Instagram shows only to logged-in users.
 */
class ToolReelMedia(
    private val ytDlp: String = "yt-dlp",
    private val ffmpeg: String = "ffmpeg",
    private val cookies: Path? = null,
    private val galleryDl: String = "gallery-dl",
    /** Runs a tool (arguments, timeout in seconds), true on success; replaced in tests. */
    runner: ((List<String>, Long) -> Boolean)? = null,
    /** Like [runner], with the tool's error line; replaced in tests. */
    tool: ((List<String>, Long) -> ToolRun)? = null,
) : ReelMedia {
    private val json = Json { ignoreUnknownKeys = true }
    private val runTool: (List<String>, Long) -> ToolRun = tool ?: runner?.let { r -> { a, t -> ToolRun(r(a, t)) } } ?: ::runWithReason

    /**
     * Instagram posts (`/p/`) may be galleries mixing pictures and videos, where yt-dlp would bring only a video:
     * gallery-dl goes first there. Reels and other video links go to yt-dlp first. Each falls back to the other.
     */
    override fun download(url: String, dir: Path): Downloaded {
        val post = isInstagramPost(url)
        val reasons = mutableListOf<String>()
        val first = if (post) gallery(url, dir, reasons) else video(url, dir, reasons)
        return first ?: (if (post) video(url, dir, reasons) else gallery(url, dir, reasons)) ?: throw DownloadFailed(reasons)
    }

    private fun video(url: String, dir: Path, reasons: MutableList<String>): Downloaded? {
        val args = mutableListOf(
            ytDlp, "--no-playlist", "--no-progress", "--no-warnings",
            "-f", "mp4/best[height<=720]/best",
            "--max-filesize", "200M",
            "--write-info-json",
            "-o", dir.resolve("video.%(ext)s").toString(),
        )
        cookies?.let { args += listOf("--cookies", it.toString()) }
        args += url
        val r = runTool(args, 180)
        if (!r.ok) { r.error?.let(::toolReason)?.let { reasons += "yt-dlp: $it" }; return null }
        val video = dir.listDirectoryEntries("video.*").firstOrNull { it.isRegularFile() && !it.name.endsWith(".json") } ?: return null
        val info = dir.listDirectoryEntries("*.info.json").firstOrNull()
        return Downloaded(listOf(video), emptyList(), info?.let { captionOf(it.readText()) })
    }

    private fun gallery(url: String, dir: Path, reasons: MutableList<String>): Downloaded? {
        val gallery = Files.createDirectories(dir.resolve("gallery"))
        // At most the first 20 items and 200 MB each; {num} keeps the gallery order in the file names.
        val g = mutableListOf(
            galleryDl, "--quiet", "--write-metadata", "--range", "1-$MAX_POST_IMAGES", "--filesize-max", "200M",
            "-f", "{num:>03}.{extension}", "-D", gallery.toString(),
        )
        cookies?.let { g += listOf("--cookies", it.toString()) }
        g += url
        val r = runTool(g, 180)
        val (videos, images) = collectGallery(gallery)
        if (videos.isEmpty() && images.isEmpty()) {
            reasons += "gallery-dl: " + (r.error?.let(::toolReason) ?: if (r.ok) "keine Bilder gefunden" else "fehlgeschlagen")
            return null
        }
        return Downloaded(videos, images, galleryCaption(gallery))
    }

    internal fun captionOf(infoJson: String): String? = try {
        val o = json.parseToJsonElement(infoJson).jsonObject
        listOf("description", "title").mapNotNull { (o[it] as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }.distinct().joinToString("\n\n").ifBlank { null }
    } catch (e: Exception) {
        null
    }

    override fun extract(d: Downloaded, dir: Path): Media {
        val pictures = mutableListOf<ByteArray>()
        // Gallery pictures at up to 1024 px: text on tip slides must stay readable.
        d.images.take(MAX_POST_IMAGES).forEachIndexed { i, img ->
            val out = dir.resolve("img_%02d.jpg".format(i))
            if (run(listOf(ffmpeg, "-y", "-loglevel", "error", "-i", img.toString(), "-vf", "scale='min(1024,iw)':-2", "-q:v", "4", out.toString()), 60) && out.exists()) {
                pictures += out.readBytes()
            }
        }
        val budget = frameBudget(pictures.size, d.videos.size)
        d.videos.forEachIndexed { v, video ->
            val n = budget[v]
            if (n == 0) return@forEachIndexed
            // One frame every 3 s, scaled to 512 px width: enough to read signs, small enough for the model.
            run(listOf(ffmpeg, "-y", "-loglevel", "error", "-i", video.toString(), "-vf", "fps=1/3,scale=512:-2", "-frames:v", "$n", "-q:v", "5", dir.resolve("frame_${v}_%02d.jpg").toString()), 120)
            pictures += dir.listDirectoryEntries("frame_${v}_*.jpg").sortedBy { it.name }.map { it.readBytes() }
        }
        var audio: ByteArray? = null
        for ((v, video) in d.videos.withIndex()) {
            val audioPath = dir.resolve("audio_$v.mp3")
            if (run(listOf(ffmpeg, "-y", "-loglevel", "error", "-i", video.toString(), "-vn", "-ac", "1", "-ar", "16000", "-b:a", "48k", "-t", "180", audioPath.toString()), 120) &&
                audioPath.exists() && Files.size(audioPath) > 1000
            ) {
                audio = audioPath.readBytes()
                break
            }
        }
        return Media(pictures.take(MAX_POST_IMAGES), audio)
    }

    /** Runs a downloader without a shell; its output goes to a temp file only to keep the last error line (links removed). */
    private fun runWithReason(args: List<String>, timeoutSeconds: Long): ToolRun {
        val log = try { Files.createTempFile("wlp-tool", ".log") } catch (e: Exception) { return ToolRun(run(args, timeoutSeconds)) }
        try {
            val p = try {
                ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            } catch (e: Exception) {
                return ToolRun(false, "${args.first()} lässt sich nicht starten")
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return ToolRun(false, "Zeitüberschreitung nach $timeoutSeconds s")
            }
            val ok = p.exitValue() == 0
            return ToolRun(ok, if (ok) null else toolReason(log.readText()) ?: "Exit-Code ${p.exitValue()}")
        } finally {
            try { Files.deleteIfExists(log) } catch (_: Exception) {}
        }
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

/** An Instagram post (picture, gallery or video post) rather than a reel: `instagram.com/p/…` or `/<user>/p/…`. */
internal fun isInstagramPost(url: String): Boolean {
    val u = url.trim().lowercase()
    val host = u.substringAfter("://").substringBefore('/').removePrefix("www.").removePrefix("m.")
    return host == "instagram.com" && Regex("""^/([^/]+/)?p/""").containsMatchIn(u.substringAfter("://").removePrefix(u.substringAfter("://").substringBefore('/')))
}

/** The first http(s) link in shared text ("Schau dir das an https://www.instagram.com/reel/…"). */
fun firstUrlIn(text: String): String? = Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '"')

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
