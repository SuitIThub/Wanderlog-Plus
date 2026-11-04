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
        val id = intent.getStringExtra(EXTRA_SAVED_PLACE) ?: return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
        val container = (context.applicationContext as? WanderlogPlusApp)?.container ?: return
        val pending = goAsync()
        container.scope.launch {
            try {
                container.edit { e, s ->
                    if (s.savedPlaces.any { it.id == id }) e.set(EntityType.SAVED_PLACE, id, mapOf("reserved" to JsonPrimitive(true)))
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
