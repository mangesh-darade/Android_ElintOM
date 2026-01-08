package com.elintpos.wrapper.utils

import android.util.Log
import com.elintpos.wrapper.BuildConfig

/**
 * Centralized logging utility for the application
 * 
 * Provides consistent logging interface with:
 * - Debug logs only in debug builds
 * - Error logs always enabled
 * - Optional crash reporting integration
 * - Tag management
 */
object AppLogger {
    
    private const val DEFAULT_TAG = "ElintPOS"
    
    /**
     * Log levels
     */
    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
    
    /**
     * Log a debug message
     * Only logged in debug builds
     */
    fun d(message: String, tag: String = DEFAULT_TAG) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }
    
    /**
     * Log a debug message with throwable
     * Only logged in debug builds
     */
    fun d(message: String, throwable: Throwable?, tag: String = DEFAULT_TAG) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.d(tag, message, throwable)
            } else {
                Log.d(tag, message)
            }
        }
    }
    
    /**
     * Log an info message
     */
    fun i(message: String, tag: String = DEFAULT_TAG) {
        Log.i(tag, message)
    }
    
    /**
     * Log a warning message
     */
    fun w(message: String, tag: String = DEFAULT_TAG) {
        Log.w(tag, message)
    }
    
    /**
     * Log a warning message with throwable
     */
    fun w(message: String, throwable: Throwable?, tag: String = DEFAULT_TAG) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }
    
    /**
     * Log an error message
     * Always logged, even in release builds
     */
    fun e(message: String, tag: String = DEFAULT_TAG) {
        Log.e(tag, message)
        // TODO: Send to crash reporting service (Firebase Crashlytics, Sentry, etc.)
    }
    
    /**
     * Log an error message with throwable
     * Always logged, even in release builds
     */
    fun e(message: String, throwable: Throwable?, tag: String = DEFAULT_TAG) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        // TODO: Send to crash reporting service (Firebase Crashlytics, Sentry, etc.)
    }
    
    /**
     * Log a verbose message
     * Only logged in debug builds
     */
    fun v(message: String, tag: String = DEFAULT_TAG) {
        if (BuildConfig.DEBUG) {
            Log.v(tag, message)
        }
    }
    
    /**
     * Log with custom level
     */
    fun log(level: Level, message: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) {
        when (level) {
            Level.VERBOSE -> v(message, tag)
            Level.DEBUG -> d(message, throwable, tag)
            Level.INFO -> i(message, tag)
            Level.WARN -> w(message, throwable, tag)
            Level.ERROR -> e(message, throwable, tag)
        }
    }
    
    /**
     * Log a formatted message (similar to String.format)
     */
    fun d(format: String, vararg args: Any?, tag: String = DEFAULT_TAG) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, format.format(*args))
        }
    }
    
    fun i(format: String, vararg args: Any?, tag: String = DEFAULT_TAG) {
        Log.i(tag, format.format(*args))
    }
    
    fun w(format: String, vararg args: Any?, tag: String = DEFAULT_TAG) {
        Log.w(tag, format.format(*args))
    }
    
    fun e(format: String, vararg args: Any?, tag: String = DEFAULT_TAG) {
        Log.e(tag, format.format(*args))
    }
    
    /**
     * Log sensitive data (only in debug builds, with warning)
     */
    fun logSensitive(message: String, tag: String = DEFAULT_TAG) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, "[SENSITIVE] $message")
        }
        // Never log sensitive data in release builds
    }
    
    /**
     * Log network request (sanitized)
     */
    fun logNetworkRequest(url: String, method: String = "GET", tag: String = DEFAULT_TAG) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, "Network Request: $method $url")
        }
    }
    
    /**
     * Log network response (sanitized)
     */
    fun logNetworkResponse(url: String, statusCode: Int, tag: String = DEFAULT_TAG) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, "Network Response: $statusCode for $url")
        }
    }
    
    /**
     * Log printer operation
     */
    fun logPrinter(operation: String, printerType: String, success: Boolean, tag: String = DEFAULT_TAG) {
        val status = if (success) "SUCCESS" else "FAILED"
        Log.i(tag, "Printer [$printerType]: $operation - $status")
    }
    
    /**
     * Log printer error
     */
    fun logPrinterError(operation: String, printerType: String, error: String, tag: String = DEFAULT_TAG) {
        Log.e(tag, "Printer [$printerType]: $operation - ERROR: $error")
    }
}

