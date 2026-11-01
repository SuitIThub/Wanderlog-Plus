package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.ErrorResponse
import de.wanderlogplus.shared.sync.PROTOCOL_HEADER
import de.wanderlogplus.shared.sync.PROTOCOL_VERSION
import io.ktor.http.HttpStatusCode
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
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.MessageDigest

/** Container for server components; later tasks add store, sync, files, imports. */
class Services(val config: ServerConfig)

@Serializable
private data class Health(val status: String, val protocol: Int)

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
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request")) }
        exception<IllegalArgumentException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request")) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request")) }
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
        get("/api/health") { call.respond(Health("ok", PROTOCOL_VERSION)) }
        protectedApi {
            get("/api/whoami") { call.respond(WhoAmI(call.author())) }
        }
    }
}
