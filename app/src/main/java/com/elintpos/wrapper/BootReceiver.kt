package com.elintpos.wrapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.SharedPreferences

/**
 * BootReceiver - Auto-Start Broadcast Receiver
 * 
 * Purpose: Automatically starts the ElintPOS app when the device boots up or when
 * specific system events occur. This is essential for dedicated POS terminals that
 * should launch the app immediately after device startup.
 * 
 * Triggers:
 * - ACTION_BOOT_COMPLETED: Device has finished booting
 * - ACTION_LOCKED_BOOT_COMPLETED: Device has booted but still locked (Android 7.0+)
 * - ACTION_USER_PRESENT: User unlocked the device
 * - ACTION_USER_UNLOCKED: User's credential-encrypted storage is unlocked
 * - ACTION_MY_PACKAGE_REPLACED: App was updated
 * 
 * Configuration:
 * - Auto-start can be enabled/disabled via SharedPreferences ("auto_start_enabled")
 * - Default: enabled (true)
 * - Can be controlled from web interface via JavaScript bridge
 * 
 * Permissions Required:
 * - RECEIVE_BOOT_COMPLETED (declared in AndroidManifest.xml)
 * 
 * Use Case: Unattended POS terminals that must always run the app
 */
class BootReceiver : BroadcastReceiver() {
	
	/**
	 * Called when a broadcast is received
	 * 
	 * Checks if the broadcast is one of the supported system events, then starts
	 * the app via StartupForegroundService if auto-start is enabled
	 * 
	 * @param context Application context
	 * @param intent The Intent being received containing the action
	 */
	override fun onReceive(context: Context, intent: Intent) {
		// Get the action from the broadcast intent
		val action = intent.action ?: return
		
		// Check if this is one of the system events we want to respond to
		if (action == Intent.ACTION_BOOT_COMPLETED ||
			action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
			action == Intent.ACTION_USER_PRESENT ||
			action == Intent.ACTION_USER_UNLOCKED ||
			action == Intent.ACTION_MY_PACKAGE_REPLACED) {
			
			// Check user preference: only auto-start if enabled
			// This allows users to disable auto-start if needed
			val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
			val enabled = prefs.getBoolean("auto_start_enabled", true) // Default: enabled
			if (!enabled) return // Exit if auto-start is disabled

			// Start foreground service to reliably bring app to front on POS devices
			// Using a service ensures the app starts even with background restrictions
			val svc = Intent(context, StartupForegroundService::class.java)
			
			// Use appropriate method based on Android version
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				// Android 8.0+: Must use startForegroundService for background starts
				context.startForegroundService(svc)
			} else {
				// Pre-Android 8.0: Use regular startService
				context.startService(svc)
			}
		}
	}
}


