package com.csc.translate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.csc.translate.CscApp
import com.csc.translate.MainActivity
import com.csc.translate.R
import com.csc.translate.audio.SystemAudio

class MicCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val system = intent?.getBooleanExtra(EXTRA_SYSTEM, false) == true
        val notification = buildNotification(system)
        val type = when {
            system -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }
        if (Build.VERSION.SDK_INT >= 29 && type != 0) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!system) return START_STICKY
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }
        if (data == null || !SystemAudio.attach(this, code, data)) {
            SystemAudio.fail()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as? CscApp)?.container?.sessions?.abort(getString(R.string.app_exited))
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.live_notification_text)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(system: Boolean): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (system) R.string.live_notification_system else R.string.live_notification_text
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.live_notification_title))
            .setContentText(getString(text))
            .setOngoing(true)
            .setContentIntent(launch)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "live_translate"
        private const val NOTIFICATION_ID = 41
        private const val EXTRA_SYSTEM = "system"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context) {
            val intent = Intent(context, MicCaptureService::class.java)
            context.startForegroundService(intent)
        }

        fun startSystem(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MicCaptureService::class.java)
                .putExtra(EXTRA_SYSTEM, true)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MicCaptureService::class.java))
        }
    }
}
