package de.wanderlogplus.server

import de.wanderlogplus.server.ai.AiJobs
import de.wanderlogplus.server.ai.ChatAgent
import de.wanderlogplus.server.ai.ParallelWebSearch
import de.wanderlogplus.server.ai.Assistant
import de.wanderlogplus.server.ai.OpenRouterModel
import de.wanderlogplus.server.ai.ModelFacts
import de.wanderlogplus.server.ai.GuideWriter
import de.wanderlogplus.server.ai.ParallelFacts
import de.wanderlogplus.server.ai.PipelineResearcher
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
    val aiJobs = AiJobs(
        store,
        openRouter?.let { model ->
            val text = textModel ?: model
            // Parallel (or the model's web search) gathers facts, the text model judges them for the trip.
            val facts = config.parallelKey?.let { ParallelFacts(HttpParallelTransport(it), config.parallelProcessor) } ?: ModelFacts(text)
            val researcher = PipelineResearcher(facts, text)
            Assistant(model, PhotonGeocoder(), researcher, textModel = text)
        },
        ToolReelMedia(cookies = config.instagramCookies),
        config.dataDir.resolve("ai-work"),
        writeResults = { edits -> sync.writeAsServer(ASSISTANT_AUTHOR, edits) },
        guideWriter = textModel?.let { text ->
            GuideWriter(config.parallelKey?.let { ParallelFacts(HttpParallelTransport(it), config.parallelProcessor) } ?: ModelFacts(text), text)
        },
    )
    // The chat saves guide pins and rules right away and writes guides through the guides job.
    val chatGuides = object : de.wanderlogplus.server.ai.ChatGuides {
        override fun saveGuide(g: de.wanderlogplus.shared.model.Guide) {
            sync.writeAsServer(ASSISTANT_AUTHOR, listOf(ServerEdit(de.wanderlogplus.shared.sync.EntityType.GUIDE, g.id, de.wanderlogplus.shared.model.EntityCodec.fields(g), create = true)))
        }
        override fun start(asks: List<de.wanderlogplus.shared.sync.GuideAsk>) = aiJobs.startGuides(asks)
    }.takeIf { aiJobs.guidesConfigured }
    val services = Services(
        config, store, sync, FileStore.forConfig(config),
        ImportService(store, sync, tripSourceFrom(config)),
        routes,
        aiJobs,
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
                    guides = chatGuides,
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
