package com.elintpos.wrapper

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ElintApp - Main Application Class
 * 
 * Purpose: Custom Application class that provides crash recovery and auto-restart functionality
 * for the ElintPOS application. This ensures the POS system remains operational even after
 * unexpected crashes, which is critical for retail/business environments.
 * 
 * Key Features:
 * - Automatic crash detection and logging
 * - Auto-restart after crash (1.2 seconds delay)
 * - Detailed crash reports with device and system information
 * - Crash log persistence for debugging
 * 
 * Usage: Automatically instantiated by Android when the app starts (declared in AndroidManifest.xml)
 */
class ElintApp : Application() {

	/**
	 * AutoReplyPrint SDK instance
	 * Initialized once when application starts
	 */
	val autoReplyPrint: com.elintpos.wrapper.printer.vendor.AutoReplyPrint by lazy {
		com.elintpos.wrapper.printer.vendor.AutoReplyPrint(this)
	}

	/**
	 * Called when the application is starting, before any activity, service, or receiver objects
	 * have been created.
	 * 
	 * Use: Initializes the global crash handler to ensure app resilience
	 */
	override fun onCreate() {
		super.onCreate()
		// Install crash handler to automatically restart app on fatal errors
		installCrashRestartHandler()
		
		// Initialize AutoReplyPrint SDK if available
		if (autoReplyPrint.isAvailable()) {
			autoReplyPrint.initialize()
		}
	}

	/**
	 * Installs a global uncaught exception handler to catch all app crashes
	 * 
	 * How it works:
	 * 1. Saves the default system exception handler
	 * 2. Replaces it with custom handler that:
	 *    - Logs the crash details
	 *    - Writes crash info to file for later analysis
	 *    - Schedules app restart using AlarmManager
	 *    - Delegates to system handler for proper crash reporting
	 * 
	 * Use Case: Ensures POS terminals don't stay crashed - critical for business continuity
	 */
	private fun installCrashRestartHandler() {
		// Get the default exception handler (system's crash handler)
		val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
		
		// Set our custom exception handler
		Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
			try {
				// Log the crash to Android's logcat
				Log.e("ElintApp", "Uncaught exception, scheduling restart", throwable)
				
				// Write detailed crash information to file
				writeCrashToFile(throwable)
				
				// Schedule the app to restart after 1.2 seconds
				scheduleRelaunch()
			} catch (_: Exception) {
				// Silently fail if crash handling itself fails (avoid infinite loop)
			}
			
			// Delegate to system/default handler so crash is properly reported to Play Console
			defaultHandler?.uncaughtException(thread, throwable)
		}
	}

	/**
	 * Writes detailed crash information to a file for debugging and analysis
	 * 
	 * Information Captured:
	 * - App package name
	 * - Crash timestamp
	 * - Android version and API level
	 * - Device manufacturer and model
	 * - Full stack trace
	 * 
	 * File Location: app's internal storage /crash/ directory
	 * File Format: crash-YYYYMMDD-HHMMSS.log
	 * 
	 * Use: Developers can retrieve these logs via JavaScript bridge (listCrashLogs, readCrashLog)
	 * to diagnose issues in production environments
	 * 
	 * @param throwable The exception that caused the crash
	 */
	private fun writeCrashToFile(throwable: Throwable) {
		try {
			// Convert stack trace to string
			val sw = StringWriter()
			throwable.printStackTrace(PrintWriter(sw))
			
			// Build comprehensive crash report
			val text = buildString {
				append("App: com.elintpos.wrapper\n")
				append("Time: ")
				append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
				append('\n')
				append("Android: ")
				append(android.os.Build.VERSION.RELEASE)
				append(" (API ")
				append(android.os.Build.VERSION.SDK_INT)
				append(")\nDevice: ")
				append(android.os.Build.MANUFACTURER)
				append(' ')
				append(android.os.Build.MODEL)
				append("\n\n")
				append(sw.toString())
			}
			
			// Create crash directory if it doesn't exist
			val dir = File(filesDir, "crash")
			if (!dir.exists()) dir.mkdirs()
			
			// Generate unique filename with timestamp
			val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
			val file = File(dir, name)
			
			// Write crash report to file
			file.writeText(text)
			
			// Save path to latest crash for quick access
			File(dir, "latest.txt").writeText(file.absolutePath)
		} catch (_: Exception) {
			// Silently fail if file writing fails (avoid secondary crash)
		}
	}

	/**
	 * Schedules the app to restart after a crash using AlarmManager
	 * 
	 * How it works:
	 * 1. Creates an Intent to launch MainActivity
	 * 2. Wraps it in a PendingIntent for delayed execution
	 * 3. Uses AlarmManager to trigger the restart after 1200ms (1.2 seconds)
	 * 4. Uses setExactAndAllowWhileIdle to ensure restart even in Doze mode
	 * 
	 * Why 1.2 seconds?
	 * - Gives system time to clean up crashed process
	 * - Short enough for quick recovery
	 * - Long enough to avoid immediate re-crash
	 * 
	 * Use Case: Automatic recovery for unattended POS terminals
	 */
	private fun scheduleRelaunch() {
		// Create intent to restart MainActivity
		val intent = Intent(this, MainActivity::class.java).apply {
			// Clear any existing activity stack and create new task
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
		}
		
		// Create PendingIntent for delayed execution
		val pi = PendingIntent.getActivity(
			this,
			9999, // Request code for identification
			intent,
			// Use appropriate flags based on Android version
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				// Android 12+ requires explicit mutability declaration
				PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
			} else {
				PendingIntent.FLAG_CANCEL_CURRENT
			}
		)
		
		// Get AlarmManager system service
		val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
		
		// Schedule restart for 1200ms (1.2 seconds) from now
		val triggerAt = System.currentTimeMillis() + 1200
		
		// Use appropriate alarm method based on Android version
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			// Android 6.0+: Use setExactAndAllowWhileIdle to work even in Doze mode
			am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
		} else {
			// Pre-Android 6.0: Use setExact for precise timing
			am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
		}
	}
}


