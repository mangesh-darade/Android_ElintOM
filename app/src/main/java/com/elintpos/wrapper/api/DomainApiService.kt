package com.elintpos.wrapper.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DomainApiService - API Service for Fetching Domains
 * 
 * Purpose: Handles API calls to fetch available domains/outlets based on mobile number.
 * This service is used during initial setup to retrieve the list of domains
 * associated with a customer's mobile number.
 * 
 * API Endpoint: (To be configured based on actual API)
 * Method: POST
 * Body: { "mobile": "mobile_number" }
 * Response: { "success": true, "domains": [...] }
 */
class DomainApiService {
    
    companion object {
        private const val TAG = "DomainApiService"
        private const val API_URL = "https://ElintOm.Elintpos.in/android_api/getUserDomain"
        private const val CONNECT_TIMEOUT = 15000 // 15 seconds
        private const val READ_TIMEOUT = 15000 // 15 seconds
    }
    
    /**
     * Data class representing a domain/outlet
     */
    data class DomainInfo(
        val id: String,
        val name: String,
        val domain: String,
        val outletName: String? = null,
        val isActive: Boolean = true
    )
    
    /**
     * Result class for API calls
     */
    sealed class ApiResult {
        data class Success(val domains: List<DomainInfo>) : ApiResult()
        data class Error(val message: String) : ApiResult()
    }
    
    /**
     * Fetch domains based on mobile number
     * 
     * @param mobileNumber Customer mobile number
     * @return ApiResult containing list of domains or error message
     */
    suspend fun fetchDomains(mobileNumber: String): ApiResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            // Validate mobile number
            if (mobileNumber.isBlank()) {
                Log.e(TAG, "Mobile number is empty")
                return@withContext ApiResult.Error("Mobile number is required")
            }
            
            // Create URL and connection
            val url = URL(API_URL)
            connection = url.openConnection() as HttpURLConnection
            
            // Configure connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ElintPOS-Android")
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.doOutput = true
            connection.doInput = true
            
            // Create request body
            val requestBody = JSONObject().apply {
                put("mobile", mobileNumber.trim())
            }
            
            // Write request body
            try {
                connection.outputStream.use { output ->
                    val bodyBytes = requestBody.toString().toByteArray(Charsets.UTF_8)
                    output.write(bodyBytes)
                    output.flush()
                }
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Error writing request body", e)
                return@withContext ApiResult.Error("Failed to send request: ${e.message}")
            }
            
            // Get response code
            val responseCode = try {
                connection.responseCode
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Error getting response code", e)
                return@withContext ApiResult.Error("Connection error: ${e.message}")
            }
            
            Log.d(TAG, "API Response Code: $responseCode for mobile: $mobileNumber")
            
            // Handle different response codes
            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Success - read response
                    try {
                        val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        Log.d(TAG, "API Response: $response")
                        
                        if (response.isBlank()) {
                            Log.e(TAG, "Empty response from server")
                            return@withContext ApiResult.Error("Empty response from server")
                        }
                        
                        // Parse JSON response
                        val jsonResponse = try {
                            JSONObject(response)
                        } catch (e: org.json.JSONException) {
                            Log.e(TAG, "Invalid JSON response", e)
                            return@withContext ApiResult.Error("Invalid response format from server")
                        }
                        
                        // Check if response indicates success
                        val success = jsonResponse.optBoolean("success", false) || 
                                     jsonResponse.optBoolean("status", false) ||
                                     jsonResponse.has("domains") || 
                                     jsonResponse.has("data")
                        
                        if (success || jsonResponse.has("domains") || jsonResponse.has("data")) {
                            // Extract domains array (try different possible keys)
                            val domainsArray = jsonResponse.optJSONArray("domains") 
                                ?: jsonResponse.optJSONArray("data")
                                ?: jsonResponse.optJSONArray("outlets")
                                ?: JSONArray()
                            
                            val domains = mutableListOf<DomainInfo>()
                            
                            // Parse domains
                            if (domainsArray.length() > 0) {
                                for (i in 0 until domainsArray.length()) {
                                    try {
                                        val domainObj = domainsArray.getJSONObject(i)
                                        domains.add(
                                            DomainInfo(
                                                id = domainObj.optString("id", i.toString()),
                                                name = domainObj.optString("name", domainObj.optString("outlet_name", "")),
                                                domain = domainObj.optString("domain", ""),
                                                outletName = domainObj.optString("outlet_name", domainObj.optString("name")),
                                                isActive = domainObj.optBoolean("is_active", domainObj.optBoolean("active", true))
                                            )
                                        )
                                    } catch (e: org.json.JSONException) {
                                        Log.w(TAG, "Error parsing domain at index $i", e)
                                        // Continue with next domain
                                    }
                                }
                            }
                            
                            if (domains.isEmpty()) {
                                Log.w(TAG, "No domains found in response")
                                val errorMsg = jsonResponse.optString("message", "No domains found for this mobile number")
                                return@withContext ApiResult.Error(errorMsg)
                            }
                            
                            Log.d(TAG, "Successfully fetched ${domains.size} domains for mobile: $mobileNumber")
                            ApiResult.Success(domains)
                        } else {
                            // API returned error
                            val errorMsg = jsonResponse.optString("message", 
                                jsonResponse.optString("error", "Failed to fetch domains"))
                            Log.e(TAG, "API returned error: $errorMsg")
                            ApiResult.Error(errorMsg)
                        }
                    } catch (e: java.io.IOException) {
                        Log.e(TAG, "Error reading response", e)
                        ApiResult.Error("Failed to read server response: ${e.message}")
                    } catch (e: org.json.JSONException) {
                        Log.e(TAG, "Error parsing JSON response", e)
                        ApiResult.Error("Invalid response format: ${e.message}")
                    }
                }
                
                HttpURLConnection.HTTP_BAD_REQUEST -> {
                    // 400 - Bad Request
                    val errorMsg = try {
                        connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.let {
                            val errorJson = JSONObject(it)
                            errorJson.optString("message", errorJson.optString("error", "Invalid request"))
                        } ?: "Invalid request. Please check your mobile number."
                    } catch (e: Exception) {
                        "Invalid request. Please check your mobile number."
                    }
                    Log.e(TAG, "Bad Request (400): $errorMsg")
                    ApiResult.Error(errorMsg)
                }
                
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    // 401 - Unauthorized
                    Log.e(TAG, "Unauthorized (401)")
                    ApiResult.Error("Unauthorized access. Please contact support.")
                }
                
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    // 404 - Not Found
                    Log.e(TAG, "Not Found (404)")
                    ApiResult.Error("API endpoint not found. Please contact support.")
                }
                
                HttpURLConnection.HTTP_INTERNAL_ERROR -> {
                    // 500 - Internal Server Error
                    val errorMsg = try {
                        connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.let {
                            val errorJson = JSONObject(it)
                            errorJson.optString("message", "Server error occurred")
                        } ?: "Server error. Please try again later."
                    } catch (e: Exception) {
                        "Server error. Please try again later."
                    }
                    Log.e(TAG, "Server Error (500): $errorMsg")
                    ApiResult.Error(errorMsg)
                }
                
                else -> {
                    // Other HTTP errors
                    val errorMsg = try {
                        connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.let {
                            try {
                                val errorJson = JSONObject(it)
                                errorJson.optString("message", errorJson.optString("error", "HTTP Error: $responseCode"))
                            } catch (e: Exception) {
                                "HTTP Error: $responseCode"
                            }
                        } ?: "HTTP Error: $responseCode"
                    } catch (e: Exception) {
                        "HTTP Error: $responseCode"
                    }
                    Log.e(TAG, "HTTP Error $responseCode: $errorMsg")
                    ApiResult.Error(errorMsg)
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Connection timeout", e)
            ApiResult.Error("Connection timeout. Please check your internet connection and try again.")
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Unknown host", e)
            ApiResult.Error("Cannot reach server. Please check your internet connection.")
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "Connection refused", e)
            ApiResult.Error("Cannot connect to server. Please check your internet connection.")
        } catch (e: java.io.IOException) {
            Log.e(TAG, "IO error", e)
            ApiResult.Error("Network error: ${e.message ?: "Please check your internet connection"}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error fetching domains", e)
            ApiResult.Error("Unexpected error: ${e.message ?: "Please try again later"}")
        } finally {
            // Clean up connection
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting", e)
            }
        }
    }
    
    /**
     * Mock method for testing without actual API
     * Returns sample domains for development/testing
     */
    suspend fun fetchDomainsMock(mobileNumber: String): ApiResult = withContext(Dispatchers.IO) {
        // Simulate network delay
        kotlinx.coroutines.delay(1000)
        
        // Return mock data
        val mockDomains = listOf(
            DomainInfo(
                id = "1",
                name = "Main Outlet",
                domain = "1602clothingprod.elintpos.in",
                outletName = "Main Outlet",
                isActive = true
            ),
            DomainInfo(
                id = "2",
                name = "Branch 1",
                domain = "branch1.elintpos.in",
                outletName = "Branch 1",
                isActive = true
            ),
            DomainInfo(
                id = "3",
                name = "Branch 2",
                domain = "branch2.elintpos.in",
                outletName = "Branch 2",
                isActive = true
            )
        )
        
        ApiResult.Success(mockDomains)
    }
}

