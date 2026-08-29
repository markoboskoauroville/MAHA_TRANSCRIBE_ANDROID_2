package com.mantra.mahatranscribe.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mantra.mahatranscribe.R

/**
 * THE ONLY REASON THIS SERVICE EXISTS.
 *
 * Android stops giving microphone samples to an app that is not in the foreground. Without a
 * foreground service, switching to WhatsApp mid-sentence does not merely background the app, it
 * silently stops the recording. The person comes back to a take that ends where they left the
 * screen, with no error anywhere, which is exactly the shape of failure the brief names as the
 * worst one: it is not a crash, so nothing reports it.
 *
 * The notification is not decoration and cannot be removed. It is the price Android charges for
 * being allowed to keep listening, and it is also honest: something is recording, and the person
 * can see that from any screen on the phone.
 *
 * ### What this service deliberately does NOT do
 *
 * It does not own the recorder. [Recorder] does, and it writes to disk on its own thread. This
 * service exists only to hold the process in the foreground while that happens. Two things owning
 * the microphone is how a recorder ends up with two files and a person ends up with half a take.
 */
class RecordingService : Service() {

    companion object {
        private const val CHANNEL = "maha_recording"
        private const val ID = 1

        fun start(context: Context) {
            val i = Intent(context, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.rec_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val note: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.rec_title))
            .setContentText(getString(R.string.rec_text))
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID, note)
        }
        // START_NOT_STICKY: if the system kills this, do NOT resurrect it on its own. A recorder
        // that restarts itself without a person asking is a microphone that turns itself on, and
        // the audio it would capture belongs to whatever is happening in the room at that moment.
        return START_NOT_STICKY
    }
}
