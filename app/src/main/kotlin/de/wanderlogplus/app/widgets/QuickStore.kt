package de.wanderlogplus.app.widgets

import kotlinx.serialization.Serializable

/**
 * The last "Was jetzt?" ask of the chat widget: [kind] sight or food, the [chatId] that answered, [state] running,
 * done or failed, when it was asked ([at], epoch ms; [date] its local day) and what came back.
 */
@Serializable
data class QuickResult(
    val kind: String,
    val chatId: String?,
    val state: String,
    val at: Long,
    val date: String,
    val cards: List<QuickCard> = emptyList(),
    val text: String? = null,
) {
    companion object {
        const val RUNNING = "RUNNING"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
    }
}

/** One suggested place: a saved place ([savedPlaceId], [imageKey]) or a web find; [distanceMeters] from where the ask was made. */
@Serializable
data class QuickCard(
    val title: String,
    val subtitle: String? = null,
    val savedPlaceId: String? = null,
    val imageKey: String? = null,
    val distanceMeters: Int? = null,
)

/** Keeps the last [QuickResult] in [file]; unreadable or missing gives null. */
class QuickStore(private val file: java.io.File) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun read(): QuickResult? = runCatching { json.decodeFromString(QuickResult.serializer(), file.readText()) }.getOrNull()

    fun write(r: QuickResult) {
        file.parentFile?.mkdirs()
        val tmp = java.io.File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(QuickResult.serializer(), r))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
