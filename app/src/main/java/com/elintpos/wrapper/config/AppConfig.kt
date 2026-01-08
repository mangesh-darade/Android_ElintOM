package com.elintpos.wrapper.config

/**
 * Application-wide configuration constants
 * 
 * Centralizes all magic numbers, timeouts, and configuration values
 * to improve maintainability and reduce errors.
 */
object AppConfig {
    
    /**
     * Session configuration
     */
    object Session {
        /** Default session timeout: 8 hours */
        const val DEFAULT_TIMEOUT_MS = 8 * 60 * 60 * 1000L
        
        /** Key for session timeout preference */
        const val PREF_SESSION_TIMEOUT_MS = "session_timeout_ms"
        
        /** Key for last activity time preference */
        const val PREF_LAST_ACTIVITY_TIME = "last_activity_time"
    }
    
    /**
     * Printer configuration
     */
    object Printer {
        /** 58mm paper width in dots */
        const val PAPER_WIDTH_58MM = 384
        
        /** 80mm paper width in dots */
        const val PAPER_WIDTH_80MM = 576
        
        /** 112mm paper width in dots */
        const val PAPER_WIDTH_112MM = 832
        
        /** Default paper width */
        const val DEFAULT_PAPER_WIDTH = PAPER_WIDTH_80MM
        
        /** Default line spacing */
        const val DEFAULT_LINE_SPACING = 30
        
        /** Default connection timeout in milliseconds */
        const val DEFAULT_TIMEOUT_MS = 5000L
        
        /** Maximum print text length to prevent memory issues */
        const val MAX_PRINT_TEXT_LENGTH = 100_000 // 100KB
        
        /** Default LAN printer port */
        const val DEFAULT_LAN_PORT = 9100
    }
    
    /**
     * Service configuration
     */
    object Service {
        /** Foreground service notification ID */
        const val FOREGROUND_SERVICE_ID = 1001
        
        /** App monitoring interval in milliseconds (5 seconds) */
        const val MONITOR_INTERVAL_MS = 5000L
        
        /** Channel ID for foreground service notifications */
        const val NOTIFICATION_CHANNEL_ID = "autostart_channel"
        
        /** Notification channel name */
        const val NOTIFICATION_CHANNEL_NAME = "App Monitor"
    }
    
    /**
     * Crash recovery configuration
     */
    object CrashRecovery {
        /** Delay before restarting app after crash (1.2 seconds) */
        const val RESTART_DELAY_MS = 1200L
        
        /** PendingIntent request code for crash recovery */
        const val RESTART_REQUEST_CODE = 9999
        
        /** Crash log directory name */
        const val CRASH_LOG_DIR = "crash"
        
        /** Latest crash log file name */
        const val LATEST_CRASH_LOG = "latest.txt"
    }
    
    /**
     * Network configuration
     */
    object Network {
        /** Default connection timeout in milliseconds */
        const val CONNECT_TIMEOUT_MS = 15000L
        
        /** Default read timeout in milliseconds */
        const val READ_TIMEOUT_MS = 15000L
        
        /** Default domain API URL */
        const val DOMAIN_API_URL = "https://ElintOm.Elintpos.in/android_api/getUserDomain"
        
        /** Default domain fallback */
        const val DEFAULT_DOMAIN = "androidtesting.elintpos.in"
        
        /** Default base URL */
        val DEFAULT_BASE_URL = "https://$DEFAULT_DOMAIN/"
    }
    
    /**
     * WebView configuration
     */
    object WebView {
        /** User agent suffix */
        const val USER_AGENT_SUFFIX = " DesktopAndroidWebView/1366x768"
        
        /** Maximum URL length */
        const val MAX_URL_LENGTH = 2048
    }
    
    /**
     * File operations
     */
    object File {
        /** Maximum file size for operations (10MB) */
        const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L
        
        /** Supported image MIME types */
        val SUPPORTED_IMAGE_TYPES = arrayOf("image/jpeg", "image/png", "image/webp")
        
        /** Supported document MIME types */
        val SUPPORTED_DOCUMENT_TYPES = arrayOf(
            "application/pdf",
            "text/csv",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    }
    
    /**
     * Security configuration
     */
    object Security {
        /** Maximum retry attempts for failed operations */
        const val MAX_RETRY_ATTEMPTS = 3
        
        /** Retry delay in milliseconds */
        const val RETRY_DELAY_MS = 1000L
    }
}

