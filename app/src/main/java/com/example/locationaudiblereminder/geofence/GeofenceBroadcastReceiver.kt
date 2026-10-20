package com.example.locationaudiblereminder.geofence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.locationaudiblereminder.data.AppDatabase
import com.example.locationaudiblereminder.data.LocationReminder
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null || geofencingEvent.hasError()) {
            Log.e("GeofenceReceiver", "Geofencing error")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            val triggeringGeofences = geofencingEvent.triggeringGeofences
            triggeringGeofences?.forEach { geofence ->
                val reminderId = geofence.requestId.toLongOrNull() ?: return@forEach
                handleGeofenceTrigger(context, reminderId)
            }
        }
    }

    private fun handleGeofenceTrigger(context: Context, reminderId: Long) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val reminder = db.locationReminderDao().getById(reminderId)

                if (reminder != null) {
                    showNotification(context, reminder.name)
                    playAudioAwait(reminder.audioFilePath)
                }
            } catch (e: Exception) {
                Log.e("GeofenceReceiver", "Error handling trigger", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        fun triggerNow(context: Context, reminder: LocationReminder) {
            showNotification(context, reminder.name)
            playAudioAsync(reminder.audioFilePath)
        }

        fun playAudioAsync(filePath: String) {
            val file = File(filePath)
            if (!file.exists()) return

            try {
                val mediaPlayer = MediaPlayer()
                mediaPlayer.setDataSource(filePath)
                mediaPlayer.prepare()
                mediaPlayer.start()
                mediaPlayer.setOnCompletionListener {
                    it.release()
                }
            } catch (e: Exception) {
                Log.e("GeofenceReceiver", "Error playing audio", e)
            }
        }

        suspend fun playAudioAwait(filePath: String) {
            val file = File(filePath)
            if (!file.exists()) return

            withTimeoutOrNull(8000L) {
                suspendCancellableCoroutine { cont ->
                    try {
                        val mediaPlayer = MediaPlayer()
                        mediaPlayer.setDataSource(filePath)
                        mediaPlayer.prepare()
                        mediaPlayer.setOnCompletionListener {
                            it.release()
                            if (cont.isActive) cont.resume(Unit)
                        }
                        mediaPlayer.setOnErrorListener { mp, _, _ ->
                            mp.release()
                            if (cont.isActive) cont.resume(Unit)
                            true
                        }
                        cont.invokeOnCancellation {
                            try {
                                mediaPlayer.release()
                            } catch (_: Exception) {}
                        }
                        mediaPlayer.start()
                    } catch (e: Exception) {
                        Log.e("GeofenceReceiver", "Error playing audio", e)
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
        }

        fun showNotification(context: Context, locationName: String) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "geofence_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Location Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("Arrived at $locationName")
                .setContentText("Playing voice message...")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(locationName.hashCode(), notification)
        }
    }
}
