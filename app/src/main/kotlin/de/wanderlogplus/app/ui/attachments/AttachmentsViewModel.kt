package de.wanderlogplus.app.ui.attachments

import androidx.lifecycle.ViewModel
import de.wanderlogplus.app.files.AttachmentTooLargeException
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/** What the user picked: name, type and a stream, resolved lazily on IO. */
interface AttachmentSource {
    fun fileName(): String
    fun mimeType(): String
    fun open(): InputStream?
}

sealed interface AttachmentTarget {
    data class Entity(val type: EntityType, val id: String) : AttachmentTarget
    data class Day(val date: String) : AttachmentTarget
}

const val MSG_TOO_LARGE = "Datei zu groß (max. 25 MB)"
const val MSG_ADD_FAILED = "Anhang konnte nicht hinzugefügt werden"
const val MSG_DELETE_FAILED = "Anhang konnte nicht gelöscht werden"

class AttachmentsViewModel(
    private val edit: EditFn,
    private val addAttachment: suspend (EntityType, suspend () -> String, InputStream, String, String) -> String,
    /** Creates the Day for [date] when missing (empty title) and returns its id. */
    private val ensureDay: suspend (date: String) -> String,
    /** Process-wide scope: leaving a screen must not cancel an import. */
    private val writeScope: CoroutineScope,
    /** Adds a gallery photo (shrunk); null: no photos. */
    private val addPhoto: (suspend (EntityType, suspend () -> String, InputStream, String) -> String)? = null,
) : ViewModel() {
    /** Fixed German user message of the last failure (never file names or data); null when fine. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() { _message.value = null }

    fun add(target: AttachmentTarget, source: AttachmentSource): Job = writeScope.launch(Dispatchers.IO) {
        try {
            val input = source.open() ?: run { _message.value = MSG_ADD_FAILED; return@launch }
            input.use {
                // The Day is created only after the file was imported, so a rejected file leaves nothing behind.
                when (target) {
                    is AttachmentTarget.Entity -> addAttachment(target.type, { target.id }, it, source.fileName(), source.mimeType())
                    is AttachmentTarget.Day -> addAttachment(EntityType.DAY, { ensureDay(target.date) }, it, source.fileName(), source.mimeType())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AttachmentTooLargeException) {
            _message.value = MSG_TOO_LARGE
        } catch (e: Exception) {
            _message.value = MSG_ADD_FAILED
        }
    }

    /** Adds several gallery photos one after another; says how many made it. [onAdded] gets the new attachments' ids. */
    fun addPhotos(target: AttachmentTarget, sources: List<AttachmentSource>, onAdded: suspend (List<String>) -> Unit = {}): Job = writeScope.launch(Dispatchers.IO) {
        val add = addPhoto ?: return@launch
        var ok = 0
        val ids = mutableListOf<String>()
        for (source in sources) {
            try {
                val input = source.open() ?: continue
                ids += input.use {
                    when (target) {
                        is AttachmentTarget.Entity -> add(target.type, { target.id }, it, source.fileName())
                        is AttachmentTarget.Day -> add(EntityType.DAY, { ensureDay(target.date) }, it, source.fileName())
                    }
                }
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // counted below
            }
        }
        _message.value = when {
            ok == sources.size -> if (ok == 1) "1 Foto hinzugefügt" else "$ok Fotos hinzugefügt"
            ok == 0 -> "Fotos konnten nicht hinzugefügt werden"
            else -> "$ok von ${sources.size} Fotos hinzugefügt"
        }
        if (ids.isNotEmpty()) onAdded(ids)
    }

    fun delete(a: Attachment): Job = writeScope.launch {
        try {
            edit { e, _ -> e.delete(EntityType.ATTACHMENT, a.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = MSG_DELETE_FAILED
        }
    }
}
