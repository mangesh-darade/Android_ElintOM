package com.elintpos.wrapper.printer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.json.JSONArray

/**
 * Comprehensive printer configuration manager that handles all printer settings,
 * profiles, and configurations for different printer types and SDKs.
 */
class PrinterConfigManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "printer_config"
        private const val KEY_DEFAULT_PROFILE = "default_profile"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_LAST_USED = "last_used"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_PRINT_QUEUE = "print_queue"
        
        // Printer types
        const val TYPE_BLUETOOTH = "bluetooth"
        const val TYPE_USB = "usb"
        const val TYPE_LAN = "lan"
        const val TYPE_EPSON = "epson"
        const val TYPE_XPRINTER = "xprinter"
        const val TYPE_VENDOR = "vendor"
        
        // Paper sizes
        const val PAPER_58MM = 384
        const val PAPER_80MM = 576
        const val PAPER_112MM = 832
        
        // Default configurations
        private val DEFAULT_BT_CONFIG = PrinterConfig(
            type = TYPE_BLUETOOTH,
            name = "Bluetooth Printer",
            enabled = true,
            paperWidth = PAPER_80MM,
            leftMargin = 0,
            rightMargin = 0,
            lineSpacing = 30,
            widthMultiplier = 0,
            heightMultiplier = 0,
            charset = "UTF-8",
            autoConnect = false,
            timeout = 5000
        )
        
        private val DEFAULT_USB_CONFIG = PrinterConfig(
            type = TYPE_USB,
            name = "USB Printer",
            enabled = true,
            paperWidth = PAPER_80MM,
            leftMargin = 0,
            rightMargin = 0,
            lineSpacing = 30,
            widthMultiplier = 0,
            heightMultiplier = 0,
            charset = "UTF-8",
            autoConnect = true,
            timeout = 3000
        )
        
        private val DEFAULT_LAN_CONFIG = PrinterConfig(
            type = TYPE_LAN,
            name = "LAN Printer",
            enabled = true,
            paperWidth = PAPER_80MM,
            leftMargin = 0,
            rightMargin = 0,
            lineSpacing = 30,
            widthMultiplier = 0,
            heightMultiplier = 0,
            charset = "UTF-8",
            autoConnect = true,
            timeout = 5000,
            connectionParams = mapOf("ip" to "192.168.1.100", "port" to "9100")
        )
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val profiles = mutableMapOf<String, PrinterConfig>()
    
    init {
        loadProfiles()
        ensureDefaultProfiles()
    }
    
    /**
     * Data class representing a complete printer configuration
     */
    data class PrinterConfig(
        val id: String = java.util.UUID.randomUUID().toString(),
        val type: String,
        val name: String,
        val enabled: Boolean = true,
        val paperWidth: Int = PAPER_80MM,
        val leftMargin: Int = 0,
        val rightMargin: Int = 0,
        val lineSpacing: Int = 30,
        val widthMultiplier: Int = 0,
        val heightMultiplier: Int = 0,
        val charset: String = "UTF-8",
        val autoConnect: Boolean = false,
        val timeout: Int = 5000,
        val connectionParams: Map<String, String> = emptyMap(),
        val advancedSettings: Map<String, Any> = emptyMap(),
        val lastUsed: Long = System.currentTimeMillis(),
        val isDefault: Boolean = false
    ) {
        fun toJson(): JSONObject {
            val json = JSONObject()
            json.put("id", id)
            json.put("type", type)
            json.put("name", name)
            json.put("enabled", enabled)
            json.put("paperWidth", paperWidth)
            json.put("leftMargin", leftMargin)
            json.put("rightMargin", rightMargin)
            json.put("lineSpacing", lineSpacing)
            json.put("widthMultiplier", widthMultiplier)
            json.put("heightMultiplier", heightMultiplier)
            json.put("charset", charset)
            json.put("autoConnect", autoConnect)
            json.put("timeout", timeout)
            json.put("lastUsed", lastUsed)
            json.put("isDefault", isDefault)
            
            // Connection parameters
            val connParams = JSONObject()
            connectionParams.forEach { (key, value) -> connParams.put(key, value) }
            json.put("connectionParams", connParams)
            
            // Advanced settings
            val advSettings = JSONObject()
            advancedSettings.forEach { (key, value) -> 
                try {
                    when (value) {
                        is String -> advSettings.put(key, value)
                        is Int -> advSettings.put(key, value)
                        is Boolean -> advSettings.put(key, value)
                        is Double -> advSettings.put(key, value)
                        is Long -> advSettings.put(key, value)
                        is Float -> advSettings.put(key, value.toDouble())
                        is Number -> advSettings.put(key, value.toDouble())
                        null -> advSettings.put(key, JSONObject.NULL)
                        else -> advSettings.put(key, value.toString())
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PrinterConfigManager", "Error serializing advanced setting $key: ${e.message}")
                    advSettings.put(key, value.toString())
                }
            }
            json.put("advancedSettings", advSettings)
            
            return json
        }
        
        companion object {
            fun fromJson(json: JSONObject): PrinterConfig {
                val connParams = mutableMapOf<String, String>()
                val connParamsJson = json.optJSONObject("connectionParams")
                connParamsJson?.let { 
                    it.keys().forEach { key -> connParams[key] = it.getString(key) }
                }
                
                val advSettings = mutableMapOf<String, Any>()
                val advSettingsJson = json.optJSONObject("advancedSettings")
                advSettingsJson?.let {
                    it.keys().forEach { key -> 
                        advSettings[key] = it.get(key)
                    }
                }
                
                return PrinterConfig(
                    id = json.optString("id", java.util.UUID.randomUUID().toString()),
                    type = json.getString("type"),
                    name = json.getString("name"),
                    enabled = json.optBoolean("enabled", true),
                    paperWidth = json.optInt("paperWidth", PAPER_80MM),
                    leftMargin = json.optInt("leftMargin", 0),
                    rightMargin = json.optInt("rightMargin", 0),
                    lineSpacing = json.optInt("lineSpacing", 30),
                    widthMultiplier = json.optInt("widthMultiplier", 0),
                    heightMultiplier = json.optInt("heightMultiplier", 0),
                    charset = json.optString("charset", "UTF-8"),
                    autoConnect = json.optBoolean("autoConnect", false),
                    timeout = json.optInt("timeout", 5000),
                    connectionParams = connParams,
                    advancedSettings = advSettings,
                    lastUsed = json.optLong("lastUsed", System.currentTimeMillis()),
                    isDefault = json.optBoolean("isDefault", false)
                )
            }
        }
    }
    
    /**
     * Generate a unique ID for printer configurations
     */
    private fun generateId(): String {
        return "printer_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
    
    /**
     * Load all printer profiles from SharedPreferences
     */
    private fun loadProfiles() {
        try {
            val profilesJson = prefs.getString(KEY_PROFILES, "{}")
            val json = JSONObject(profilesJson ?: "{}")
            
            json.keys().forEach { key ->
                val profileJson = json.getJSONObject(key)
                val config = PrinterConfig.fromJson(profileJson)
                profiles[key] = config
            }
        } catch (e: Exception) {
            // If loading fails, start with empty profiles
            profiles.clear()
        }
    }
    
    /**
     * Save all printer profiles to SharedPreferences
     */
    private fun saveProfiles() {
        try {
            val json = JSONObject()
            profiles.forEach { (key, config) ->
                try {
                    json.put(key, config.toJson())
                } catch (e: Exception) {
                    android.util.Log.e("PrinterConfigManager", "Error serializing profile $key: ${e.message}", e)
                    // Skip this profile and continue with others
                }
            }
            val success = prefs.edit().putString(KEY_PROFILES, json.toString()).commit()
            if (!success) {
                android.util.Log.e("PrinterConfigManager", "Failed to save profiles to SharedPreferences")
            }
        } catch (e: Exception) {
            android.util.Log.e("PrinterConfigManager", "Error saving profiles: ${e.message}", e)
            throw e // Re-throw to be caught by saveProfile
        }
    }
    
    /**
     * Ensure default profiles exist for each printer type
     */
    private fun ensureDefaultProfiles() {
        val defaultConfigs = mapOf(
            "default_bt" to DEFAULT_BT_CONFIG,
            "default_usb" to DEFAULT_USB_CONFIG,
            "default_lan" to DEFAULT_LAN_CONFIG
        )
        
        var needsSave = false
        defaultConfigs.forEach { (key, config) ->
            if (!profiles.containsKey(key)) {
                profiles[key] = config.copy(isDefault = true)
                needsSave = true
            }
        }
        
        if (needsSave) {
            saveProfiles()
        }
    }
    
    /**
     * Get all printer profiles
     */
    fun getAllProfiles(): List<PrinterConfig> {
        return profiles.values.sortedByDescending { it.lastUsed }
    }
    
    /**
     * Get profiles by type
     */
    fun getProfilesByType(type: String): List<PrinterConfig> {
        return profiles.values.filter { it.type == type && it.enabled }
    }
    
    /**
     * Get a specific profile by ID
     */
    fun getProfile(id: String): PrinterConfig? {
        return profiles[id]
    }
    
    /**
     * Get the default profile for a specific type
     */
    fun getDefaultProfile(type: String): PrinterConfig? {
        return profiles.values.firstOrNull { it.type == type && it.isDefault }
    }
    
    /**
     * Add or update a printer profile
     */
    fun saveProfile(config: PrinterConfig): Boolean {
        return try {
            profiles[config.id] = config.copy(lastUsed = System.currentTimeMillis())
            saveProfiles()
            true
        } catch (e: Exception) {
            android.util.Log.e("PrinterConfigManager", "Error saving profile: ${e.message}", e)
            false
        }
    }
    
    /**
     * Delete a printer profile
     */
    fun deleteProfile(id: String): Boolean {
        return try {
            if (profiles.containsKey(id) && profiles[id]?.isDefault != true) {
                profiles.remove(id)
                saveProfiles()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Set a profile as the default for its type
     */
    fun setAsDefault(id: String): Boolean {
        return try {
            val config = profiles[id] ?: return false
            
            // Remove default flag from other profiles of the same type
            profiles.values.filter { it.type == config.type && it.id != id }
                .forEach { profiles[it.id] = it.copy(isDefault = false) }
            
            // Set this profile as default
            profiles[id] = config.copy(isDefault = true)
            saveProfiles()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get the last used printer configuration
     */
    fun getLastUsedProfile(): PrinterConfig? {
        val lastUsedId = prefs.getString(KEY_LAST_USED, null)
        return if (lastUsedId != null) profiles[lastUsedId] else null
    }
    
    /**
     * Set the last used printer configuration
     */
    fun setLastUsedProfile(id: String) {
        prefs.edit().putString(KEY_LAST_USED, id).apply()
        profiles[id]?.let { config ->
            profiles[id] = config.copy(lastUsed = System.currentTimeMillis())
            saveProfiles()
        }
    }
    
    /**
     * Get auto-connect enabled profiles
     */
    fun getAutoConnectProfiles(): List<PrinterConfig> {
        return profiles.values.filter { it.autoConnect && it.enabled }
    }
    
    /**
     * Create a new profile from connection parameters
     */
    fun createProfileFromConnection(
        type: String,
        name: String,
        connectionParams: Map<String, String>,
        paperWidth: Int = PAPER_80MM
    ): PrinterConfig {
        val baseConfig = when (type) {
            TYPE_BLUETOOTH -> DEFAULT_BT_CONFIG
            TYPE_USB -> DEFAULT_USB_CONFIG
            TYPE_LAN -> DEFAULT_LAN_CONFIG
            else -> DEFAULT_BT_CONFIG
        }
        
        return baseConfig.copy(
            type = type,
            name = name,
            connectionParams = connectionParams,
            paperWidth = paperWidth
        )
    }
    
    /**
     * Duplicate an existing profile
     */
    fun duplicateProfile(id: String, newName: String): PrinterConfig? {
        val original = profiles[id] ?: return null
        val duplicated = original.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = newName,
            isDefault = false,
            lastUsed = System.currentTimeMillis()
        )
        return if (saveProfile(duplicated)) duplicated else null
    }
    
    /**
     * Export all profiles as JSON
     */
    fun exportProfiles(): String {
        val json = JSONObject()
        profiles.forEach { (key, config) ->
            json.put(key, config.toJson())
        }
        return json.toString()
    }
    
    /**
     * Import profiles from JSON
     */
    fun importProfiles(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            val importedProfiles = mutableMapOf<String, PrinterConfig>()
            
            json.keys().forEach { key ->
                val profileJson = json.getJSONObject(key)
                val config = PrinterConfig.fromJson(profileJson)
                importedProfiles[key] = config
            }
            
            // Merge with existing profiles (don't overwrite existing IDs)
            importedProfiles.forEach { (key, config) ->
                if (!profiles.containsKey(config.id)) {
                    profiles[config.id] = config
                }
            }
            
            saveProfiles()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Clear all non-default profiles
     */
    fun clearAllProfiles(): Boolean {
        return try {
            val defaultProfiles = profiles.filter { it.value.isDefault }
            profiles.clear()
            profiles.putAll(defaultProfiles)
            saveProfiles()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get printer configuration statistics
     */
    fun getStatistics(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        stats["totalProfiles"] = profiles.size
        stats["enabledProfiles"] = profiles.values.count { it.enabled }
        stats["autoConnectProfiles"] = profiles.values.count { it.autoConnect && it.enabled }
        stats["profilesByType"] = profiles.values.groupBy { it.type }.mapValues { it.value.size }
        stats["lastUsed"] = profiles.values.maxByOrNull { it.lastUsed }?.name ?: "None"
        return stats
    }
}
