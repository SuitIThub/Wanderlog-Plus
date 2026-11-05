package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.ErrorResponse
import de.wanderlogplus.shared.sync.PROTOCOL_HEADER
import de.wanderlogplus.shared.sync.PROTOCOL_VERSION
import de.wanderlogplus.shared.sync.PushRequest
import de.wanderlogplus.shared.sync.RouteRequest
import de.wanderlogplus.shared.sync.ReelRequest
import de.wanderlogplus.shared.sync.AssessRequest
import de.wanderlogplus.shared.sync.AssessConfig
import de.wanderlogplus.shared.sync.ResearchRequest
import de.wanderlogplus.server.ai.AiJobs
import de.wanderlogplus.server.ai.AiNotConfigured
import de.wanderlogplus.server.ai.ReelInput
import de.wanderlogplus.server.ai.ToolReelMedia
import de.wanderlogplus.server.ai.firstUrlIn
import de.wanderlogplus.server.ai.isSupportedVideoUrl
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.MessageDigest

private const val MULTIPART_HEADROOM = 64L * 1024

/** Largest video accepted for reel analysis (reels are usually 5–40 MB). */
private const val MAX_REEL_BYTES = 200L * 1024 * 1024

/** Container for server components; later tasks add store, sync, files, imports. */
class Services(
    val config: ServerConfig,
    val store: SqliteStore,
    val sync: SyncService,
    val files: FileStore,
    val imports: ImportService,
    val routes: RoutesService = RoutesService(store, null),
    val ai: AiJobs = AiJobs(store, null, ToolReelMedia(), config.dataDir.resolve("ai-work")),
    val transit: TransitService = TransitService(store, null, null),
    val tokens: TokenService = TokenService(config, store),
    /** Media vault (full image backup): per-user namespace, addressed by random ids (see [vaultApi]/[vaultWeb]). */
    val vault: VaultStore = VaultStore.forConfig(config),
    /** The trip chat (assistant with the saved places and web search). */
    val chats: ChatService = ChatService(store, null),
    /** Weather per trip day (Open-Meteo, JMA models); without HTTP it answers an empty list. */
    val weather: WeatherService = WeatherService(store, null),
    /** Euro exchange rates for the budget (ECB via Frankfurter). */
    val rates: RatesService = RatesService(store, null),
    /** Google photos of places (Places API); without a key `/api/places/photos` answers `photos_not_configured`. */
    val photos: PlacePhotosService = PlacePhotosService(store, config.dataDir.resolve("place-photos"), null),
    /** Live status of flights (AirLabs). */
    val flightLive: FlightLiveService = FlightLiveService(store, null, null),
    /** Live status of Shinkansen. */
    val trainLive: TrainLiveService = TrainLiveService(store, null),
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

/** Admin routes: 503 without `WLP_ADMIN_TOKEN`, 401 unless the bearer token is the admin token. */
private fun Route.adminApi(services: Services, build: Route.() -> Unit) {
    route("/api/admin") {
        install(
            createRouteScopedPlugin("AdminCheck") {
                onCall { call ->
                    if (!services.tokens.adminConfigured) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("admin_not_configured"))
                        return@onCall
                    }
                    val presented = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim().orEmpty()
                    if (!services.tokens.isAdmin(presented)) call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                }
            },
        )
        build()
    }
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
    // Range answers (206) for streamed vault videos; browsers seek with Range headers.
    install(PartialContent)
    install(StatusPages) {
        // Never echo exception messages: they can contain request data.
        exception<PushRejected> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.code, e.message)) }
        exception<ImportNotConfigured> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("import_not_configured")) }
        exception<FileTooLarge> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("file_too_large")) }
        exception<PartConflict> { call, _ -> call.respond(HttpStatusCode.Conflict, ErrorResponse("part_conflict")) }
        exception<SessionGone> { call, _ -> call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")) }
        exception<CursorAhead> { call, _ -> call.respond(HttpStatusCode.Conflict, ErrorResponse("cursor_ahead")) }
        exception<RoutesNotConfigured> { call, _ -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("routes_not_configured")) }
        exception<NoRoute> { call, _ -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no_route")) }
        exception<TokenNameTaken> { call, _ -> call.respond(HttpStatusCode.Conflict, ErrorResponse("token_name_taken")) }
        exception<TokenInEnv> { call, _ -> call.respond(HttpStatusCode.Conflict, ErrorResponse("token_in_env")) }
        exception<TokenNotFound> { call, _ -> call.respond(HttpStatusCode.NotFound, ErrorResponse("token_not_found")) }
        exception<NoStation> { call, _ -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no_station")) }
        exception<TransitNotConfigured> { call, _ -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("transit_not_configured")) }
        exception<TransitUnavailable> { call, e -> call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.code)) }
        exception<RatesUnavailable> { call, _ -> call.respond(HttpStatusCode.BadGateway, ErrorResponse("rates_unavailable")) }
        exception<FlightLiveNotConfigured> { call, _ -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("flight_live_not_configured")) }
        exception<PhotosNotConfigured> { call, _ -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("photos_not_configured")) }
        exception<PhotosUnavailable> { call, _ -> call.respond(HttpStatusCode.BadGateway, ErrorResponse("photos_unavailable")) }
        exception<RoutesUnavailable> { call, _ -> call.respond(HttpStatusCode.BadGateway, ErrorResponse("routes_unavailable")) }
        exception<AiNotConfigured> { call, _ -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("ai_not_configured")) }
        exception<ChatBusy> { call, _ -> call.respond(HttpStatusCode.Conflict, ErrorResponse("chat_busy")) }
        exception<ChatNotFound> { call, _ -> call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")) }
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
                services.tokens.nameFor(cred.token)?.let { UserIdPrincipal(it) }
            }
        }
    }
    routing {
        get("/api/health") { call.respond(Health("ok", PROTOCOL_VERSION, services.store.epoch, System.currentTimeMillis())) }
        vaultWeb(services)
        adminApi(services) {
            get("/tokens") { call.respond(de.wanderlogplus.shared.sync.AccessTokens(withContext(Dispatchers.IO) { services.tokens.list() })) }
            post("/tokens") {
                val req = call.receive<de.wanderlogplus.shared.sync.CreateTokenRequest>()
                call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { services.tokens.create(req.name) })
            }
            delete("/tokens/{name}") {
                val name = call.parameters["name"] ?: throw BadInput()
                withContext(Dispatchers.IO) { services.tokens.delete(name) }
                call.respond(HttpStatusCode.NoContent)
            }
        }
        protectedApi {
            get("/api/whoami") { call.respond(WhoAmI(call.author())) }
            vaultApi(services)
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
            post("/api/transit") {
                val req = call.receive<de.wanderlogplus.shared.sync.TransitRequest>()
                call.respond(withContext(Dispatchers.IO) { services.transit.search(req) })
            }
            get("/api/transit/stations") {
                val lat = call.request.queryParameters["lat"]?.toDoubleOrNull() ?: throw BadInput()
                val lng = call.request.queryParameters["lng"]?.toDoubleOrNull() ?: throw BadInput()
                call.respond(de.wanderlogplus.shared.sync.TransitStations(withContext(Dispatchers.IO) { services.transit.stationsNear(lat, lng) }))
            }
            get("/api/live/train/{id}") {
                val id = call.parameters["id"].orEmpty()
                call.respond(withContext(Dispatchers.IO) { services.trainLive.forTrain(id) })
            }
            get("/api/live/flight/{id}") {
                val id = call.parameters["id"].orEmpty()
                call.respond(withContext(Dispatchers.IO) { services.flightLive.forFlight(id) })
            }
            get("/api/places/photos") {
                val id = call.request.queryParameters["placeId"] ?: throw BadInput()
                call.respond(de.wanderlogplus.shared.sync.PlacePhotos(withContext(Dispatchers.IO) { services.photos.list(id) }))
            }
            get("/api/places/photos/media") {
                val name = call.request.queryParameters["name"] ?: throw BadInput()
                val w = call.request.queryParameters["w"]?.toIntOrNull() ?: 1200
                val path = withContext(Dispatchers.IO) { services.photos.media(name, w) }
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.respond(LocalFileContent(path.toFile(), ContentType.Image.JPEG))
            }
            get("/api/rates") {
                val from = call.request.queryParameters["from"] ?: throw BadInput()
                val date = call.request.queryParameters["date"]
                call.respond(withContext(Dispatchers.IO) { services.rates.rate(from, date) })
            }
            get("/api/weather") {
                val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).toString()
                call.respond(withContext(Dispatchers.IO) { services.weather.forTrip(de.wanderlogplus.server.ai.ChatTrip.from(services.store), today) })
            }
            post("/api/routes") {
                val req = call.receive<RouteRequest>()
                call.respond(withContext(Dispatchers.IO) { services.routes.compute(req) })
            }
            post("/api/ai/reels") {
                if (!services.ai.configured) throw AiNotConfigured()
                val req = call.receive<ReelRequest>()
                val url = firstUrlIn(req.url)?.takeIf { isSupportedVideoUrl(it) } ?: throw BadInput()
                call.respond(services.ai.startReel(ReelInput.Link(url), req.config))
            }
            post("/api/ai/reels/upload") {
                if (!services.ai.configured) throw AiNotConfigured()
                val work = services.config.dataDir.resolve("ai-work")
                withContext(Dispatchers.IO) { Files.createDirectories(work) }
                var video: java.nio.file.Path? = null
                var caption: String? = null
                var config = AssessConfig()
                val multipart = call.receiveMultipart(formFieldLimit = MAX_REEL_BYTES + MULTIPART_HEADROOM)
                try {
                    multipart.forEachPart { part ->
                        try {
                            if (part is PartData.FileItem && part.name == "file" && video == null) {
                                val target = withContext(Dispatchers.IO) { Files.createTempFile(work, "upload-", ".mp4") }
                                video = target
                                val size = withContext(Dispatchers.IO) {
                                    part.provider().toInputStream().use { input -> Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING) }
                                }
                                if (size > MAX_REEL_BYTES) throw FileTooLarge()
                            } else if (part is PartData.FormItem && part.name == "caption") {
                                caption = part.value.take(10_000).ifBlank { null }
                            } else if (part is PartData.FormItem && part.name == "config") {
                                config = try { Json.decodeFromString(AssessConfig.serializer(), part.value) } catch (e: Exception) { AssessConfig() }
                            }
                        } finally {
                            part.dispose()
                        }
                    }
                } catch (e: Exception) {
                    video?.let { withContext(Dispatchers.IO) { Files.deleteIfExists(it) } }
                    if (e is java.io.IOException && e.message?.startsWith("Multipart content length exceeds limit") == true) throw FileTooLarge()
                    throw e
                }
                val v = video ?: throw BadInput()
                call.respond(services.ai.startReel(ReelInput.Video(v, caption), config))
            }
            post("/api/ai/assess") {
                if (!services.ai.configured) throw AiNotConfigured()
                val req = call.receive<AssessRequest>()
                call.respond(services.ai.startAssessment(req.savedPlaceIds, req.config))
            }
            post("/api/ai/research") {
                if (!services.ai.configured) throw AiNotConfigured()
                val req = call.receive<ResearchRequest>()
                if (req.savedPlaceIds.isEmpty() || req.savedPlaceIds.size > 300) throw BadInput()
                call.respond(services.ai.startResearch(req.savedPlaceIds.distinct(), req.config))
            }
            get("/api/ai/jobs") {
                val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw BadInput() } ?: 20
                if (limit !in 1..100) throw BadInput()
                val kind = call.request.queryParameters["kind"]?.takeIf { it in setOf("reel", "assess", "research") }
                call.respond(withContext(Dispatchers.IO) { services.ai.list(kind, limit) })
            }
            get("/api/ai/jobs/{id}") {
                val id = call.parameters["id"].orEmpty()
                val job = withContext(Dispatchers.IO) { services.ai.get(id) }
                if (job == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")) else call.respond(job)
            }
            get("/api/chats") {
                val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw BadInput() } ?: 50
                if (limit !in 1..200) throw BadInput()
                call.respond(withContext(Dispatchers.IO) { services.chats.list(limit) })
            }
            post("/api/chats") {
                if (!services.chats.configured) throw AiNotConfigured()
                call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { services.chats.create(call.author()) })
            }
            get("/api/chats/{id}") {
                val chat = withContext(Dispatchers.IO) { services.chats.get(call.parameters["id"].orEmpty()) } ?: throw ChatNotFound()
                call.respond(chat)
            }
            delete("/api/chats/{id}") {
                withContext(Dispatchers.IO) { services.chats.delete(call.parameters["id"].orEmpty()) }
                call.respond(HttpStatusCode.NoContent)
            }
            post("/api/chats/{id}/messages") {
                val req = call.receive<de.wanderlogplus.shared.sync.ChatSendRequest>()
                val author = call.author()
                call.respond(withContext(Dispatchers.IO) { services.chats.send(call.parameters["id"].orEmpty(), author, req) })
            }
            post("/api/chats/{id}/retry") {
                call.respond(withContext(Dispatchers.IO) { services.chats.retry(call.parameters["id"].orEmpty()) })
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
