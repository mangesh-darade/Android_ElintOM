package com.elintpos.wrapper

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * MyDeviceAdminReceiver - Device Administrator Receiver
 * 
 * Purpose: Handles device administrator events for the ElintPOS app. Device admin privileges
 * are required to enable Kiosk Mode (Lock Task Mode) which locks the device to only run
 * this app - essential for dedicated POS terminals.
 * 
 * Device Admin Capabilities:
 * - Enable Lock Task Mode (Kiosk Mode) to prevent users from exiting the app
 * - Restrict device functionality to POS operations only
 * - Prevent unauthorized access to device settings
 * 
 * How to Enable:
 * 1. User must manually grant Device Admin permission in device settings
 * 2. Or use MDM (Mobile Device Management) to automatically grant permission
 * 3. Once enabled, app can use startLockTask() to enter kiosk mode
 * 
 * Security Note:
 * - Device admin must be explicitly disabled before app can be uninstalled
 * - This prevents unauthorized removal of the POS app
 * 
 * Use Case: Retail stores, restaurants, or businesses using dedicated Android tablets
 * as POS terminals that should only run this app
 */
class MyDeviceAdminReceiver : DeviceAdminReceiver() {
	
	/**
	 * Called when device administrator is enabled for this app
	 * 
	 * This happens when:
	 * - User grants Device Admin permission in Settings
	 * - MDM policy automatically enables it
	 * 
	 * Once enabled, the app can use Lock Task Mode (kiosk mode)
	 * 
	 * @param context Application context
	 * @param intent The Intent that triggered this callback
	 */
	override fun onEnabled(context: Context, intent: Intent) {
		// Notify user that device admin has been enabled
		Toast.makeText(context, "Device Admin enabled", Toast.LENGTH_SHORT).show()
	}

	/**
	 * Called when device administrator is disabled for this app
	 * 
	 * This happens when:
	 * - User manually revokes Device Admin permission in Settings
	 * - MDM policy removes the permission
	 * 
	 * After this, Lock Task Mode (kiosk mode) will no longer work
	 * 
	 * @param context Application context
	 * @param intent The Intent that triggered this callback
	 */
	override fun onDisabled(context: Context, intent: Intent) {
		// Notify user that device admin has been disabled
		Toast.makeText(context, "Device Admin disabled", Toast.LENGTH_SHORT).show()
	}
}


