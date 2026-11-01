package de.wanderlogplus.importer

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

private val stateJson = Json { prettyPrint = false }

private fun usage(): Nothing {
    System.err.println("Usage: (--key <tripKey> | --file <path>) [--state <path>] [--summary]")
    exitProcess(2)
}

private fun printSummary(store: EntityStore) {
    val q = store.tripQueries()
    val regions = store.all(EntityType.REGION).filter { !it.isDeleted }
        .map { EntityCodec.decode<Region>(it) }.associate { it.id to it.name }
    val places = store.all(EntityType.PLACE).map { EntityCodec.decode<Place>(it) }.associate { it.id to it.name }
    for (d in q.days()) {
        val title = d.day?.title.orEmpty()
        val region = d.regionId?.let { regions[it] }.orEmpty()
        val stay = d.stay?.let { places[it.placeId] }.orEmpty()
        println("${d.date} | $title | $region | $stay | ${q.suggestionsFor(d.date).size}")
    }
}

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    var key: String? = null
    var file: String? = null
    var state: String? = null
    var summary = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--key" -> key = args.getOrNull(++i) ?: usage()
            "--file" -> file = args.getOrNull(++i) ?: usage()
            "--state" -> state = args.getOrNull(++i) ?: usage()
            "--summary" -> summary = true
            else -> usage()
        }
        i++
    }
    if ((key == null) == (file == null)) usage()

    try {
        val json = if (key != null) WanderlogClient().fetch(key) else File(file!!).readText()
        val store = InMemoryEntityStore()
        val stateFile = state?.let { File(it).absoluteFile }
        if (stateFile != null && stateFile.exists()) {
            stateJson.decodeFromString(ListSerializer(EntityRecord.serializer()), stateFile.readText())
                .forEach(store::put)
        }
        val result = ImportRunner.run(json, store, HlcClock("importer"))
        if (stateFile != null) {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(stateJson.encodeToString(ListSerializer(EntityRecord.serializer()), store.all()))
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val s = result.stats
        println("created=${s.created} updated=${s.updated} conflicts=${s.conflicts} removed=${s.removed} flaggedRemoved=${s.flaggedRemoved}")
        if (summary) printSummary(store)
    } catch (e: ImportFormatException) {
        System.err.println("Import failed: ${e.message}")
        exitProcess(1)
    }
}
