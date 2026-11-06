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
    // Text work (chat, judgements, research evaluation) runs on the stronger text model; reels stay on the media model.
    val textModel = config.openRouterKey?.let { OpenRouterModel(it, config.textModel) }
    val routes = RoutesService(store, config.googleRoutesKey?.let { GoogleRoutesTransport(it) })
    val transit = TransitService(
        store,
        config.apifyToken?.let { ApifyTransitSource(it, config.apifyTransitActor) },
        config.apifyToken?.let { PhotonStationFinder(PhotonGeocoder()) },
        elsewhere = TransitousSource(store, OpenMeteoHttp()),
    )
    // The chat asks the same services as the app's day screens (and shares their cache).
    val ways = object : de.wanderlogplus.server.ai.ChatWays {
        override fun transit(req: de.wanderlogplus.shared.sync.TransitRequest) = transit.search(req)
        override fun route(req: de.wanderlogplus.shared.sync.RouteRequest) = routes.compute(req)
    }.takeIf { transit.configured || routes.configured }
    val weather = WeatherService(store, OpenMeteoHttp())
    val services = Services(
        config, store, sync, FileStore.forConfig(config),
        ImportService(store, sync, tripSourceFrom(config)),
        routes,
        AiJobs(
            store,
            openRouter?.let { model ->
                val text = textModel ?: model
                val researcher = config.parallelKey?.let { ParallelResearcher(HttpParallelTransport(it), config.parallelProcessor) } ?: OpenRouterResearcher(text)
                Assistant(model, PhotonGeocoder(), researcher, textModel = text)
            },
            ToolReelMedia(cookies = config.instagramCookies),
            config.dataDir.resolve("ai-work"),
            writeResults = { edits -> sync.writeAsServer(ASSISTANT_AUTHOR, edits) },
        ),
        transit = transit,
        weather = weather,
        rates = RatesService(store, OpenMeteoHttp()),
        flightLive = FlightLiveService(store, OpenMeteoHttp(), config.airlabsKey),
        trainLive = TrainLiveService(store, OpenMeteoHttp()),
        photos = PlacePhotosService(store, config.dataDir.resolve("place-photos"), config.googlePlacesKey?.let { GooglePlacesPhotoTransport(it) }),
        chats = ChatService(
            store,
            textModel?.let { model ->
                // "Today" in Japan, where the trip happens.
                ChatAgent(
                    model, config.parallelKey?.let { ParallelWebSearch(HttpParallelTransport(it)) }, PhotonGeocoder(), ways,
                    weather = { trip, today -> weather.forTrip(trip, today) },
                ) {
                    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).toString()
                }
            },
        ),
    )
    services.ai.resumeInterrupted()
    services.chats.resumeInterrupted()
    embeddedServer(Netty, port = config.port) { module(services) }.start(wait = true)
}
