package de.wanderlogplus.app.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.Conflict
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.client.keepApp
import de.wanderlogplus.client.takeWanderlog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.JsonElement

/**
 * Resolves [conflict] only if [fresh] still contains it with the same values; otherwise (Wanderlog or another
 * device changed it meanwhile) nothing is written and false is returned.
 */
fun resolveConflict(editor: LocalEditor, fresh: TripSnapshot, conflict: Conflict, takeWanderlog: Boolean): Boolean {
    val current = fresh.conflicts.firstOrNull {
        it.type == conflict.type && it.id == conflict.id && it.field == conflict.field
    } ?: return false
    if (current.appValue != conflict.appValue || current.wanderlogValue != conflict.wanderlogValue) return false
    if (takeWanderlog) editor.takeWanderlog(current) else editor.keepApp(current)
    return true
}

/** Notes, logistics and conflicts. Every write goes through [edit] only. */
class MoreViewModel(
    private val snapshots: suspend () -> StateFlow<TripSnapshot?>,
    private val edit: EditFn,
    /** Process-wide scope for writes: leaving a screen must not cancel a pending write. */
    private val writeScope: CoroutineScope,
) : ViewModel() {
    private val _snapshot = MutableStateFlow<TripSnapshot?>(null)
    val snapshot: StateFlow<TripSnapshot?> = _snapshot

    /** Exception class name of the last failed action (never data); null when fine. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        viewModelScope.launch { snapshots().collect { _snapshot.value = it } }
    }

    fun consumeError() { _error.value = null }

    private fun write(action: (LocalEditor, TripSnapshot) -> Unit): Job = writeScope.launch {
        try {
            edit(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.javaClass.simpleName
        }
    }

    /** Writes the changed fields; for [de.wanderlogplus.app.ui.logistics.NEW_ID] creates the entry. */
    fun saveFields(
        type: EntityType,
        id: String,
        before: Map<String, JsonElement>,
        after: Map<String, JsonElement>,
        /** Further rides of a picked connection: the next legs of a journey after this train. */
        extraRides: List<de.wanderlogplus.shared.model.Train> = emptyList(),
    ) = write { e, s ->
        val newLeg = de.wanderlogplus.app.ui.logistics.parseNewLeg(id)
        val savedId = if (de.wanderlogplus.app.ui.logistics.isNewId(id)) {
            val newId = e.create(type, after)
            // A new leg of a journey goes exactly where it was inserted.
            newLeg?.let { (jid, prev, next) -> if (extraRides.isEmpty()) e.placeInJourney(s, jid, type to newId, prev, next) }
            newId
        } else {
            e.setChanged(type, id, before, after)
            id
        }
        if (type == EntityType.TRAIN && extraRides.isNotEmpty()) {
            val journey = newLeg?.first ?: s.trains.firstOrNull { it.id == savedId }?.journeyId
            e.addRidesAfter(s, savedId, journey, extraRides, newLeg?.second, newLeg?.third)
        }
    }

    /** "Andere Fahrt suchen": the picked connection replaces the ride of train [trainId] (further rides follow it). */
    fun replaceRide(trainId: String, result: de.wanderlogplus.shared.sync.TransitResult) =
        write { e, s -> e.replaceRide(s, trainId, de.wanderlogplus.app.ui.logistics.namedRides(result)) }

    /** "Verbindung ändern": the picked connection replaces all legs of the train journey [journeyId]. */
    fun replaceJourney(journeyId: String, result: de.wanderlogplus.shared.sync.TransitResult) =
        write { e, s -> e.replaceJourney(s, journeyId, de.wanderlogplus.app.ui.logistics.namedRides(result)) }

    /** The seats of [trainId] fit the current ride ("Passen noch"). */
    fun confirmSeats(trainId: String) =
        write { e, _ -> e.set(EntityType.TRAIN, trainId, mapOf("seatsForDeparture" to kotlinx.serialization.json.JsonNull)) }

    fun moveJourneyLeg(journeyId: String, index: Int, up: Boolean) = write { e, s -> e.moveJourneyLeg(s, journeyId, index, up) }

    fun joinJourney(legs: List<Pair<EntityType, String>>) = write { e, s -> if (legs.distinct().size >= 2) e.joinJourney(s, legs) }
    fun leaveJourney(type: EntityType, id: String) = write { e, s -> e.leaveJourney(s, type, id) }
    fun dissolveJourney(journeyId: String) = write { e, s -> e.dissolveJourney(s, journeyId) }
    fun splitLegacy(type: EntityType, id: String) = write { e, s -> e.splitLegacy(s, type, id) }

    /** Saves a stay (a picked hotel becomes a new place first); [existing] null creates it. */
    fun saveStay(existing: de.wanderlogplus.shared.model.Stay?, draft: de.wanderlogplus.app.ui.logistics.StayDraft) = write { e, s ->
        val save = de.wanderlogplus.app.ui.logistics.stayToSave(s, draft, existing, de.wanderlogplus.shared.model.Ids.newId())
        save.newPlace?.let { p -> e.create(EntityType.PLACE, de.wanderlogplus.shared.model.EntityCodec.fields(p), p.id) }
        val after = de.wanderlogplus.shared.model.EntityCodec.fields(save.stay)
        if (existing == null) e.create(EntityType.STAY, after)
        else e.setChanged(EntityType.STAY, existing.id, de.wanderlogplus.shared.model.EntityCodec.fields(existing), after)
    }

    /** Links a stay's place to a Google entry ([hit] null: unlinks). */
    fun linkGoogle(placeId: String, hit: de.wanderlogplus.client.GeoPlace?, takePosition: Boolean) =
        write { e, s -> de.wanderlogplus.app.ui.places.googleLinkEdits(placeId, hit, takePosition)(e, s) }

    /** The photo attachment [attachmentId] becomes the header of [type]/[id]. */
    fun setHeaderFromAttachment(type: EntityType, id: String, attachmentId: String) =
        write { e, s -> de.wanderlogplus.app.ui.attachments.headerFromAttachmentEdits(type, id, attachmentId)(e, s) }

    /** Own header image of a stay ([ref] null: default). */
    fun setHeaderImage(type: EntityType, id: String, ref: String?) =
        write { e, _ -> e.set(type, id, mapOf("headerImage" to (ref?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull))) }

    /** What a photo shows (e.g. [de.wanderlogplus.shared.model.Attachment.LABEL_ROOM]); null removes the mark. */
    fun setPhotoLabel(attachmentId: String, label: String?) =
        write { e, _ -> e.set(EntityType.ATTACHMENT, attachmentId, mapOf("label" to (label?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull))) }

    fun setLinks(type: EntityType, id: String, links: List<de.wanderlogplus.shared.model.CustomLink>) =
        write { e, _ -> e.set(type, id, mapOf("links" to kotlinx.serialization.json.Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(de.wanderlogplus.shared.model.CustomLink.serializer()), links))) }

    fun mergeMoves(type: EntityType, firstId: String, secondId: String) = write { e, s -> e.mergeMoves(s, type, firstId, secondId) }

    fun deleteMove(type: EntityType, id: String) = write { e, _ -> e.delete(type, id) }

    fun keepApp(c: Conflict) = write { e, s -> resolveConflict(e, s, c, takeWanderlog = false) }
    fun takeWanderlog(c: Conflict) = write { e, s -> resolveConflict(e, s, c, takeWanderlog = true) }
}
