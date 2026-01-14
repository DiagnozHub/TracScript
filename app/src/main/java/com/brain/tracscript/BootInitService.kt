package com.brain.tracscript

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brain.tracscript.core.TracScriptApp

class BootInitService : Service() {

    companion object {
        private const val CH_ID = "boot_init"
        private const val NOTIF_ID = 2001
        private const val ACTION = "com.brain.tracscript.BOOT_INIT"

        fun start(context: Context) {
            val i = Intent(context, BootInitService::class.java).apply { action = ACTION }
            ContextCompat.startForegroundService(context, i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        startForeground(NOTIF_ID, buildNotif())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION) {
            val app = application as TracScriptApp
            app.pluginRuntime.attachAllOnce()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotif(): Notification =
        NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TracScript")
            .setContentText("Starting plugins…")
            .setOngoing(true)
            .build()

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "Boot init", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }
}
