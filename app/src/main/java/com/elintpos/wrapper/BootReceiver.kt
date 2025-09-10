package com.elintpos.wrapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.SharedPreferences

class BootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val action = intent.action ?: return
		if (action == Intent.ACTION_BOOT_COMPLETED ||
			action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
			action == Intent.ACTION_USER_PRESENT ||
			action == Intent.ACTION_USER_UNLOCKED ||
			action == Intent.ACTION_MY_PACKAGE_REPLACED) {
			// Respect user preference: only auto-start if enabled (default true)
			val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
			val enabled = prefs.getBoolean("auto_start_enabled", true)
			if (!enabled) return

			// Start foreground service to reliably bring app to front on POS devices
			val svc = Intent(context, StartupForegroundService::class.java)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				context.startForegroundService(svc)
			} else {
				context.startService(svc)
			}
		}
	}
}


