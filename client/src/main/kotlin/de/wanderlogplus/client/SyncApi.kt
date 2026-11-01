package de.wanderlogplus.client

import de.wanderlogplus.shared.sync.ErrorResponse
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.PROTOCOL_HEADER
import de.wanderlogplus.shared.sync.PROTOCOL_VERSION
import de.wanderlogplus.shared.sync.PullResponse
import de.wanderlogplus.shared.sync.PushRequest
import de.wanderlogplus.shared.sync.PushResponse
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
import java.io.IOException

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
    private val json = Json { ignoreUnknownKeys = true }
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

    suspend fun pull(since: Long, limit: Int = 500): PullResponse = call {
        client.get("$base/api/sync/pull") {
            auth()
            parameter("since", since)
            parameter("limit", limit)
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
            status in 400..499 -> ApiErrorKind.BAD_REQUEST
            else -> ApiErrorKind.SERVER
        }
        return ApiException(kind, code, status, err?.message)
    }
}
