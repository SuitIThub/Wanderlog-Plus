package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.ErrorResponse
import de.wanderlogplus.shared.sync.VaultImageInfo
import de.wanderlogplus.shared.sync.VaultKind
import de.wanderlogplus.shared.sync.VaultScanStates
import de.wanderlogplus.shared.sync.VaultScanUpdate
import de.wanderlogplus.shared.sync.VaultUploadTrigger
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val VAULT_MULTIPART_HEADROOM = 64L * 1024

/** SHA-256 hex, as produced by `VaultStore`'s content hashing. */
private val VAULT_HASH_RE = Regex("[0-9a-f]{64}")

/** Images per page of the web overview; hundreds of `<img>` nodes would make the browser page laggy. */
internal const val VAULT_PAGE_SIZE = 60

/**
 * JSON API of the vault; mounted inside `protectedApi`, so bearer token and protocol header are already checked.
 * Every user only ever sees their own namespace (`call.author()`).
 */
fun Route.vaultApi(services: Services) {
    route("/api/vault") {
        post("/scan") {
            val update = call.receive<VaultScanUpdate>()
            if (update.state !in VaultScanStates.ALL) throw BadInput()
            call.respond(withContext(Dispatchers.IO) { services.vault.saveScan(call.author(), update) })
        }
        get("/scan") {
            val status = withContext(Dispatchers.IO) { services.vault.scan(call.author()) }
            if (status == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")) else call.respond(status)
        }
        post("/images") {
            val declared = call.request.contentLength()
            if (declared != null && declared > services.config.maxFileBytes + VAULT_MULTIPART_HEADROOM) throw FileTooLarge()
            var stored: VaultImageInfo? = null
            var score = 0.0
            var fileName = "image"
            var mimeType = "image/jpeg"
            var kind = VaultKind.PHOTO
            var videoRef: String? = null
            var durationMs: Long? = null
            var videoBytes: Long? = null
            val multipart = call.receiveMultipart(formFieldLimit = services.config.maxFileBytes + VAULT_MULTIPART_HEADROOM)
            try {
                multipart.forEachPart { part ->
                    try {
                        when {
                            part is PartData.FileItem && part.name == "file" -> {
                                if (stored != null) throw BadInput()
                                if (!mimeType.startsWith("image/")) throw BadInput()
                                stored = withContext(Dispatchers.IO) {
                                    part.provider().toInputStream().use {
                                        services.vault.addImage(call.author(), it, fileName, mimeType, score, kind, videoRef, durationMs, videoBytes)
                                    }
                                }
                            }
                            part is PartData.FormItem && part.name == "score" ->
                                score = part.value.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
                            part is PartData.FormItem && part.name == "fileName" ->
                                fileName = part.value.take(200).ifBlank { "image" }
                            part is PartData.FormItem && part.name == "mimeType" ->
                                mimeType = part.value.take(100).ifBlank { "image/jpeg" }
                            // A video entry stores its thumbnail here; the full video follows chunked on request.
                            part is PartData.FormItem && part.name == "kind" ->
                                kind = if (part.value == "video") VaultKind.VIDEO else VaultKind.PHOTO
                            part is PartData.FormItem && part.name == "videoRef" ->
                                videoRef = part.value.take(500).ifBlank { null }
                            part is PartData.FormItem && part.name == "durationMs" ->
                                durationMs = part.value.toLongOrNull()?.coerceAtLeast(0)
                            part is PartData.FormItem && part.name == "videoBytes" ->
                                videoBytes = part.value.toLongOrNull()?.coerceAtLeast(0)
                        }
                    } finally {
                        part.dispose()
                    }
                }
            } catch (e: java.io.IOException) {
                if (e.message?.startsWith("Multipart content length exceeds limit") == true) throw FileTooLarge()
                throw e
            }
            call.respond(stored ?: throw BadInput())
        }
        post("/images/bulk-delete") {
            val req = call.receive<BulkDeleteRequest>()
            if (req.ids.size > 1000) throw BadInput()
            val deleted = withContext(Dispatchers.IO) { req.ids.count { services.vault.deleteImage(call.author(), it) } }
            call.respond(BulkDeleteResponse(deleted))
        }
        get("/images") {
            call.respond(withContext(Dispatchers.IO) { services.vault.images(call.author()) }.sortedByDescending { it.uploadedAt })
        }
        // Declared before "/images/{id}": "by-hash" must not be read as an image id. Lets the uploader check
        // cheaply whether the bytes are already stored before (re-)sending a whole body over a slow link.
        get("/images/by-hash/{hash}") {
            val hash = call.parameters["hash"].orEmpty().lowercase()
            if (!VAULT_HASH_RE.matches(hash)) throw BadInput()
            val found = withContext(Dispatchers.IO) { services.vault.imageByHash(call.author(), hash) }
            if (found == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")) else call.respond(found)
        }
        get("/images/{id}") {
            val id = call.parameters["id"].orEmpty()
            val path = services.vault.imagePath(call.author(), id)
            if (path == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
            } else {
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.respond(LocalFileContent(path.toFile(), contentTypeOf(services.vault.mimeTypeOf(call.author(), id))))
            }
        }
        delete("/images/{id}") {
            val id = call.parameters["id"].orEmpty()
            val removed = withContext(Dispatchers.IO) { services.vault.deleteImage(call.author(), id) }
            if (removed) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
        }
        // "Upload fortsetzen" of the web overview: the device polls this flag after each sync (there is no push).
        post("/uploads/request") {
            withContext(Dispatchers.IO) { services.vault.requestUpload(call.author()) }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/uploads/request") {
            call.respond(
                withContext(Dispatchers.IO) {
                    VaultUploadTrigger(
                        requested = services.vault.uploadRequested(call.author()),
                        paused = services.vault.uploadPaused(call.author()),
                        videoRefs = services.vault.videoRequests(call.author()).sorted(),
                    )
                },
            )
        }
        post("/uploads/request/clear") {
            withContext(Dispatchers.IO) { services.vault.clearUploadRequest(call.author()) }
            call.respond(HttpStatusCode.NoContent)
        }
        // Sticky pause: uploads stop (after the in-flight part) until the web UI resumes them manually.
        post("/uploads/pause") {
            withContext(Dispatchers.IO) { services.vault.setUploadPaused(call.author(), true) }
            call.respond(HttpStatusCode.NoContent)
        }
        post("/uploads/resume") {
            withContext(Dispatchers.IO) { services.vault.setUploadPaused(call.author(), false) }
            call.respond(HttpStatusCode.NoContent)
        }
        // Chunked, resumable upload of one requested video (parts are idempotent per index).
        post("/videos/sessions") {
            val req = call.receive<VideoSessionRequest>()
            val hash = req.sha256.lowercase()
            if (req.videoRef.isBlank() || req.fileName.isBlank() || req.size <= 0 || !VAULT_HASH_RE.matches(hash)) throw BadInput()
            call.respond(
                withContext(Dispatchers.IO) {
                    services.vault.startVideoSession(call.author(), req.videoRef, req.fileName, req.size, hash, req.videoMimeType)
                },
            )
        }
        get("/videos/sessions/{id}") {
            val session = withContext(Dispatchers.IO) { services.vault.videoSession(call.author(), call.parameters["id"].orEmpty()) }
            if (session == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")) else call.respond(session)
        }
        put("/videos/sessions/{id}/parts/{n}") {
            val index = call.parameters["n"]?.toIntOrNull() ?: throw BadInput()
            val partHash = call.request.header("X-Part-Sha256")?.lowercase()
            if (partHash == null || !VAULT_HASH_RE.matches(partHash)) throw BadInput()
            val updated = withContext(Dispatchers.IO) {
                call.receiveStream().use { services.vault.savePart(call.author(), call.parameters["id"].orEmpty(), index, it, partHash) }
            }
            call.respond(updated)
        }
        post("/videos/sessions/{id}/complete") {
            call.respond(withContext(Dispatchers.IO) { services.vault.completeVideoSession(call.author(), call.parameters["id"].orEmpty()) })
        }
        // Queue/dequeue the full video of a vault entry (the id is the entry id, the queue keys by its videoRef).
        post("/videos/{id}/request") {
            videoRequest(services, requested = true)
        }
        post("/videos/{id}/unrequest") {
            videoRequest(services, requested = false)
        }
    }
}

private suspend fun RoutingContext.videoRequest(services: Services, requested: Boolean) {
    val key = call.parameters["id"].orEmpty()
    val entry = withContext(Dispatchers.IO) {
        val all = services.vault.images(call.author())
        all.firstOrNull { it.id == key } ?: all.firstOrNull { it.videoRef == key }
    }
    if (entry == null || entry.kind != VaultKind.VIDEO || entry.videoRef == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
    } else {
        withContext(Dispatchers.IO) {
            if (requested) services.vault.requestVideo(call.author(), entry.videoRef!!) else services.vault.unrequestVideo(call.author(), entry.videoRef!!)
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Body of `POST /api/vault/videos/sessions`; the hash is normalized to lowercase before validation. */
@Serializable
private data class VideoSessionRequest(
    val videoRef: String = "",
    val fileName: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val videoMimeType: String = "application/octet-stream",
)

@Serializable
private data class BulkDeleteRequest(val ids: List<String> = emptyList())

@Serializable
private data class BulkDeleteResponse(val deleted: Int)

private fun contentTypeOf(mimeType: String?): ContentType =
    try {
        mimeType?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream
    } catch (e: Exception) {
        ContentType.Application.OctetStream
    }

/** Browser routes need the token as a query parameter: `<img>` tags cannot send an Authorization header. */
private fun ApplicationCall.webUser(services: Services): String? {
    val fromQuery = request.queryParameters["token"]
    val fromHeader = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    return (fromQuery ?: fromHeader)?.let { services.tokens.nameFor(it) }
}

private fun esc(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

private fun formatTime(epochMillis: Long): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** Overview page (`/vault?token=…`) that reloads only when its content changes, plus media serving for its tags. */
fun Route.vaultWeb(services: Services) {
    get("/vault/img/{id}") {
        val user = call.webUser(services)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            return@get
        }
        val id = call.parameters["id"].orEmpty()
        val path = services.vault.imagePath(user, id)
        if (path == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
        } else {
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.respond(LocalFileContent(path.toFile(), contentTypeOf(services.vault.mimeTypeOf(user, id))))
        }
    }
    // Full video behind an entry (thumbnail stays at /vault/img): streamed, so browsers can seek (206).
    get("/vault/vid/{id}") {
        val user = call.webUser(services)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            return@get
        }
        val id = call.parameters["id"].orEmpty()
        val path = services.vault.videoPath(user, id)
        if (path == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
        } else {
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            val mime = withContext(Dispatchers.IO) { services.vault.images(user).firstOrNull { it.id == id }?.videoMimeType }
            call.respond(LocalFileContent(path.toFile(), contentTypeOf(mime)))
        }
    }
    get("/vault") {
        val token = call.request.queryParameters["token"]
        val user = call.webUser(services)
        if (user == null || token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            return@get
        }
        val scan = withContext(Dispatchers.IO) { services.vault.scan(user) }
        val images = withContext(Dispatchers.IO) { services.vault.images(user) }
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val tab = if (call.request.queryParameters["tab"] == "videos") "videos" else "photos"
        val sort = call.request.queryParameters["sort"]?.takeIf { it in setOf("date", "size", "name") } ?: "date"
        val paused = withContext(Dispatchers.IO) { services.vault.uploadPaused(user) }
        val requests = withContext(Dispatchers.IO) { services.vault.videoRequests(user) }
        val sessions = withContext(Dispatchers.IO) { services.vault.openVideoSessions(user) }
        call.respondText(vaultPage(user, token, scan, images, page, tab, sort, paused, requests, sessions), ContentType.Text.Html)
    }
    // Cheap change fingerprint for the overview page: it polls this instead of reloading blindly and only
    // reloads when one of the values moved (new image, deleted image, scan progress, queue/pause change).
    get("/vault/state") {
        val user = call.webUser(services)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            return@get
        }
        val images = withContext(Dispatchers.IO) { services.vault.images(user) }
        val scanUpdated = withContext(Dispatchers.IO) { services.vault.scan(user)?.updatedAt ?: 0L }
        val requests = withContext(Dispatchers.IO) { services.vault.videoRequests(user) }
        // Sum of received part indices over all open sessions: cheap and change-sensitive.
        val sessionsProgress = withContext(Dispatchers.IO) { services.vault.openVideoSessions(user).sumOf { it.receivedParts.size } }
        val paused = withContext(Dispatchers.IO) { services.vault.uploadPaused(user) }
        val pending = withContext(Dispatchers.IO) { services.vault.scan(user)?.pendingUploads ?: 0 }
        val latest = images.maxOfOrNull { it.uploadedAt } ?: 0L
        call.respondText(
            """{"images":${images.size},"latest":$latest,"scanUpdated":$scanUpdated,"requests":${requests.size},"sessionsProgress":$sessionsProgress,"paused":$paused,"pending":$pending}""",
            ContentType.Application.Json,
        )
    }
}

/** `m:ss` for the duration line of a video card. */
private fun formatDuration(ms: Long): String = "${ms / 60000}:${((ms / 1000) % 60).toString().padStart(2, '0')}"

private fun formatSize(bytes: Long): String =
    if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0)} MB"

private fun vaultPage(
    user: String,
    token: String,
    scan: de.wanderlogplus.shared.sync.VaultScanStatus?,
    images: List<VaultImageInfo>,
    page: Int,
    tab: String,
    sort: String,
    paused: Boolean,
    requests: Set<String>,
    sessions: List<de.wanderlogplus.shared.sync.VaultVideoSession>,
): String {
    val scanHtml = if (scan == null) {
        "<p class=\"muted\">Noch kein Scan gemeldet.</p>"
    } else {
        val stateLabel = when (scan.state) {
            VaultScanStates.RUNNING -> "läuft"
            VaultScanStates.DONE -> "fertig"
            else -> "Fehler"
        }
        val total = scan.totalImages.coerceAtLeast(1)
        val pct = (scan.scannedImages * 100L / total).coerceIn(0, 100)
        """
        <div class="card">
          <div><span class="label">Zustand:</span> ${esc(stateLabel)}</div>
          <progress value="${scan.scannedImages}" max="${scan.totalImages.coerceAtLeast(1)}"></progress>
          <div><span class="label">Fortschritt:</span> ${scan.scannedImages} / ${scan.totalImages} Bilder ($pct&nbsp;%)</div>
          <div><span class="label">Erfasst:</span> ${scan.foundImages}</div>
          ${scan.device?.let { "<div><span class=\"label\">Gerät:</span> ${esc(it)}</div>" } ?: ""}
          <div class="muted">Aktualisiert: ${formatTime(scan.updatedAt)}</div>
        </div>
        """.trimIndent()
    }
    // Progress of an open session in percent, keyed by its videoRef (for the card badges and the status bar).
    val sessionPct: Map<String, Int> = sessions.associate { s ->
        val partCount = ((s.size + s.partSize - 1) / s.partSize).toInt().coerceAtLeast(1)
        s.videoRef to (s.receivedParts.size * 100 / partCount)
    }
    // Always-visible device status: what the device holds, what still waits, what is uploading right now.
    val deviceParts = listOfNotNull(
        scan?.photosOnDevice?.let { "$it Fotos" },
        scan?.videosOnDevice?.let { "$it Videos" },
        scan?.pendingUploads?.let { "$it ausstehend" },
    )
    val statusbarHtml = """
        <div class="statusbar">
          <span><strong>Gerät:</strong> ${if (deviceParts.isEmpty()) "keine Scan-Daten" else esc(deviceParts.joinToString(" · "))}</span>
          ${sessions.joinToString("\n") { "<span>${esc(it.fileName)} lädt ${sessionPct[it.videoRef]} %</span>" }}
          <span>${if (paused) "<span class=\"badge\">pausiert</span> <button onclick=\"setPaused(false)\">Uploads fortsetzen</button>" else "<button onclick=\"setPaused(true)\">Uploads pausieren</button>"}</span>
        </div>
    """.trimIndent()
    val photoCount = images.count { it.kind == VaultKind.PHOTO }
    val videoCount = images.size - photoCount
    val filtered = images.filter { if (tab == "videos") it.kind == VaultKind.VIDEO else it.kind == VaultKind.PHOTO }
    val sorted = when (sort) {
        "size" -> filtered.sortedByDescending { it.size }
        "name" -> filtered.sortedBy { it.fileName.lowercase() }
        else -> filtered.sortedByDescending { it.uploadedAt }
    }
    val pageCount = maxOf(1, (sorted.size + VAULT_PAGE_SIZE - 1) / VAULT_PAGE_SIZE)
    val current = page.coerceIn(1, pageCount)
    val pageImages = sorted.drop((current - 1) * VAULT_PAGE_SIZE).take(VAULT_PAGE_SIZE)
    val imagesHtml = if (sorted.isEmpty()) {
        if (tab == "videos") "<p class=\"muted\">Keine Videos gespeichert.</p>" else "<p class=\"muted\">Keine Bilder gespeichert.</p>"
    } else {
        pageImages.joinToString("\n") { img ->
            val detail = if (img.kind == VaultKind.VIDEO) {
                val meta = listOfNotNull(img.durationMs?.let { formatDuration(it) }, img.videoBytes?.let { formatSize(it) }).joinToString(" · ")
                val state = when {
                    img.hasVideo -> "<a class=\"badge\" href=\"/vault/vid/${img.id}?token=${esc(token)}\" target=\"_blank\">geladen ▶</a>"
                    sessionPct.containsKey(img.videoRef) -> "<span class=\"badge\">lädt ${sessionPct[img.videoRef]} %</span>"
                    img.videoRef != null && img.videoRef in requests -> "<span class=\"badge\">angefordert</span>"
                    else -> "<span class=\"badge\">Thumbnail</span> <button onclick=\"requestVideo('/api/vault/videos/${img.id}/request')\">Upload anfordern</button>"
                }
                "<span class=\"muted\">$meta</span><br>$state"
            } else {
                "<span class=\"muted\">${if (img.score > 0) "Score ${(img.score * 100).toInt()}&nbsp;% · " else ""}${img.size / 1024}&nbsp;KB</span>"
            }
            """
            <figure>
              <input type="checkbox" name="sel" value="${img.id}">
              <a href="/vault/img/${img.id}?token=${esc(token)}" target="_blank"><img loading="lazy" src="/vault/img/${img.id}?token=${esc(token)}" alt=""></a>
              <figcaption>${esc(img.fileName)}<br>$detail<br>
              <button onclick="del('${img.id}')">Löschen</button></figcaption>
            </figure>
            """.trimIndent()
        }
    }
    val tabHref = { t: String -> "/vault?token=${esc(token)}&amp;tab=$t&amp;sort=$sort" }
    val sortHref = { s: String -> "/vault?token=${esc(token)}&amp;tab=$tab&amp;sort=$s" }
    val pagerHtml = if (pageCount <= 1) {
        ""
    } else {
        val prev = if (current > 1) "<a href=\"${sortHref(sort)}&amp;page=${current - 1}\">« Neuere</a>" else ""
        val next = if (current < pageCount) "<a href=\"${sortHref(sort)}&amp;page=${current + 1}\">Ältere »</a>" else ""
        """<div class="pager">$prev<span class="muted">Seite $current von $pageCount</span>$next</div>"""
    }
    // The token reaches the page only after successful authentication and is JSON-escaped for the script context.
    val tokenJs = kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.json.JsonPrimitive.serializer(),
        kotlinx.serialization.json.JsonPrimitive(token),
    )
    // Fingerprint of what the page currently shows; the poll below reloads only when this changes.
    val initialState = """{"images":${images.size},"latest":${images.maxOfOrNull { it.uploadedAt } ?: 0L},"scanUpdated":${scan?.updatedAt ?: 0L},"requests":${requests.size},"sessionsProgress":${sessions.sumOf { it.receivedParts.size }},"paused":$paused,"pending":${scan?.pendingUploads ?: 0}}"""
    val heading = if (tab == "videos") "Videos" else "Bilder"
    return """
<!DOCTYPE html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bilder-Upload</title>
<style>
body { font-family: system-ui, sans-serif; background: #14171c; color: #e8eaed; max-width: 1100px; margin: 0 auto; padding: 16px; }
h1 { font-size: 1.4rem; }
h2 { font-size: 1.1rem; margin-top: 24px; }
a { color: #8ab4f8; }
.card { background: #1e242c; border-radius: 12px; padding: 16px; display: grid; gap: 8px; }
.label { color: #9aa0a6; display: inline-block; min-width: 90px; }
progress { width: 100%; height: 14px; }
.muted { color: #9aa0a6; }
.statusbar { position: sticky; top: 0; z-index: 10; background: #0d1013; border-bottom: 1px solid #353b44; padding: 8px 4px; display: flex; gap: 16px; align-items: center; flex-wrap: wrap; }
.tabs { display: flex; gap: 16px; margin: 16px 0 4px; }
.tabs a { text-decoration: none; padding: 4px 10px; border-radius: 8px; }
.tabs a.active { background: #353b44; color: #e8eaed; }
.badge { background: #353b44; border-radius: 6px; padding: 1px 6px; font-size: 0.75rem; text-decoration: none; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 12px; }
.pager { display: flex; gap: 16px; justify-content: center; align-items: center; margin: 16px 0; }
figure { margin: 0; background: #1e242c; border-radius: 12px; overflow: hidden; position: relative; }
figure input[type=checkbox] { position: absolute; top: 6px; left: 6px; width: 18px; height: 18px; }
figure img { width: 100%; aspect-ratio: 1; object-fit: cover; display: block; background: #0d1013; }
figcaption { padding: 8px; font-size: 0.8rem; word-break: break-all; }
button { background: #353b44; color: #e8eaed; border: 0; border-radius: 8px; padding: 4px 10px; margin-top: 4px; cursor: pointer; }
</style>
</head>
<body>
<h1>Bilder-Upload &middot; ${esc(user)}</h1>
$statusbarHtml
$scanHtml
<div class="card">
  <div><button onclick="resumeUpload()">Upload fortsetzen</button> <span class="muted" id="resume-state"></span></div>
  <div class="muted">Fordert das Gerät an, wartende Uploads fortzusetzen – wirkt ab dem nächsten Sync des Geräts.</div>
</div>
<div class="tabs">
  <a class="${if (tab == "photos") "active" else ""}" href="${tabHref("photos")}">Fotos ($photoCount)</a>
  <a class="${if (tab == "videos") "active" else ""}" href="${tabHref("videos")}">Videos ($videoCount)</a>
</div>
<div class="muted">Sortieren: <a href="${sortHref("date")}">Datum</a> · <a href="${sortHref("size")}">Größe</a> · <a href="${sortHref("name")}">Name</a>
  &nbsp; <button onclick="delSelected()">Ausgewählte löschen</button></div>
<h2>$heading (${sorted.size})</h2>
<div class="grid">
$imagesHtml
</div>
$pagerHtml
<script>
var authHeaders = { 'Authorization': 'Bearer ' + $tokenJs, 'X-Protocol-Version': '${de.wanderlogplus.shared.sync.PROTOCOL_VERSION}' };
function del(id) {
  if (!confirm('Eintrag wirklich löschen?')) return;
  fetch('/api/vault/images/' + id, { method: 'DELETE', headers: authHeaders })
    .then(function () { location.reload(); });
}
function delSelected() {
  var ids = Array.prototype.slice.call(document.querySelectorAll('input[name="sel"]:checked')).map(function (cb) { return cb.value; });
  if (ids.length === 0) return;
  if (!confirm(ids.length + ' Einträge wirklich löschen?')) return;
  fetch('/api/vault/images/bulk-delete', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders), body: JSON.stringify({ ids: ids }) })
    .then(function () { location.reload(); });
}
function requestVideo(url) {
  fetch(url, { method: 'POST', headers: authHeaders })
    .then(function () { location.reload(); });
}
function setPaused(p) {
  fetch(p ? '/api/vault/uploads/pause' : '/api/vault/uploads/resume', { method: 'POST', headers: authHeaders })
    .then(function () { location.reload(); });
}
function resumeUpload() {
  fetch('/api/vault/uploads/request', { method: 'POST', headers: authHeaders })
    .then(function () { document.getElementById('resume-state').textContent = 'Angefordert.'; });
}
// Statt starr neu zu laden: nur abfragen, ob sich etwas getan hat – und nur dann die Seite erneuern.
var known = $initialState;
setInterval(function () {
  fetch('/vault/state?token=' + encodeURIComponent($tokenJs))
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (s) {
      if (s && JSON.stringify(s) !== JSON.stringify(known)) location.reload();
    })
    .catch(function () { /* offline: Seite einfach so lassen */ });
}, 15000);
</script>
</body>
</html>
    """.trimIndent()
}
