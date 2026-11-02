package de.wanderlogplus.server

import de.wanderlogplus.server.ai.AiJobs
import de.wanderlogplus.server.ai.Assistant
import de.wanderlogplus.server.ai.OpenRouterModel
import de.wanderlogplus.server.ai.PhotonGeocoder
import de.wanderlogplus.server.ai.ToolReelMedia
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Files
import kotlin.system.exitProcess

fun main() {
    val config = try {
        ServerConfig.fromEnv(System.getenv())
    } catch (e: IllegalArgumentException) {
        System.err.println("Configuration error: ${e.message}")
        exitProcess(2)
    }
    Files.createDirectories(config.dataDir)
    val store = SqliteStore(config.dataDir.resolve("wanderlog-plus.db"))
    Runtime.getRuntime().addShutdownHook(Thread { store.close() })
    val sync = SyncService(store)
    val services = Services(
        config, store, sync, FileStore.forConfig(config),
        ImportService(store, sync, tripSourceFrom(config)),
        RoutesService(store, config.googleRoutesKey?.let { GoogleRoutesTransport(it) }),
        AiJobs(
            store,
            config.openRouterKey?.let { Assistant(OpenRouterModel(it, config.aiModel), PhotonGeocoder()) },
            ToolReelMedia(cookies = config.instagramCookies),
            config.dataDir.resolve("ai-work"),
        ),
    )
    embeddedServer(Netty, port = config.port) { module(services) }.start(wait = true)
}
