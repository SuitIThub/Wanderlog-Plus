package de.wanderlogplus.server

import de.wanderlogplus.server.ai.AiJobs
import de.wanderlogplus.server.ai.ChatAgent
import de.wanderlogplus.server.ai.ParallelWebSearch
import de.wanderlogplus.server.ai.Assistant
import de.wanderlogplus.server.ai.OpenRouterModel
import de.wanderlogplus.server.ai.OpenRouterResearcher
import de.wanderlogplus.server.ai.ParallelResearcher
import de.wanderlogplus.server.ai.HttpParallelTransport
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
    val openRouter = config.openRouterKey?.let { OpenRouterModel(it, config.aiModel) }
    val services = Services(
        config, store, sync, FileStore.forConfig(config),
        ImportService(store, sync, tripSourceFrom(config)),
        RoutesService(store, config.googleRoutesKey?.let { GoogleRoutesTransport(it) }),
        AiJobs(
            store,
            openRouter?.let { model ->
                val researcher = config.parallelKey?.let { ParallelResearcher(HttpParallelTransport(it), config.parallelProcessor) } ?: OpenRouterResearcher(model)
                Assistant(model, PhotonGeocoder(), researcher)
            },
            ToolReelMedia(cookies = config.instagramCookies),
            config.dataDir.resolve("ai-work"),
            writeResults = { edits -> sync.writeAsServer(ASSISTANT_AUTHOR, edits) },
        ),
        transit = TransitService(
            store,
            config.apifyToken?.let { ApifyTransitSource(it, config.apifyTransitActor) },
            config.apifyToken?.let { PhotonStationFinder(PhotonGeocoder()) },
        ),
        chats = ChatService(
            store,
            openRouter?.let { model ->
                // "Today" in Japan, where the trip happens.
                ChatAgent(model, config.parallelKey?.let { ParallelWebSearch(HttpParallelTransport(it)) }) {
                    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).toString()
                }
            },
        ),
    )
    services.ai.resumeInterrupted()
    services.chats.resumeInterrupted()
    embeddedServer(Netty, port = config.port) { module(services) }.start(wait = true)
}
