package com.elintpos.wrapper

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * StartupForegroundService - App Monitoring and Auto-Launch Service
 * 
 * Purpose: Ensures the ElintPOS app stays in the foreground on POS terminals.
 * This service monitors the app state and automatically brings it back to the
 * foreground if it goes to the background.
 * 
 * Key Features:
 * - Runs as a foreground service (survives background restrictions)
 * - Monitors app state every 5 seconds
 * - Automatically brings app to foreground if backgrounded
 * - Shows persistent notification (required for foreground services)
 * - Sticky service (Android restarts it if killed)
 * - On boot: Checks printer configuration and launches PrinterSetupActivity if not configured
 * 
 * Triggered By:
 * - BootReceiver on device boot
 * - System events (user unlock, app update)
 * 
 * Use Case: Dedicated POS terminals that must always show the app
 */
class StartupForegroundService : Service() {

    /** Handler for posting delayed tasks on the main thread */
    private val handler = Handler(Looper.getMainLooper())
    
    /** Runnable that checks if app is in foreground and brings it back if needed */
    private lateinit var monitorRunnable: Runnable
    
    /** How often to check if app is in foreground (5 seconds) */
    private val MONITOR_INTERVAL_MS = 5000L // 5 seconds
    
    /** SharedPreferences to check printer configuration */
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
    }
    
    /**
     * Check if printer is configured
     */
    private fun isPrinterConfigured(): Boolean {
        return prefs.getBoolean("printer_configured", false)
    }

    /**
     * Called when the service is started
     * 
     * Flow:
     * 1. Start as foreground service (shows notification)
     * 2. Check printer configuration
     * 3. If printer NOT configured: Launch PrinterSetupActivity
     * 4. If printer configured: Launch MainActivity
     * 5. Set up monitoring loop to check app state every 5 seconds
     * 6. Return START_STICKY so Android restarts service if killed
     * 
     * @param intent The Intent supplied to startService()
     * @param flags Additional data about the start request
     * @param startId Unique ID representing this specific request to start
     * @return START_STICKY to ensure service is restarted if killed
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service (required for Android 8.0+)
        startAsForeground()
        
        // Check printer configuration and launch appropriate activity
        bringAppToFront()

        // Create monitoring runnable that checks app state periodically
        monitorRunnable = Runnable {
            // Check if app is in foreground
            if (!isAppInForeground()) {
                // App went to background - bring it back to front
                // Once printer is configured, always bring MainActivity
                bringAppToFront()
            }
            // Reschedule the check after MONITOR_INTERVAL_MS
            handler.postDelayed(monitorRunnable, MONITOR_INTERVAL_MS)
        }

        // Start the monitoring loop after the initial launch
        handler.postDelayed(monitorRunnable, MONITOR_INTERVAL_MS)

        // Make the service sticky to ensure it's restarted if killed by system
        return START_STICKY
    }

    /**
     * Called when the service is being destroyed
     * 
     * Cleanup: Removes the monitoring runnable from the handler to prevent memory leaks
     */
    override fun onDestroy() {
        super.onDestroy()
        // Stop the runnable when the service is destroyed
        handler.removeCallbacks(monitorRunnable)
    }

    /**
     * Checks if the app is currently in the foreground
     * 
     * Method: Queries ActivityManager for running processes and checks if our app's
     * process has IMPORTANCE_FOREGROUND importance level
     * 
     * @return true if app is in foreground, false otherwise
     */
    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = packageName
        
        // Check all running processes
        for (appProcess in appProcesses) {
            // If our process is in foreground, return true
            if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && appProcess.processName == packageName) {
                return true
            }
        }
        return false
    }

    /**
     * Starts the service as a foreground service with a persistent notification
     * 
     * Required for Android 8.0+: Foreground services must show a notification
     * 
     * Notification Features:
     * - Low importance (IMPORTANCE_MIN) - minimal intrusion
     * - Ongoing (can't be dismissed by user)
     * - Tapping opens appropriate activity (PrinterSetupActivity or MainActivity)
     * 
     * @see <a href="https://developer.android.com/guide/components/foreground-services">Foreground Services</a>
     */
    private fun startAsForeground() {
        val channelId = "autostart_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "App Monitor", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(channel)
        }
        
        // Determine which activity to open based on printer configuration
        val targetActivity = if (isPrinterConfigured()) {
            MainActivity::class.java
        } else {
            PrinterSetupActivity::class.java
        }
        
        // Create PendingIntent to open appropriate activity when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, targetActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        
        // Build the notification
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ElintPOS")
            .setContentText("App is running in the background.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Can't be dismissed
            .build()
        
        // Start as foreground service with notification ID 1001
        startForeground(1001, notification)
    }

    /**
     * Brings the appropriate activity to the foreground based on printer configuration
     * 
     * Flow:
     * - If printer NOT configured: Launch PrinterSetupActivity
     * - If printer configured: Launch MainActivity
     * 
     * Flags:
     * - FLAG_ACTIVITY_NEW_TASK: Start activity in a new task
     * - FLAG_ACTIVITY_CLEAR_TOP: Clear all activities above target activity
     * 
     * Effect: Appropriate activity becomes visible and focused
     */
    private fun bringAppToFront() {
        val targetActivity = if (isPrinterConfigured()) {
            MainActivity::class.java
        } else {
            PrinterSetupActivity::class.java
        }
        
        val launch = Intent(this, targetActivity)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launch)
    }

    /**
     * This is a started service, not a bound service
     * @return null because we don't support binding
     */
    override fun onBind(intent: Intent?): IBinder? = null
}
