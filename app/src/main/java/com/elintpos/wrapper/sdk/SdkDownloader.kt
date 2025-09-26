package com.elintpos.wrapper.sdk

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SDK Downloader for automatically downloading and installing printer SDKs
 */
class SdkDownloader(private val context: Context) {
    
    companion object {
        private const val TAG = "SdkDownloader"
        private const val DOWNLOAD_TIMEOUT = 300L // 5 minutes
        private const val EPSON_SDK_URL = "https://download.epson-biz.com/modules/pos/index.php?page=single_soft&cid=4571&scat=58&pcat=3"
        private const val XPRINTER_SDK_URL = "https://www.xprinter.com/download/android-sdk"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(DOWNLOAD_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(DOWNLOAD_TIMEOUT, TimeUnit.SECONDS)
        .build()
    
    private val libsDir = File(context.filesDir, "libs")
    
    init {
        // Create libs directory if it doesn't exist
        if (!libsDir.exists()) {
            libsDir.mkdirs()
        }
    }
    
    /**
     * Download and install Epson ePOS2 SDK
     */
    suspend fun downloadEpsonSdk(): DownloadResult {
        return try {
            Log.d(TAG, "Starting Epson SDK download...")
            
            // Check if already downloaded
            val epsonAar = File(libsDir, "epson-epos2-2.8.0.aar")
            if (epsonAar.exists()) {
                return DownloadResult.Success("Epson SDK already installed")
            }
            
            // Try to download from Maven Central first
            val result = downloadFromMaven(
                groupId = "com.epson.epos2",
                artifactId = "epos2",
                version = "2.8.0",
                fileName = "epson-epos2-2.8.0.aar"
            )
            
            if (result is DownloadResult.Success) {
                Log.d(TAG, "Epson SDK downloaded successfully")
                return result
            }
            
            // If Maven download fails, provide manual installation instructions
            val instructionFile = File(libsDir, "epson-sdk-instructions.txt")
            instructionFile.writeText("""
                Epson ePOS2 SDK Manual Installation Instructions
                ================================================
                
                Due to repository access restrictions, the Epson SDK needs to be installed manually:
                
                1. Visit: https://download.epson-biz.com/modules/pos/index.php?page=single_soft&cid=4571&scat=58&pcat=3
                2. Download the Epson ePOS2 SDK for Android
                3. Extract the downloaded package
                4. Find the AAR file (usually named epos2-2.8.0.aar or similar)
                5. Rename it to 'epson-epos2-2.8.0.aar'
                6. Place it in the app/libs/ directory
                7. Rebuild the project
                
                Alternative download locations:
                - Epson Developer Portal: https://developer.epson.com/
                - GitHub releases (if available)
                
                Note: The SDK will be automatically detected once placed in the libs folder.
            """.trimIndent())
            
            DownloadResult.Info("Epson SDK requires manual download. See instructions in libs/epson-sdk-instructions.txt")
        } catch (e: Exception) {
            Log.e(TAG, "Error with Epson SDK", e)
            DownloadResult.Error("Failed to process Epson SDK: ${e.message}")
        }
    }
    
    /**
     * Download and install XPrinter SDK
     */
    suspend fun downloadXPrinterSdk(): DownloadResult {
        return try {
            Log.d(TAG, "Starting XPrinter SDK download...")
            
            // Check if already downloaded
            val xprinterAar = File(libsDir, "xprinter-sdk.aar")
            if (xprinterAar.exists()) {
                return DownloadResult.Success("XPrinter SDK already installed")
            }
            
            // XPrinter SDK is not available on public Maven repositories
            // We'll create a placeholder and provide instructions
            val placeholderFile = File(libsDir, "xprinter-sdk-placeholder.txt")
            placeholderFile.writeText("""
                XPrinter SDK Download Instructions:
                ===================================
                
                1. Visit: https://www.xprinter.com/download/android-sdk
                2. Download the XPrinter Android SDK
                3. Extract the AAR file from the downloaded package
                4. Rename it to 'xprinter-sdk.aar'
                5. Place it in the app/libs/ directory
                6. Rebuild the project
                
                Note: XPrinter SDK requires manual download due to licensing restrictions.
            """.trimIndent())
            
            DownloadResult.Info("XPrinter SDK requires manual download. See instructions in libs/xprinter-sdk-placeholder.txt")
        } catch (e: Exception) {
            Log.e(TAG, "Error with XPrinter SDK", e)
            DownloadResult.Error("Failed to process XPrinter SDK: ${e.message}")
        }
    }
    
    /**
     * Download SDK from Maven repository
     */
    private suspend fun downloadFromMaven(
        groupId: String,
        artifactId: String,
        version: String,
        fileName: String
    ): DownloadResult {
        return try {
            // Maven coordinates to URL conversion
            val groupPath = groupId.replace(".", "/")
            
            // Try multiple Maven repositories
            val mavenUrls = listOf(
                "https://repo1.maven.org/maven2/$groupPath/$artifactId/$version/$artifactId-$version.aar",
                "https://maven.epson.com/repository/maven2/$groupPath/$artifactId/$version/$artifactId-$version.aar",
                "https://jcenter.bintray.com/$groupPath/$artifactId/$version/$artifactId-$version.aar"
            )
            
            var lastError: Exception? = null
            
            for (mavenUrl in mavenUrls) {
                try {
                    val request = Request.Builder()
                        .url(mavenUrl)
                        .build()
                    
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val file = File(libsDir, fileName)
                        response.body?.byteStream()?.use { inputStream ->
                            FileOutputStream(file).use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        return DownloadResult.Success("SDK downloaded successfully: $fileName")
                    }
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
            }
            
            DownloadResult.Error("Failed to download SDK from all repositories. Last error: ${lastError?.message}")
        } catch (e: Exception) {
            DownloadResult.Error("Download failed: ${e.message}")
        }
    }
    
    /**
     * Check if SDKs are available
     */
    fun checkSdkAvailability(): Map<String, Any> {
        val epsonAar = File(libsDir, "epson-epos2-2.8.0.aar")
        val xprinterAar = File(libsDir, "xprinter-sdk.aar")
        
        return mapOf(
            "epson_sdk_available" to epsonAar.exists(),
            "xprinter_sdk_available" to xprinterAar.exists(),
            "epson_sdk_path" to epsonAar.absolutePath,
            "xprinter_sdk_path" to xprinterAar.absolutePath
        )
    }
    
    /**
     * Get download progress (placeholder for future implementation)
     */
    fun getDownloadProgress(): Map<String, Int> {
        // This would be implemented with a progress callback system
        return mapOf(
            "epson_progress" to 0,
            "xprinter_progress" to 0
        )
    }
    
    /**
     * Download result sealed class
     */
    sealed class DownloadResult {
        data class Success(val message: String) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
        data class Info(val message: String) : DownloadResult()
    }
}
