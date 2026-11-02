package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.sync.JobItem
import de.wanderlogplus.shared.sync.Worth

/**
 * Receives what a job is doing right now, for the app's "Ablauf" screen. Texts are German and may contain place
 * names and reel content, never booking data or raw model output.
 */
interface JobProgress {
    /** Appends a log line; [level] is one of INFO, OK, WARN, ERROR. */
    fun step(text: String, level: String = INFO) {}

    /** Sets the items (places) the job works through. */
    fun items(items: List<JobItem>) {}

    /** Updates one item's state (WAITING, RUNNING, DONE, FAILED) and/or its current detail line. */
    fun item(key: String, state: String? = null, detail: String? = null) {}

    companion object {
        const val INFO = "INFO"
        const val OK = "OK"
        const val WARN = "WARN"
        const val ERROR = "ERROR"
        val NONE: JobProgress = object : JobProgress {}
    }
}

/** A reporter for one item: its detail line follows the latest message, the log gets "name: message". */
fun JobProgress.sayFor(key: String, name: String): (String) -> Unit = { text ->
    item(key, detail = text)
    step("$name: $text")
}

/** German label of a judgement for log lines. */
fun worthText(w: Worth?): String = when (w) {
    Worth.MUST -> "Muss man sehen"
    Worth.WORTH -> "Lohnt sich"
    Worth.OPTIONAL -> "Wenn Zeit ist"
    Worth.SKIP -> "Eher auslassen"
    null -> "ohne Einschätzung"
}
