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

class ElintApp : Application() {

	override fun onCreate() {
		super.onCreate()
		installCrashRestartHandler()
	}

	private fun installCrashRestartHandler() {
		val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
			try {
				Log.e("ElintApp", "Uncaught exception, scheduling restart", throwable)
				writeCrashToFile(throwable)
				scheduleRelaunch()
			} catch (_: Exception) {}
			// Delegate to system/default so crash is reported
			defaultHandler?.uncaughtException(thread, throwable)
		}
	}

	private fun writeCrashToFile(throwable: Throwable) {
		try {
			val sw = StringWriter()
			throwable.printStackTrace(PrintWriter(sw))
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
			val dir = File(filesDir, "crash")
			if (!dir.exists()) dir.mkdirs()
			val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
			val file = File(dir, name)
			file.writeText(text)
			// Save last path
			File(dir, "latest.txt").writeText(file.absolutePath)
		} catch (_: Exception) {}
	}

	private fun scheduleRelaunch() {
		val intent = Intent(this, MainActivity::class.java).apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
		}
		val pi = PendingIntent.getActivity(
			this,
			9999,
			intent,
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
			} else {
				PendingIntent.FLAG_CANCEL_CURRENT
			}
		)
		val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
		val triggerAt = System.currentTimeMillis() + 1200
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
		} else {
			am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
		}
	}
}


