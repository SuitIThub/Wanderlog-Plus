package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.ErrorResponse
import de.wanderlogplus.shared.sync.PROTOCOL_HEADER
import de.wanderlogplus.shared.sync.PROTOCOL_VERSION
import de.wanderlogplus.shared.sync.PushRequest
import de.wanderlogplus.shared.sync.RouteRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.MessageDigest

private const val MULTIPART_HEADROOM = 64L * 1024

/** Container for server components; later tasks add store, sync, files, imports. */
class Services(
    val config: ServerConfig,
    val store: SqliteStore,
    val sync: SyncService,
    val files: FileStore,
    val imports: ImportService,
    val routes: RoutesService = RoutesService(store, null),
)

@Serializable
private data class Health(val status: String, val protocol: Int, val epoch: String, val serverTime: Long)

@Serializable
private data class WhoAmI(val name: String)

fun ApplicationCall.author(): String =
    principal<UserIdPrincipal>()?.name ?: error("author() called on unauthenticated call")

/** Rejects calls whose protocol header is missing or not [PROTOCOL_VERSION]; runs after authentication succeeded. */
private val ProtocolCheck = createRouteScopedPlugin("ProtocolCheck") {
    on(AuthenticationChecked) { call ->
        if (call.principal<UserIdPrincipal>() == null) return@on
        if (call.request.headers[PROTOCOL_HEADER]?.trim() != PROTOCOL_VERSION.toString()) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(error = "protocol_mismatch", serverProtocol = PROTOCOL_VERSION),
            )
        }
    }
}

/** Routes inside require a valid bearer token and a matching protocol version (auth first). */
fun Route.protectedApi(build: Route.() -> Unit) {
    authenticate("api") {
        install(ProtocolCheck)
        build()
    }
}

private fun findTokenName(tokens: Map<String, String>, presented: String): String? {
    val p = presented.toByteArray(Charsets.UTF_8)
    var found: String? = null
    // No early exit: compare against every token in constant time per comparison.
    for ((name, token) in tokens) {
        if (MessageDigest.isEqual(p, token.toByteArray(Charsets.UTF_8))) found = name
    }
    return found
}

fun Application.module(services: Services) {
    install(ContentNegotiation) {
        json(Json { explicitNulls = false; ignoreUnknownKeys = true })
    }
    install(CallLogging) {
        level = Level.INFO
        // Only method, path, status: never headers, query strings or bodies.
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
    install(StatusPages) {
        // Never echo exception messages: they can contain request data.
        exception<PushRejected> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.code, e.message)) }
        exception<ImportNotConfigured> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("import_not_configured")) }
        exception<FileTooLarge> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("file_too_large")) }
        exception<CursorAhead> { call, _ -> call.respond(HttpStatusCode.Conflict, ErrorResponse("cursor_ahead")) }
        exception<RoutesNotConfigured> { call, _ -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("routes_not_configured")) }
        exception<NoRoute> { call, _ -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no_route")) }
        exception<RoutesUnavailable> { call, _ -> call.respond(HttpStatusCode.BadGateway, ErrorResponse("routes_unavailable")) }
        exception<BadInput> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request")) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request")) }
        exception<ContentTransformationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request")) }
        exception<UnsupportedMediaTypeException> { call, _ -> call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("unsupported_media_type")) }
        exception<PayloadTooLargeException> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("payload_too_large")) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request")) }
        // Ktor 3.0's bearer provider has no challenge hook; its default 401 (with WWW-Authenticate) gets a JSON body here.
        status(HttpStatusCode.Unauthorized) { call, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")) }
        status(HttpStatusCode.UnsupportedMediaType) { call, _ -> call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("unsupported_media_type")) }
        status(HttpStatusCode.NotFound) { call, _ -> call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")) }
        exception<Throwable> { call, e ->
            // Class name and path only: messages and stack traces may contain booking data.
            LoggerFactory.getLogger("de.wanderlogplus.server").error("Unhandled {} on {}", e.javaClass.name, call.request.path())
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal"))
        }
    }
    authentication {
        bearer("api") {
            authenticate { cred ->
                findTokenName(services.config.tokens, cred.token)?.let { UserIdPrincipal(it) }
            }
        }
    }
    routing {
        get("/api/health") { call.respond(Health("ok", PROTOCOL_VERSION, services.store.epoch, System.currentTimeMillis())) }
        protectedApi {
            get("/api/whoami") { call.respond(WhoAmI(call.author())) }
            post("/api/sync/push") {
                val req = call.receive<PushRequest>()
                val author = call.author()
                call.respond(withContext(Dispatchers.IO) { services.sync.push(author, req.changes) })
            }
            post("/api/files") {
                var stored: StoredFile? = null
                val declared = call.request.contentLength()
                if (declared != null && declared > services.config.maxFileBytes + MULTIPART_HEADROOM) throw FileTooLarge()
                // Ktor applies formFieldLimit to file parts too: leave headroom so FileStore's own limit fires first.
                val multipart = call.receiveMultipart(formFieldLimit = services.config.maxFileBytes + MULTIPART_HEADROOM)
                try {
                    multipart.forEachPart { part ->
                        try {
                            if (part is PartData.FileItem && part.name == "file") {
                                if (stored != null) throw BadInput()
                                stored = withContext(Dispatchers.IO) {
                                    part.provider().toInputStream().use { services.files.save(it) }
                                }
                            }
                        } finally {
                            part.dispose()
                        }
                    }
                } catch (e: java.io.IOException) {
                    // Ktor's own multipart limit (only reached for uploads far beyond maxBytes).
                    if (e.message?.startsWith("Multipart content length exceeds limit") == true) throw FileTooLarge()
                    throw e
                }
                call.respond(stored ?: throw BadInput())
            }
            get("/api/files/{hash}") {
                val hash = call.parameters["hash"].orEmpty()
                if (!FileStore.isValidHash(hash)) throw BadInput()
                val path = services.files.open(hash)
                if (path == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
                } else {
                    call.response.headers.append("X-Content-Type-Options", "nosniff")
                    call.respond(LocalFileContent(path.toFile(), ContentType.Application.OctetStream))
                }
            }
            post("/api/routes") {
                val req = call.receive<RouteRequest>()
                call.respond(withContext(Dispatchers.IO) { services.routes.compute(req) })
            }
            post("/api/import/wanderlog") {
                val entry = withContext(Dispatchers.IO) { services.imports.runImport() }
                if (entry.ok) call.respond(entry)
                else call.respond(HttpStatusCode.BadGateway, ErrorResponse("import_failed", entry.error))
            }
            get("/api/import/status") {
                val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw BadInput() } ?: 20
                if (limit !in 1..100) throw BadInput()
                call.respond(withContext(Dispatchers.IO) { services.store.importLog(limit) })
            }
            get("/api/export") {
                val records = withContext(Dispatchers.IO) { services.store.all() }
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"wanderlog-plus-export.json\"")
                call.respond(records)
            }
            get("/api/sync/pull") {
                val q = call.request.queryParameters
                val since = q["since"]?.let { it.toLongOrNull() ?: throw BadInput() } ?: 0L
                val limit = q["limit"]?.let { it.toIntOrNull() ?: throw BadInput() } ?: 500
                call.respond(withContext(Dispatchers.IO) { services.sync.pull(since, limit) })
            }
        }
    }
}
