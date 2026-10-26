package com.elintpos.wrapper.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized preferences manager for the application
 * 
 * Provides type-safe access to SharedPreferences and prevents
 * typos in preference keys.
 */
class PreferencesManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, 
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "settings"
        
        // Setup preferences
        private const val KEY_SETUP_COMPLETED = "is_setup_completed"
        private const val KEY_CUSTOMER_NAME = "customer_name"
        private const val KEY_MOBILE_NUMBER = "mobile_number"
        private const val KEY_SELECTED_DOMAIN = "selected_domain"
        private const val KEY_SELECTED_DOMAIN_NAME = "selected_domain_name"
        private const val KEY_FOOTER_TEXT = "footer_text"
        private const val KEY_PRINTER_CONFIGURED = "printer_configured"
        
        // Session preferences
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_LAST_ACTIVITY_TIME = "last_activity_time"
        private const val KEY_SESSION_TIMEOUT_MS = "session_timeout_ms"
        
        // App behavior preferences
        private const val KEY_AUTO_START_ENABLED = "auto_start_enabled"
        private const val KEY_KIOSK_ENABLED = "kiosk_enabled"
        
        // URL preferences
        private const val KEY_APP_URL = "app_url"
        private const val KEY_SETTINGS_BACKUP_JSON = "settings_backup_json"
        
        // UI preferences
        private const val KEY_SHOW_PRINT_DIALOG = "show_print_dialog"
        private const val KEY_SETTINGS_BUTTON_X = "settings_button_x"
        private const val KEY_SETTINGS_BUTTON_Y = "settings_button_y"
        private const val KEY_ADMIN_PIN = "admin_pin"
        private const val KEY_PRINT_TOP_GAP = "print_top_gap_dots"
        private const val KEY_PRINT_BOTTOM_GAP = "print_bottom_gap_dots"
        private const val KEY_PRINT_CUT_MODE = "print_cut_mode"
        private const val KEY_PRINT_DENSITY = "print_density"
        const val DEFAULT_PRINT_TOP_GAP = 8
        const val DEFAULT_PRINT_BOTTOM_GAP = 20
        const val MAX_PRINT_TOP_GAP = 48
        const val MAX_PRINT_BOTTOM_GAP = 120
    }
    
    // Setup preferences
    var isSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply()
    
    var customerName: String?
        get() = prefs.getString(KEY_CUSTOMER_NAME, null)
        set(value) = prefs.edit().putString(KEY_CUSTOMER_NAME, value).apply()
    
    var mobileNumber: String?
        get() = prefs.getString(KEY_MOBILE_NUMBER, null)
        set(value) = prefs.edit().putString(KEY_MOBILE_NUMBER, value).apply()
    
    var selectedDomain: String?
        get() = prefs.getString(KEY_SELECTED_DOMAIN, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_DOMAIN, value).apply()
    
    var selectedDomainName: String?
        get() = prefs.getString(KEY_SELECTED_DOMAIN_NAME, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_DOMAIN_NAME, value).apply()
    
    var footerText: String?
        get() = prefs.getString(KEY_FOOTER_TEXT, null)
        set(value) = prefs.edit().putString(KEY_FOOTER_TEXT, value).apply()
    
    var isPrinterConfigured: Boolean
        get() = prefs.getBoolean(KEY_PRINTER_CONFIGURED, false)
        set(value) = prefs.edit().putBoolean(KEY_PRINTER_CONFIGURED, value).apply()
    
    // Session preferences
    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()
    
    var lastActivityTime: Long
        get() = prefs.getLong(KEY_LAST_ACTIVITY_TIME, System.currentTimeMillis())
        set(value) = prefs.edit().putLong(KEY_LAST_ACTIVITY_TIME, value).apply()
    
    var sessionTimeoutMs: Long
        get() = prefs.getLong(KEY_SESSION_TIMEOUT_MS, com.elintpos.wrapper.config.AppConfig.Session.DEFAULT_TIMEOUT_MS)
        set(value) = prefs.edit().putLong(KEY_SESSION_TIMEOUT_MS, value).apply()
    
    // App behavior preferences
    var autoStartEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_ENABLED, value).apply()
    
    var kioskEnabled: Boolean
        get() = prefs.getBoolean(KEY_KIOSK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_KIOSK_ENABLED, value).apply()
    
    // URL preferences
    var appUrl: String?
        get() = prefs.getString(KEY_APP_URL, null)
        set(value) = prefs.edit().putString(KEY_APP_URL, value).apply()
    
    var settingsBackupJson: String?
        get() = prefs.getString(KEY_SETTINGS_BACKUP_JSON, null)
        set(value) = prefs.edit().putString(KEY_SETTINGS_BACKUP_JSON, value).apply()
    
    // UI preferences
    var showPrintDialog: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PRINT_DIALOG, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_PRINT_DIALOG, value).apply()
    
    var settingsButtonX: Float
        get() = prefs.getFloat(KEY_SETTINGS_BUTTON_X, -1f)
        set(value) = prefs.edit().putFloat(KEY_SETTINGS_BUTTON_X, value).apply()
    
    var settingsButtonY: Float
        get() = prefs.getFloat(KEY_SETTINGS_BUTTON_Y, -1f)
        set(value) = prefs.edit().putFloat(KEY_SETTINGS_BUTTON_Y, value).apply()
    
    var adminPin: String
        get() = prefs.getString(KEY_ADMIN_PIN, "1234") ?: "1234"
        set(value) = prefs.edit().putString(KEY_ADMIN_PIN, value).apply()

    var printTopGapDots: Int
        get() = prefs.getInt(KEY_PRINT_TOP_GAP, DEFAULT_PRINT_TOP_GAP).coerceIn(0, MAX_PRINT_TOP_GAP)
        set(value) = prefs.edit().putInt(KEY_PRINT_TOP_GAP, value.coerceIn(0, MAX_PRINT_TOP_GAP)).apply()

    var printBottomGapDots: Int
        get() = prefs.getInt(KEY_PRINT_BOTTOM_GAP, DEFAULT_PRINT_BOTTOM_GAP).coerceIn(0, MAX_PRINT_BOTTOM_GAP)
        set(value) = prefs.edit().putInt(KEY_PRINT_BOTTOM_GAP, value.coerceIn(0, MAX_PRINT_BOTTOM_GAP)).apply()

    var printCutMode: String
        get() = prefs.getString(KEY_PRINT_CUT_MODE, "full_cut") ?: "full_cut"
        set(value) = prefs.edit().putString(KEY_PRINT_CUT_MODE, value).apply()

    /** TecMI K8 print darkness, 1..4 (ICOD setPrintDensity). 0 = leave printer default. */
    var printDensity: Int
        get() = prefs.getInt(KEY_PRINT_DENSITY, 0).coerceIn(0, 4)
        set(value) = prefs.edit().putInt(KEY_PRINT_DENSITY, value.coerceIn(0, 4)).apply()
    
    /**
     * Clear all preferences (use with caution)
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
    
    /**
     * Clear session-related preferences only
     */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_IS_LOGGED_IN)
            .remove(KEY_LAST_ACTIVITY_TIME)
            .apply()
    }
    
    /**
     * Get the base URL from saved domain preference or use default
     */
    fun getBaseUrl(): String {
        val savedDomain = selectedDomain
        return if (!savedDomain.isNullOrBlank()) {
            if (savedDomain.startsWith("http://") || savedDomain.startsWith("https://")) {
                savedDomain.trimEnd('/')
            } else {
                "https://${savedDomain.trimEnd('/')}/"
            }
        } else {
            com.elintpos.wrapper.config.AppConfig.Network.DEFAULT_BASE_URL
        }
    }
    
    /**
     * Get the base domain (without scheme or path) from saved preference or use default
     */
    fun getBaseDomain(): String {
        val savedDomain = selectedDomain
        return if (!savedDomain.isNullOrBlank()) {
            try {
                val candidate = if (savedDomain.startsWith("http://") || savedDomain.startsWith("https://")) {
                    android.net.Uri.parse(savedDomain).host
                } else {
                    android.net.Uri.parse("https://$savedDomain").host
                }
                candidate ?: savedDomain.replace(Regex("^https?://"), "").split('/')[0].trimEnd('/')
            } catch (_: Exception) {
                savedDomain.replace(Regex("^https?://"), "").split('/')[0].trimEnd('/')
            }
        } else {
            com.elintpos.wrapper.config.AppConfig.Network.DEFAULT_DOMAIN
        }
    }
}

