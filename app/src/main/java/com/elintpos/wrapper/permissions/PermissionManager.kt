package com.elintpos.wrapper.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.elintpos.wrapper.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PermissionManager - Centralized permission handling
 * 
 * Handles all Android runtime permission requests and checks.
 * Provides a clean API for requesting and checking permissions.
 * 
 * Usage:
 * ```kotlin
 * val permissionManager = PermissionManager(activity)
 * val granted = permissionManager.hasPermission(Manifest.permission.CAMERA)
 * permissionManager.requestPermission(Manifest.permission.CAMERA) { granted ->
 *     // Handle result
 * }
 * ```
 */
class PermissionManager(private val activity: ComponentActivity) {
    
    companion object {
        private const val TAG = "PermissionManager"
    }
    
    private var pendingPermissionCallback: ((Map<String, Boolean>) -> Unit)? = null
    
    // Permission launchers
    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLogger.d("Notification permission: $granted", TAG)
        }
    
    private val storagePermissionLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            AppLogger.d("Storage permissions: allGranted=$allGranted", TAG)
        }
    
    private val genericPermissionsLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            try {
                pendingPermissionCallback?.invoke(permissions)
            } finally {
                pendingPermissionCallback = null
            }
        }
    
    /**
     * Checks if a permission is granted
     */
    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Checks multiple permissions and returns a map of results
     */
    fun checkPermissions(permissions: Array<String>): Map<String, Boolean> {
        return permissions.associateWith { hasPermission(it) }
    }
    
    /**
     * Requests a single permission
     */
    fun requestPermission(permission: String, callback: (Boolean) -> Unit) {
        if (hasPermission(permission)) {
            callback(true)
            return
        }
        
        val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            callback(granted)
        }
        launcher.launch(permission)
    }
    
    /**
     * Requests multiple permissions
     */
    fun requestPermissions(permissions: Array<String>, callback: (Map<String, Boolean>) -> Unit) {
        val alreadyGranted = checkPermissions(permissions)
        val allGranted = alreadyGranted.values.all { it }
        
        if (allGranted) {
            callback(alreadyGranted)
            return
        }
        
        pendingPermissionCallback = callback
        genericPermissionsLauncher.launch(permissions)
    }
    
    /**
     * Requests notification permission (Android 13+)
     */
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    /**
     * Requests storage permissions (handles different Android versions)
     */
    fun requestStoragePermissions() {
        val permissions = buildStoragePermissions()
        if (permissions.isNotEmpty()) {
            storagePermissionLauncher.launch(permissions.toTypedArray())
        }
    }
    
    /**
     * Requests all common permissions needed by the app
     */
    fun requestAllCommonPermissions(callback: (Map<String, Boolean>) -> Unit) {
        val permissions = buildCommonPermissions()
        requestPermissions(permissions.toTypedArray(), callback)
    }
    
    /**
     * Builds list of common permissions based on Android version
     */
    fun buildCommonPermissions(): List<String> {
        val list = mutableListOf<String>()
        
        // Camera & audio
        list.add(Manifest.permission.CAMERA)
        list.add(Manifest.permission.RECORD_AUDIO)
        
        // Location
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        
        // Bluetooth (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        
        // Storage / media (scoped by Android version)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0 - 12
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        // Nearby devices (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        
        return list
    }
    
    /**
     * Builds storage permissions based on Android version
     */
    private fun buildStoragePermissions(): List<String> {
        val list = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0 - 12
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        return list
    }
    
    /**
     * Parses permissions from JSON array string
     */
    fun parsePermissionsJson(permsJson: String?): List<String> {
        return try {
            if (permsJson.isNullOrBlank()) return buildCommonPermissions()
            
            val arr = org.json.JSONArray(permsJson)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val p = arr.optString(i)
                if (p.isNotBlank()) out.add(p)
            }
            if (out.isEmpty()) buildCommonPermissions() else out
        } catch (e: Exception) {
            AppLogger.e("Error parsing permissions JSON", e, TAG)
            buildCommonPermissions()
        }
    }
    
    /**
     * Checks if all required permissions are granted
     */
    fun hasAllRequiredPermissions(): Boolean {
        val permissions = buildCommonPermissions()
        return permissions.all { hasPermission(it) }
    }
    
    /**
     * Gets list of denied permissions
     */
    fun getDeniedPermissions(): List<String> {
        val permissions = buildCommonPermissions()
        return permissions.filter { !hasPermission(it) }
    }
    
    /**
     * Gets list of granted permissions
     */
    fun getGrantedPermissions(): List<String> {
        val permissions = buildCommonPermissions()
        return permissions.filter { hasPermission(it) }
    }
}

