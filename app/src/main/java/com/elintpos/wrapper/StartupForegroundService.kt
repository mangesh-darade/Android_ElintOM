package com.elintpos.wrapper

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class StartupForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var monitorRunnable: Runnable
    private val MONITOR_INTERVAL_MS = 5000L // 5 seconds

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        // Immediately bring the app to the front
        bringMainToFront()

        monitorRunnable = Runnable {
            if (!isAppInForeground()) {
                bringMainToFront()
            }
            // Reschedule the check
            handler.postDelayed(monitorRunnable, MONITOR_INTERVAL_MS)
        }

        // Start the monitoring loop after the initial launch
        handler.postDelayed(monitorRunnable, MONITOR_INTERVAL_MS)

        // Make the service sticky to ensure it's restarted if killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the runnable when the service is destroyed
        handler.removeCallbacks(monitorRunnable)
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = packageName
        for (appProcess in appProcesses) {
            if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && appProcess.processName == packageName) {
                return true
            }
        }
        return false
    }

    private fun startAsForeground() {
        val channelId = "autostart_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "App Monitor", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ElintPOS")
            .setContentText("App is running in the background.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    private fun bringMainToFront() {
        val launch = Intent(this, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launch)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
