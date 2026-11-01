package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.PROTOCOL_HEADER
import de.wanderlogplus.shared.sync.PROTOCOL_VERSION
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.nio.file.Path

const val TOKEN_A = "token-alice-0123456789"
const val TOKEN_B = "token-bob-0123456789ab"

fun testConfig(dir: Path, maxFileBytes: Long = 25L * 1024 * 1024): ServerConfig = ServerConfig(
    tokens = mapOf("alice" to TOKEN_A, "bob" to TOKEN_B),
    dataDir = dir,
    port = 0,
    maxFileBytes = maxFileBytes,
    tripKey = null,
    importFile = null,
)

fun testServices(dir: Path, maxFileBytes: Long = 25L * 1024 * 1024, source: TripSource? = null): Services {
    val store = SqliteStore(dir.resolve("wanderlog-plus.db"))
    val config = testConfig(dir, maxFileBytes)
    val sync = SyncService(store)
    return Services(config, store, sync, FileStore.forConfig(config), ImportService(store, sync, source))
}

fun HttpRequestBuilder.authed(token: String = TOKEN_A) {
    header(HttpHeaders.Authorization, "Bearer $token")
    header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString())
}
