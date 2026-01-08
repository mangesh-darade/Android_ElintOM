package com.elintpos.wrapper.utils

import android.util.Log
import com.elintpos.wrapper.config.AppConfig
import java.net.URL
import java.util.regex.Pattern

/**
 * Input validation utility for sanitizing and validating user inputs
 * 
 * Prevents security issues like XSS, injection attacks, and memory issues
 * from malicious or malformed inputs from the WebView JavaScript bridge.
 */
object InputValidator {
    
    private const val TAG = "InputValidator"
    
    /**
     * Validates and sanitizes print text content
     * 
     * @param text The text to validate
     * @return Validation result with sanitized text or error message
     */
    fun validatePrintText(text: String?): ValidationResult {
        if (text.isNullOrBlank()) {
            return ValidationResult.Error("Print text cannot be empty")
        }
        
        // Check length
        if (text.length > AppConfig.Printer.MAX_PRINT_TEXT_LENGTH) {
            return ValidationResult.Error(
                "Print text exceeds maximum length of ${AppConfig.Printer.MAX_PRINT_TEXT_LENGTH} characters"
            )
        }
        
        // Sanitize: Remove null bytes and control characters (except newlines and tabs)
        val sanitized = text
            .replace("\u0000", "") // Remove null bytes
            .replace(Regex("[\u0001-\u0008\u000B\u000C\u000E-\u001F]"), "") // Remove control chars except \n, \t
        
        return ValidationResult.Success(sanitized)
    }
    
    /**
     * Validates URL before loading in WebView
     * 
     * @param urlString The URL string to validate
     * @return Validation result with validated URL or error message
     */
    fun validateUrl(urlString: String?): ValidationResult {
        if (urlString.isNullOrBlank()) {
            return ValidationResult.Error("URL cannot be empty")
        }
        
        // Check length
        if (urlString.length > AppConfig.WebView.MAX_URL_LENGTH) {
            return ValidationResult.Error("URL exceeds maximum length")
        }
        
        // Try to parse as URL
        return try {
            val url = URL(urlString)
            val protocol = url.protocol.lowercase()
            
            // Only allow http, https, file, data, and blob schemes
            if (protocol !in listOf("http", "https", "file", "data", "blob")) {
                return ValidationResult.Error("Invalid URL scheme: $protocol")
            }
            
            // For http/https, validate domain (optional - can be configured)
            if (protocol in listOf("http", "https")) {
                val host = url.host
                if (host.isNullOrBlank()) {
                    return ValidationResult.Error("Invalid URL: missing host")
                }
                
                // Check for suspicious patterns
                if (containsSuspiciousPatterns(host)) {
                    Log.w(TAG, "Suspicious URL pattern detected: $host")
                    // Still allow, but log it
                }
            }
            
            ValidationResult.Success(urlString)
        } catch (e: Exception) {
            ValidationResult.Error("Invalid URL format: ${e.message}")
        }
    }
    
    /**
     * Validates file path to prevent directory traversal attacks
     * 
     * @param filePath The file path to validate
     * @return Validation result with validated path or error message
     */
    fun validateFilePath(filePath: String?): ValidationResult {
        if (filePath.isNullOrBlank()) {
            return ValidationResult.Error("File path cannot be empty")
        }
        
        // Check for directory traversal attempts
        if (filePath.contains("..") || filePath.contains("//")) {
            return ValidationResult.Error("Invalid file path: directory traversal detected")
        }
        
        // Check for absolute paths outside app directory (security)
        if (filePath.startsWith("/") && !filePath.startsWith("/data/data/")) {
            return ValidationResult.Error("Invalid file path: outside app directory")
        }
        
        return ValidationResult.Success(filePath)
    }
    
    /**
     * Validates mobile number format
     * 
     * @param mobileNumber The mobile number to validate
     * @return Validation result
     */
    fun validateMobileNumber(mobileNumber: String?): ValidationResult {
        if (mobileNumber.isNullOrBlank()) {
            return ValidationResult.Error("Mobile number cannot be empty")
        }
        
        // Remove spaces and special characters for validation
        val cleaned = mobileNumber.replace(Regex("[\\s\\-\\(\\)]"), "")
        
        // Check if it's all digits
        if (!cleaned.all { it.isDigit() }) {
            return ValidationResult.Error("Mobile number must contain only digits")
        }
        
        // Check length (typical mobile numbers are 10-15 digits)
        if (cleaned.length < 10 || cleaned.length > 15) {
            return ValidationResult.Error("Mobile number must be between 10 and 15 digits")
        }
        
        return ValidationResult.Success(cleaned)
    }
    
    /**
     * Validates domain name format
     * 
     * @param domain The domain to validate
     * @return Validation result
     */
    fun validateDomain(domain: String?): ValidationResult {
        if (domain.isNullOrBlank()) {
            return ValidationResult.Error("Domain cannot be empty")
        }
        
        // Remove http/https prefix if present
        val cleaned = domain
            .replace(Regex("^https?://"), "")
            .replace(Regex("/.*$"), "") // Remove path
            .trim()
        
        // Basic domain validation
        val domainPattern = Pattern.compile(
            "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$"
        )
        
        if (!domainPattern.matcher(cleaned).matches()) {
            return ValidationResult.Error("Invalid domain format")
        }
        
        return ValidationResult.Success(cleaned)
    }
    
    /**
     * Validates JSON string
     * 
     * @param jsonString The JSON string to validate
     * @return Validation result
     */
    fun validateJson(jsonString: String?): ValidationResult {
        if (jsonString.isNullOrBlank()) {
            return ValidationResult.Error("JSON cannot be empty")
        }
        
        return try {
            org.json.JSONObject(jsonString)
            ValidationResult.Success(jsonString)
        } catch (e: org.json.JSONException) {
            try {
                org.json.JSONArray(jsonString)
                ValidationResult.Success(jsonString)
            } catch (e2: org.json.JSONException) {
                ValidationResult.Error("Invalid JSON format: ${e.message}")
            }
        }
    }
    
    /**
     * Sanitizes string to prevent XSS attacks
     * Removes potentially dangerous characters and patterns
     */
    fun sanitizeString(input: String?): String {
        if (input.isNullOrBlank()) return ""
        
        return input
            .replace("<script", "") // Remove script tags
            .replace("</script>", "")
            .replace("javascript:", "") // Remove javascript: protocol
            .replace("onerror=", "") // Remove event handlers
            .replace("onclick=", "")
            .replace("onload=", "")
            .trim()
    }
    
    /**
     * Checks for suspicious patterns in hostname
     */
    private fun containsSuspiciousPatterns(host: String): Boolean {
        val suspiciousPatterns = listOf(
            "localhost",
            "127.0.0.1",
            "0.0.0.0",
            "::1",
            "192.168.",
            "10.",
            "172.16.",
            "172.17.",
            "172.18.",
            "172.19.",
            "172.20.",
            "172.21.",
            "172.22.",
            "172.23.",
            "172.24.",
            "172.25.",
            "172.26.",
            "172.27.",
            "172.28.",
            "172.29.",
            "172.30.",
            "172.31."
        )
        
        return suspiciousPatterns.any { host.startsWith(it, ignoreCase = true) }
    }
    
    /**
     * Validation result sealed class
     */
    sealed class ValidationResult {
        data class Success(val value: String) : ValidationResult()
        data class Error(val message: String) : ValidationResult()
        
        fun isSuccess(): Boolean = this is Success
        fun isError(): Boolean = this is Error
        
        fun getValueOrNull(): String? = (this as? Success)?.value
        fun getErrorMessage(): String? = (this as? Error)?.message
    }
}

