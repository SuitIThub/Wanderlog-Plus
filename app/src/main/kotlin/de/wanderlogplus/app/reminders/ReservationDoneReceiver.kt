package de.wanderlogplus.app.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import de.wanderlogplus.app.WanderlogPlusApp
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** "Reserviert" in a reservation reminder: marks the place as reserved (synced) and closes the notification. */
class ReservationDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val window = intent.getStringExtra(EXTRA_TYPE)?.let { t -> intent.getStringExtra(EXTRA_ID)?.let { t to it } }
        val id = intent.getStringExtra(EXTRA_SAVED_PLACE) ?: window?.takeIf { it.first == "PLACE" }?.second
        if (id == null && window == null) return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
        val container = (context.applicationContext as? WanderlogPlusApp)?.container ?: return
        val pending = goAsync()
        container.scope.launch {
            try {
                container.edit { e, s ->
                    if (id != null) {
                        if (s.savedPlaces.any { it.id == id }) e.set(EntityType.SAVED_PLACE, id, mapOf("reserved" to JsonPrimitive(true)))
                    } else if (window != null) {
                        markWindowDone(e, s, window.first, window.second)
                    }
                }
            } catch (e: Exception) {
                // The reminder comes again at the next step; nothing else to do here.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_SAVED_PLACE = "savedPlaceId"
        const val EXTRA_NOTIFICATION = "notificationId"
        const val EXTRA_TYPE = "windowType"
        const val EXTRA_ID = "windowId"

        /** Sets "erledigt" on the reservation window of a stay, train or flight. */
        internal fun markWindowDone(e: de.wanderlogplus.client.LocalEditor, s: de.wanderlogplus.client.TripSnapshot, type: String, id: String) {
            val (entity, old) = when (type) {
                "STAY" -> EntityType.STAY to (s.stays.firstOrNull { it.id == id } ?: return).reservation
                "TRAIN" -> EntityType.TRAIN to (s.trains.firstOrNull { it.id == id } ?: return).reservation
                "FLIGHT" -> EntityType.FLIGHT to (s.flights.firstOrNull { it.id == id } ?: return).reservation
                else -> return
            }
            val done = (old ?: de.wanderlogplus.shared.model.ReservationWindow()).copy(done = true)
            e.set(entity, id, mapOf("reservation" to kotlinx.serialization.json.Json.encodeToJsonElement(de.wanderlogplus.shared.model.ReservationWindow.serializer(), done)))
        }

        fun windowIntent(context: Context, type: String, id: String, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, ReservationDoneReceiver::class.java)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_NOTIFICATION, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun intent(context: Context, savedPlaceId: String, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, ReservationDoneReceiver::class.java)
                .putExtra(EXTRA_SAVED_PLACE, savedPlaceId)
                .putExtra(EXTRA_NOTIFICATION, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
