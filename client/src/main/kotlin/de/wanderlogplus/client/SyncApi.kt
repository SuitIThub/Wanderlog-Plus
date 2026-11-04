package de.wanderlogplus.client

import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.ErrorResponse
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.PROTOCOL_HEADER
import de.wanderlogplus.shared.sync.PROTOCOL_VERSION
import de.wanderlogplus.shared.sync.PullResponse
import de.wanderlogplus.shared.sync.PushRequest
import de.wanderlogplus.shared.sync.PushResponse
import de.wanderlogplus.shared.sync.RouteInfo
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.AssessRequest
import de.wanderlogplus.shared.sync.AssessConfig
import de.wanderlogplus.shared.sync.ResearchRequest
import de.wanderlogplus.shared.sync.ReelRequest
import de.wanderlogplus.shared.sync.AccessToken
import de.wanderlogplus.shared.sync.AccessTokens
import de.wanderlogplus.shared.sync.CreateTokenRequest
import io.ktor.client.request.delete
import de.wanderlogplus.shared.sync.TransitRequest
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitStationNear
import de.wanderlogplus.shared.sync.TransitStations
import io.ktor.http.encodeURLPathPart
import de.wanderlogplus.shared.sync.RouteRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentDisposition
import io.ktor.http.Headers
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.streams.asInput
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
data class HealthInfo(val status: String, val protocol: Int, val epoch: String, val serverTime: Long)

@Serializable
data class ImportLogInfo(
    val startedAt: String,
    val finishedAt: String,
    val ok: Boolean,
    val created: Int = 0,
    val updated: Int = 0,
    val conflicts: Int = 0,
    val removed: Int = 0,
    val flaggedRemoved: Int = 0,
    val error: String? = null,
)

@Serializable
data class UploadedFile(val hash: String, val size: Long, val duplicate: Boolean)

@Serializable
internal data class WhoAmI(val name: String)

enum class ApiErrorKind { NETWORK, UNAUTHORIZED, PROTOCOL, CURSOR_AHEAD, BAD_REQUEST, CLOCK_DRIFT, NOT_CONFIGURED, SERVER }

class ApiException(
    val kind: ApiErrorKind,
    val code: String? = null,
    val status: Int? = null,
    override val message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: code ?: kind.name, cause)

class SyncApi(http: HttpClient, baseUrl: String, private val token: String) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        // The server omits null fields (e.g. PushResponse.maxHlc when nothing was written).
        explicitNulls = false
    }
    private val client = http.config {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
        }
        expectSuccess = false
    }

    suspend fun health(): HealthInfo = call { client.get("$base/api/health") }

    suspend fun whoami(): String = call<WhoAmI> { client.get("$base/api/whoami") { auth() } }.name

    suspend fun push(changes: List<FieldChange>): PushResponse = call {
        client.post("$base/api/sync/push") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(PushRequest(changes))
        }
    }

    suspend fun pull(since: Long, limit: Int = 500): PullResponse {
        val raw: JsonObject = call {
            client.get("$base/api/sync/pull") {
                auth()
                parameter("since", since)
                parameter("limit", limit)
            }
        }
        // Tolerate record types a newer server knows but this app does not (forward compatibility).
        val records = (raw["records"] as? JsonArray)?.filter { r ->
            val type = ((r as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
            type != null && KNOWN_TYPES.contains(type)
        }
        val cleaned = if (records != null) JsonObject(raw + ("records" to JsonArray(records))) else raw
        try {
            return json.decodeFromJsonElement(PullResponse.serializer(), cleaned)
        } catch (e: Exception) {
            // Never include the body: it may hold booking data.
            throw ApiException(ApiErrorKind.SERVER, message = "Unerwartete Serverantwort", cause = e)
        }
    }

    suspend fun triggerImport(): ImportLogInfo = call {
        client.post("$base/api/import/wanderlog") { auth() }
    }

    suspend fun importStatus(limit: Int = 20): List<ImportLogInfo> = call {
        client.get("$base/api/import/status") {
            auth()
            parameter("limit", limit)
        }
    }

    /** Route between two coordinates via the server (Google Routes API); null when there is no route for this mode. */
    suspend fun computeRoute(req: RouteRequest): RouteInfo? = try {
        call<RouteInfo> {
            client.post("$base/api/routes") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }
    } catch (e: ApiException) {
        if (e.status == 404 && e.code == "no_route") null else throw e
    }

    /**
     * A public-transport connection in Japan (`POST /api/transit`); null when there is none (also: both places use
     * the same station, or no station near one of them – see [ApiException.code] via [transitOrThrow]).
     * NOT_CONFIGURED without an Apify token on the server. A search can take some 20 seconds.
     */
    suspend fun transit(req: TransitRequest): TransitResult? = try {
        transitOrThrow(req)
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    suspend fun transitOrThrow(req: TransitRequest): TransitResult = call {
        client.post("$base/api/transit") {
            auth()
            timeout { requestTimeoutMillis = TRANSIT_TIMEOUT_MS }
            contentType(ContentType.Application.Json)
            setBody(req)
        }
    }

    /** Railway stations near a point, nearest first. */
    suspend fun transitStations(lat: Double, lng: Double): List<TransitStationNear> = call<TransitStations> {
        client.get("$base/api/transit/stations") {
            auth()
            parameter("lat", lat)
            parameter("lng", lng)
        }
    }.stations

    /** All access tokens; this API must have been built with the admin token. NOT_CONFIGURED without one on the server. */
    suspend fun adminTokens(): List<AccessToken> = call<AccessTokens> { client.get("$base/api/admin/tokens") { auth() } }.tokens

    /** A new access token for [name]; BAD_REQUEST code `token_name_taken` when the name is in use. */
    suspend fun createToken(name: String): AccessToken = call {
        client.post("$base/api/admin/tokens") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(CreateTokenRequest(name))
        }
    }

    /** Revokes a token created in the app (`token_in_env` for one from WLP_TOKENS). */
    suspend fun deleteToken(name: String) {
        val r = try {
            client.delete("$base/api/admin/tokens/${name.encodeURLPathPart()}") { auth() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(ApiErrorKind.NETWORK, cause = e)
        }
        if (r.status.value !in 200..299) throw classify(r.status.value, try { r.body<ErrorResponse>() } catch (e: Exception) { null })
    }

    /** Starts the assistant on a reel link; NOT_CONFIGURED without an OpenRouter key on the server. */
    suspend fun startReel(url: String, config: AssessConfig = AssessConfig()): AiJobInfo = call {
        client.post("$base/api/ai/reels") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ReelRequest(url, config))
        }
    }

    /** Starts the assistant on a video file (when the link cannot be downloaded); [caption] is optional. */
    suspend fun uploadReel(file: File, mimeType: String, caption: String? = null, config: AssessConfig = AssessConfig()): AiJobInfo = call {
        client.post("$base/api/ai/reels/upload") {
            auth()
            timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            InputProvider(file.length()) { file.inputStream().asInput() },
                            Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(HttpHeaders.ContentDisposition, ContentDisposition.File.withParameter(ContentDisposition.Parameters.Name, "file").withParameter(ContentDisposition.Parameters.FileName, "reel.mp4").toString())
                            },
                        )
                        caption?.takeIf { it.isNotBlank() }?.let { append("caption", it) }
                        append("config", Json.encodeToString(AssessConfig.serializer(), config))
                    },
                ),
            )
        }
    }

    /** Judges saved places (empty = all without a judgement). */
    suspend fun startAssessment(savedPlaceIds: List<String> = emptyList(), config: AssessConfig = AssessConfig()): AiJobInfo = call {
        client.post("$base/api/ai/assess") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(AssessRequest(savedPlaceIds, config))
        }
    }

    /** Web research (criteria, verdict, insider tips, hidden spots) for 1–300 saved places. */
    suspend fun startResearch(savedPlaceIds: List<String>, config: AssessConfig = AssessConfig()): AiJobInfo = call {
        client.post("$base/api/ai/research") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ResearchRequest(savedPlaceIds, config))
        }
    }

    suspend fun aiJob(id: String): AiJobInfo = call { client.get("$base/api/ai/jobs/${id.encodeURLPathPart()}") { auth() } }

    suspend fun aiJobs(kind: String? = null, limit: Int = 20): List<AiJobInfo> = call {
        client.get("$base/api/ai/jobs") {
            auth()
            kind?.let { parameter("kind", it) }
            parameter("limit", limit)
        }
    }

    suspend fun uploadFile(file: File, fileName: String, mimeType: String): UploadedFile = call {
        client.post("$base/api/files") {
            auth()
            timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            InputProvider(file.length()) { file.inputStream().asInput() },
                            Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(HttpHeaders.ContentDisposition, ContentDisposition.File.withParameter(ContentDisposition.Parameters.Name, "file").withParameter(ContentDisposition.Parameters.FileName, fileName).toString())
                            },
                        )
                    },
                ),
            )
        }
    }

    /** Streams to `<dest>.part`, verifies the SHA-256 against [hash], then moves into place. */
    suspend fun downloadFile(hash: String, dest: File) {
        val part = dest.resolveSibling(dest.name + ".part")
        try {
            client.prepareGet("$base/api/files/$hash") {
                auth()
                timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
            }.execute { response ->
                val status = response.status.value
                if (status !in 200..299) throw classify(status, readError(response))
                val digest = MessageDigest.getInstance("SHA-256")
                val channel = response.bodyAsChannel()
                val buf = ByteArray(64 * 1024)
                part.outputStream().use { out ->
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) continue
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != hash.lowercase()) throw ApiException(ApiErrorKind.SERVER, message = "Datei beschädigt")
            }
            try {
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: ApiException) {
            part.delete()
            throw e
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            throw ApiException(ApiErrorKind.NETWORK, cause = e)
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    private fun HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString())
    }

    private suspend inline fun <reified T> call(block: () -> HttpResponse): T {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(ApiErrorKind.NETWORK, cause = e)
        }
        val status = response.status.value
        if (status in 200..299) {
            try {
                return response.body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw ApiException(ApiErrorKind.NETWORK, cause = e)
            } catch (e: Exception) {
                // Never include the body: it may hold booking data.
                throw ApiException(ApiErrorKind.SERVER, status = status, message = "Unerwartete Serverantwort", cause = e)
            }
        }
        throw classify(status, readError(response))
    }

    private suspend fun readError(response: HttpResponse): ErrorResponse? = try {
        json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun classify(status: Int, err: ErrorResponse?): ApiException {
        val code = err?.error
        val kind = when {
            status == 401 -> ApiErrorKind.UNAUTHORIZED
            status == 409 && code == "protocol_mismatch" -> ApiErrorKind.PROTOCOL
            status == 409 && code == "cursor_ahead" -> ApiErrorKind.CURSOR_AHEAD
            status == 400 && code == "clock_drift" -> ApiErrorKind.CLOCK_DRIFT
            status == 400 && code == "import_not_configured" -> ApiErrorKind.NOT_CONFIGURED
            status == 503 && code == "routes_not_configured" -> ApiErrorKind.NOT_CONFIGURED
            status == 503 && code == "ai_not_configured" -> ApiErrorKind.NOT_CONFIGURED
            status == 503 && code == "transit_not_configured" -> ApiErrorKind.NOT_CONFIGURED
            status == 503 && code == "admin_not_configured" -> ApiErrorKind.NOT_CONFIGURED
            status in 400..499 -> ApiErrorKind.BAD_REQUEST
            else -> ApiErrorKind.SERVER
        }
        return ApiException(kind, code, status, err?.message)
    }

    private companion object {
        val KNOWN_TYPES: Set<String> = EntityType.entries.map { it.name }.toSet()
        const val DOWNLOAD_TIMEOUT_MS = 300_000L
        const val TRANSIT_TIMEOUT_MS = 150_000L
    }
}
