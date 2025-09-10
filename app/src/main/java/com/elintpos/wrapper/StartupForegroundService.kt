package com.elintpos.wrapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class StartupForegroundService : Service() {

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		startAsForeground()
		bringMainToFront()
		stopSelf()
		return START_NOT_STICKY
	}

	private fun startAsForeground() {
		val channelId = "autostart_channel"
		val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(channelId, "Auto Start", NotificationManager.IMPORTANCE_MIN)
			nm.createNotificationChannel(channel)
		}
		val pendingIntent = PendingIntent.getActivity(
			this,
			0,
			Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
		)
		val notification: Notification = NotificationCompat.Builder(this, channelId)
			.setContentTitle("Starting POS")
			.setContentText("Bringing app to front")
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


