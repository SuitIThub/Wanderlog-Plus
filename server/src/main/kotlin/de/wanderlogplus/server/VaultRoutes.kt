package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.ErrorResponse
import de.wanderlogplus.shared.sync.VaultImageInfo
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
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val multipart = call.receiveMultipart(formFieldLimit = services.config.maxFileBytes + VAULT_MULTIPART_HEADROOM)
            try {
                multipart.forEachPart { part ->
                    try {
                        when {
                            part is PartData.FileItem && part.name == "file" -> {
                                if (stored != null) throw BadInput()
                                if (!mimeType.startsWith("image/")) throw BadInput()
                                stored = withContext(Dispatchers.IO) {
                                    part.provider().toInputStream().use { services.vault.addImage(call.author(), it, fileName, mimeType, score) }
                                }
                            }
                            part is PartData.FormItem && part.name == "score" ->
                                score = part.value.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
                            part is PartData.FormItem && part.name == "fileName" ->
                                fileName = part.value.take(200).ifBlank { "image" }
                            part is PartData.FormItem && part.name == "mimeType" ->
                                mimeType = part.value.take(100).ifBlank { "image/jpeg" }
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
            call.respond(VaultUploadTrigger(withContext(Dispatchers.IO) { services.vault.uploadRequested(call.author()) }))
        }
        post("/uploads/request/clear") {
            withContext(Dispatchers.IO) { services.vault.clearUploadRequest(call.author()) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

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

/** Overview page (`/vault?token=…`) that reloads only when its content changes, plus image serving for its `<img>` tags. */
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
    get("/vault") {
        val token = call.request.queryParameters["token"]
        val user = call.webUser(services)
        if (user == null || token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            return@get
        }
        val scan = withContext(Dispatchers.IO) { services.vault.scan(user) }
        val images = withContext(Dispatchers.IO) { services.vault.images(user) }.sortedByDescending { it.uploadedAt }
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        call.respondText(vaultPage(user, token, scan, images, page), ContentType.Text.Html)
    }
    // Cheap change fingerprint for the overview page: it polls this instead of reloading blindly and only
    // reloads when one of the values moved (new image, deleted image, scan progress). Numbers only.
    get("/vault/state") {
        val user = call.webUser(services)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            return@get
        }
        val images = withContext(Dispatchers.IO) { services.vault.images(user) }
        val scanUpdated = withContext(Dispatchers.IO) { services.vault.scan(user)?.updatedAt ?: 0L }
        val latest = images.maxOfOrNull { it.uploadedAt } ?: 0L
        call.respondText("""{"images":${images.size},"latest":$latest,"scanUpdated":$scanUpdated}""", ContentType.Application.Json)
    }
}

private fun vaultPage(user: String, token: String, scan: de.wanderlogplus.shared.sync.VaultScanStatus?, images: List<VaultImageInfo>, page: Int): String {
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
          <div><span class="label">Gefunden:</span> ${scan.foundImages}</div>
          ${scan.device?.let { "<div><span class=\"label\">Gerät:</span> ${esc(it)}</div>" } ?: ""}
          <div class="muted">Aktualisiert: ${formatTime(scan.updatedAt)}</div>
        </div>
        """.trimIndent()
    }
    val pageCount = maxOf(1, (images.size + VAULT_PAGE_SIZE - 1) / VAULT_PAGE_SIZE)
    val current = page.coerceIn(1, pageCount)
    val pageImages = images.drop((current - 1) * VAULT_PAGE_SIZE).take(VAULT_PAGE_SIZE)
    val imagesHtml = if (images.isEmpty()) {
        "<p class=\"muted\">Keine Bilder gespeichert.</p>"
    } else {
        pageImages.joinToString("\n") { img ->
            """
            <figure>
              <a href="/vault/img/${img.id}?token=${esc(token)}" target="_blank"><img loading="lazy" src="/vault/img/${img.id}?token=${esc(token)}" alt=""></a>
              <figcaption>${esc(img.fileName)}<br><span class="muted">Score ${(img.score * 100).toInt()}&nbsp;% · ${img.size / 1024}&nbsp;KB</span><br>
              <button onclick="del('${img.id}')">Löschen</button></figcaption>
            </figure>
            """.trimIndent()
        }
    }
    val pagerHtml = if (pageCount <= 1) {
        ""
    } else {
        val prev = if (current > 1) "<a href=\"/vault?token=${esc(token)}&amp;page=${current - 1}\">« Neuere</a>" else ""
        val next = if (current < pageCount) "<a href=\"/vault?token=${esc(token)}&amp;page=${current + 1}\">Ältere »</a>" else ""
        """<div class="pager">$prev<span class="muted">Seite $current von $pageCount</span>$next</div>"""
    }
    // The token reaches the page only after successful authentication and is JSON-escaped for the script context.
    val tokenJs = kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.json.JsonPrimitive.serializer(),
        kotlinx.serialization.json.JsonPrimitive(token),
    )
    // Fingerprint of what the page currently shows; the poll below reloads only when this changes.
    val initialState = """{"images":${images.size},"latest":${images.maxOfOrNull { it.uploadedAt } ?: 0L},"scanUpdated":${scan?.updatedAt ?: 0L}}"""
    return """
<!DOCTYPE html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sensitiv-Scan</title>
<style>
body { font-family: system-ui, sans-serif; background: #14171c; color: #e8eaed; max-width: 1100px; margin: 0 auto; padding: 16px; }
h1 { font-size: 1.4rem; }
h2 { font-size: 1.1rem; margin-top: 24px; }
a { color: #8ab4f8; }
.card { background: #1e242c; border-radius: 12px; padding: 16px; display: grid; gap: 8px; }
.label { color: #9aa0a6; display: inline-block; min-width: 90px; }
progress { width: 100%; height: 14px; }
.muted { color: #9aa0a6; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 12px; }
.pager { display: flex; gap: 16px; justify-content: center; align-items: center; margin: 16px 0; }
figure { margin: 0; background: #1e242c; border-radius: 12px; overflow: hidden; }
figure img { width: 100%; aspect-ratio: 1; object-fit: cover; display: block; background: #0d1013; }
figcaption { padding: 8px; font-size: 0.8rem; word-break: break-all; }
button { background: #353b44; color: #e8eaed; border: 0; border-radius: 8px; padding: 4px 10px; margin-top: 4px; cursor: pointer; }
</style>
</head>
<body>
<h1>Sensitiv-Scan &middot; ${esc(user)}</h1>
$scanHtml
<div class="card">
  <div><button onclick="resumeUpload()">Upload fortsetzen</button> <span class="muted" id="resume-state"></span></div>
  <div class="muted">Fordert das Gerät an, wartende Uploads fortzusetzen – wirkt ab dem nächsten Sync des Geräts.</div>
</div>
<h2>Gefundene Bilder (${images.size})</h2>
<div class="grid">
$imagesHtml
</div>
$pagerHtml
<script>
function del(id) {
  if (!confirm('Bild wirklich löschen?')) return;
  fetch('/api/vault/images/' + id, { method: 'DELETE', headers: { 'Authorization': 'Bearer ' + $tokenJs, 'X-Protocol-Version': '${de.wanderlogplus.shared.sync.PROTOCOL_VERSION}' } })
    .then(function () { location.reload(); });
}
function resumeUpload() {
  fetch('/api/vault/uploads/request', { method: 'POST', headers: { 'Authorization': 'Bearer ' + $tokenJs, 'X-Protocol-Version': '${de.wanderlogplus.shared.sync.PROTOCOL_VERSION}' } })
    .then(function () { document.getElementById('resume-state').textContent = 'Angefordert.'; });
}
// Statt starr neu zu laden: nur abfragen, ob sich etwas getan hat – und nur dann die Seite erneuern.
var known = $initialState;
setInterval(function () {
  fetch('/vault/state?token=' + encodeURIComponent($tokenJs))
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (s) {
      if (s && (s.images !== known.images || s.latest !== known.latest || s.scanUpdated !== known.scanUpdated)) location.reload();
    })
    .catch(function () { /* offline: Seite einfach so lassen */ });
}, 15000);
</script>
</body>
</html>
    """.trimIndent()
}
