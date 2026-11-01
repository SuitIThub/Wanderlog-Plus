package de.wanderlogplus.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlin.system.exitProcess

fun main() {
    val config = try {
        ServerConfig.fromEnv(System.getenv())
    } catch (e: IllegalArgumentException) {
        System.err.println("Configuration error: ${e.message}")
        exitProcess(2)
    }
    val services = Services(config)
    embeddedServer(Netty, port = config.port) { module(services) }.start(wait = true)
}
