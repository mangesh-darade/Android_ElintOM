package com.elintpos.wrapper.bridge

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.elintpos.wrapper.MainActivity
import com.elintpos.wrapper.UnifiedPrinterHandler
import com.elintpos.wrapper.printer.PrinterConfigManager
import com.elintpos.wrapper.printer.PrinterTester
import com.elintpos.wrapper.printer.vendor.AutoReplyPrint
import com.elintpos.wrapper.printer.vendor.EpsonPrinter
import com.elintpos.wrapper.printer.vendor.VendorPrinter
import com.elintpos.wrapper.printer.vendor.XPrinter
import com.elintpos.wrapper.sdk.SdkDownloader
import com.elintpos.wrapper.utils.AppLogger
import com.elintpos.wrapper.utils.InputValidator
import com.elintpos.wrapper.utils.PreferencesManager
import com.elintpos.wrapper.viewer.ReceiptActivity
import com.elintpos.wrapper.escpos.BluetoothEscPosPrinter
import com.elintpos.wrapper.escpos.UsbEscPosPrinter
import com.elintpos.wrapper.escpos.LanEscPosPrinter
import com.elintpos.wrapper.escpos.ReceiptFormatter
import com.elintpos.wrapper.pdf.PdfDownloader
import com.elintpos.wrapper.export.CsvExporter
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.webkit.CookieManager
import android.print.PrintManager
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PageRange
import android.print.PrintDocumentAdapter.LayoutResultCallback
import android.print.PrintDocumentAdapter.WriteResultCallback
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import android.app.AlertDialog

/**
 * JavaScript Bridge - Handles all communication between WebView JavaScript and native Android code
 * 
 * This class contains all @JavascriptInterface methods that are exposed to the WebView.
 * It provides a clean separation of concerns from MainActivity.
 * 
 * Usage:
 * ```kotlin
 * val jsBridge = JavaScriptBridge(activity, dependencies...)
 * webView.addJavascriptInterface(jsBridge, "ElintPOSNative")
 * ```
 */
class JavaScriptBridge(
    private val activity: MainActivity,
    private val webViewProvider: () -> WebView, // Lazy WebView provider
    private val unifiedPrinterHandler: UnifiedPrinterHandler,
    private val printerConfigManager: PrinterConfigManager,
    private val preferencesManager: PreferencesManager,
    private val printerTester: PrinterTester,
    private val sdkDownloader: SdkDownloader,
    private val autoReplyPrint: AutoReplyPrint,
    private val epsonPrinter: EpsonPrinter,
    private val xPrinter: XPrinter,
    private val vendorPrinter: VendorPrinter
) {
    
    // Get WebView lazily
    private val webView: WebView get() = webViewProvider()
    
    companion object {
        private const val TAG = "JavaScriptBridge"
    }
    
    /**
     * Helper function to create a consistent JSON response
     */
    private fun createJsonResponse(ok: Boolean, msg: String? = null, data: Map<String, Any>? = null): String {
        val json = JSONObject()
        json.put("ok", ok)
        msg?.let { json.put("msg", it) }
        data?.forEach { (key, value) ->
            try {
                when (value) {
                    is String -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    is Long -> json.put(key, value)
                    is Double -> json.put(key, value)
                    is Float -> json.put(key, value.toDouble())
                    is JSONObject -> json.put(key, value)
                    is JSONArray -> json.put(key, value)
                    else -> json.put(key, value.toString())
                }
            } catch (e: Exception) {
                AppLogger.w("Error adding key $key to JSON response: ${e.message}", TAG)
            }
        }
        return json.toString()
    }
    
    /**
     * Helper function to create error response
     */
    private fun createErrorResponse(message: String): String {
        return createJsonResponse(ok = false, msg = message)
    }
    
    /**
     * Helper function to create success response
     */
    private fun createSuccessResponse(message: String? = null, data: Map<String, Any>? = null): String {
        return createJsonResponse(ok = true, msg = message, data = data)
    }
    
    // Helper function to get base URL
    private fun getBaseUrl(): String = preferencesManager.getBaseUrl()
    
    // Helper function to build absolute URL
    private fun buildAbsoluteUrl(input: String): String {
        val url = input.trim()
        if (url.isEmpty()) return url
        
        val baseUrl = getBaseUrl()
        
        // Already absolute (has scheme)
        if (url.contains("://")) return url
        
        // Scheme-relative URL
        if (url.startsWith("//")) {
            val scheme = try {
                java.net.URI(webView.url ?: baseUrl).scheme ?: "http"
            } catch (_: Exception) { "http" }
            return "$scheme:$url"
        }
        
        // Resolve against current page or baseUrl
        val base = try {
            val current = webView.url ?: baseUrl
            val baseUri = java.net.URI(current)
            if (baseUri.scheme == null || baseUri.host == null) java.net.URI(baseUrl) else baseUri
        } catch (_: Exception) { java.net.URI(baseUrl) }
        
        return try {
            base.resolve(url).toString()
        } catch (_: Exception) {
            (baseUrl.trimEnd('/') + "/" + url.trimStart('/'))
        }
    }
    
    // ==================== Debug & Utility Methods ====================
    
    @JavascriptInterface
    fun debugLog(message: String) {
        AppLogger.d("JS Debug: $message", TAG)
        activity.runOnUiThread {
            Toast.makeText(activity, "JS Debug: $message", Toast.LENGTH_SHORT).show()
        }
    }
    
    @JavascriptInterface
    fun checkInterfaceAvailable(): String {
        return createSuccessResponse(
            message = "ElintPOSNative interface is available",
            data = mapOf("timestamp" to System.currentTimeMillis())
        )
    }
    
    @JavascriptInterface
    fun testPrintFunction(): String {
        AppLogger.d("testPrintFunction called", TAG)
        activity.runOnUiThread {
            Toast.makeText(activity, "Test print function called", Toast.LENGTH_SHORT).show()
        }
        return createSuccessResponse(message = "Test print function working")
    }
    
    @JavascriptInterface
    fun debugPageInfo(): String {
        AppLogger.d("debugPageInfo called", TAG)
        val currentUrl = webView.url ?: "unknown"
        val userAgent = webView.settings.userAgentString ?: "unknown"
        return createSuccessResponse(
            data = mapOf(
                "url" to currentUrl,
                "userAgent" to userAgent,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }
    
    @JavascriptInterface
    fun forceInterfaceReconnect(): String {
        AppLogger.d("forceInterfaceReconnect called", TAG)
        activity.runOnUiThread {
            webView.evaluateJavascript("""
                console.log('=== Force Interface Reconnect ===');
                console.log('Current URL:', window.location.href);
                console.log('ElintPOSNative before:', typeof window.ElintPOSNative !== 'undefined');
                
                setTimeout(function() {
                    console.log('Refreshing page to reconnect interface...');
                    window.location.reload();
                }, 100);
            """, null)
        }
        return createSuccessResponse(message = "Interface reconnect initiated")
    }
    
    // ==================== Receipt Viewer Methods ====================
    
    @JavascriptInterface
    fun openReceiptUrl(url: String?): String {
        return try {
            val urlValidation = InputValidator.validateUrl(url)
            if (urlValidation.isError()) {
                return createErrorResponse(urlValidation.getErrorMessage() ?: "Invalid URL")
            }
            val validatedUrl = urlValidation.getValueOrNull() ?: return createErrorResponse("Invalid URL")
            
            activity.runOnUiThread {
                val i = Intent(activity, ReceiptActivity::class.java)
                i.putExtra(ReceiptActivity.EXTRA_URL, validatedUrl)
                activity.startActivity(i)
            }
            createSuccessResponse()
        } catch (e: Exception) {
            AppLogger.e("Error opening receipt URL", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun openReceiptForOrder(orderId: String?): String {
        return try {
            if (orderId.isNullOrBlank()) return createErrorResponse("Empty orderId")
            
            activity.runOnUiThread {
                val i = Intent(activity, ReceiptActivity::class.java)
                i.putExtra(ReceiptActivity.EXTRA_ORDER_ID, orderId)
                activity.startActivity(i)
            }
            createSuccessResponse()
        } catch (e: Exception) {
            AppLogger.e("Error opening receipt for order", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun openReceiptDialogUrl(url: String?): String {
        return try {
            val urlValidation = InputValidator.validateUrl(url)
            if (urlValidation.isError()) {
                return createErrorResponse(urlValidation.getErrorMessage() ?: "Invalid URL")
            }
            val validatedUrl = urlValidation.getValueOrNull() ?: return createErrorResponse("Invalid URL")
            val finalUrl = buildAbsoluteUrl(validatedUrl)
            
            activity.runOnUiThread {
                val i = Intent(activity, ReceiptActivity::class.java)
                i.putExtra(ReceiptActivity.EXTRA_URL, finalUrl)
                activity.startActivity(i)
            }
            createSuccessResponse()
        } catch (e: Exception) {
            AppLogger.e("Error opening receipt dialog", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun openReceiptDialogForOrder(orderId: String?): String {
        return try {
            if (orderId.isNullOrBlank()) return createErrorResponse("Empty orderId")
            val guess = getBaseUrl() + "pos/receipt/" + orderId + "?print=1"
            
            activity.runOnUiThread {
                val i = Intent(activity, ReceiptActivity::class.java)
                i.putExtra(ReceiptActivity.EXTRA_URL, guess)
                activity.startActivity(i)
            }
            createSuccessResponse()
        } catch (e: Exception) {
            AppLogger.e("Error opening receipt dialog for order", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== Printer Availability Methods ====================
    
    @JavascriptInterface
    fun vendorAvailable(): Boolean {
        return try { vendorPrinter.isAvailable() } catch (_: Exception) { false }
    }
    
    @JavascriptInterface
    fun epsonAvailable(): Boolean {
        return try { epsonPrinter.isAvailable() } catch (_: Exception) { false }
    }
    
    @JavascriptInterface
    fun xprinterAvailable(): Boolean {
        return try { xPrinter.isAvailable() } catch (_: Exception) { false }
    }
    
    // ==================== Printer Print Methods ====================
    
    @JavascriptInterface
    fun epsonPrintText(text: String): String {
        return try {
            val textValidation = InputValidator.validatePrintText(text)
            if (textValidation.isError()) {
                return createErrorResponse(textValidation.getErrorMessage() ?: "Invalid text")
            }
            val sanitizedText = textValidation.getValueOrNull() ?: return createErrorResponse("Invalid text")
            
            if (!epsonPrinter.isAvailable()) return createErrorResponse("Epson SDK not available")
            val ok = epsonPrinter.printText(sanitizedText)
            if (ok) createSuccessResponse() else createErrorResponse("Epson print failed")
        } catch (e: Exception) {
            AppLogger.e("Epson print error", e, TAG)
            createErrorResponse(e.message ?: "Print error")
        }
    }
    
    @JavascriptInterface
    fun xprinterPrintText(text: String): String {
        return try {
            val textValidation = InputValidator.validatePrintText(text)
            if (textValidation.isError()) {
                return createErrorResponse(textValidation.getErrorMessage() ?: "Invalid text")
            }
            val sanitizedText = textValidation.getValueOrNull() ?: return createErrorResponse("Invalid text")
            
            if (!xPrinter.isAvailable()) return createErrorResponse("XPrinter SDK not available")
            val ok = xPrinter.printText(sanitizedText)
            if (ok) createSuccessResponse() else createErrorResponse("XPrinter print failed")
        } catch (e: Exception) {
            AppLogger.e("XPrinter print error", e, TAG)
            createErrorResponse(e.message ?: "Print error")
        }
    }
    
    @JavascriptInterface
    fun vendorPrintText(text: String): String {
        return try {
            val textValidation = InputValidator.validatePrintText(text)
            if (textValidation.isError()) {
                return createErrorResponse(textValidation.getErrorMessage() ?: "Invalid text")
            }
            val sanitizedText = textValidation.getValueOrNull() ?: return createErrorResponse("Invalid text")
            
            if (!vendorPrinter.isAvailable()) return createErrorResponse("Vendor SDK not available")
            val ok = vendorPrinter.printText(sanitizedText)
            if (ok) createSuccessResponse() else createErrorResponse("Vendor print failed")
        } catch (e: Exception) {
            AppLogger.e("Vendor print error", e, TAG)
            createErrorResponse(e.message ?: "Print error")
        }
    }
    
    // ==================== Unified Print Method ====================
    
    @JavascriptInterface
    fun printWebContent(content: String, printerType: String = "auto"): String {
        // Validate input
        val textValidation = InputValidator.validatePrintText(content)
        if (textValidation.isError()) {
            return createErrorResponse(textValidation.getErrorMessage() ?: "Invalid content")
        }
        val sanitizedContent = textValidation.getValueOrNull() 
            ?: return createErrorResponse("Invalid content")
        
        // Check if print dialog should be shown
        // IMPORTANT: When checkbox is CHECKED (true) → show dialog
        //            When checkbox is UNCHECKED (false) → direct print (no dialog)
        val showPrintDialog = preferencesManager.showPrintDialog
        
        AppLogger.d("printWebContent - showPrintDialog: $showPrintDialog, content length: ${sanitizedContent.length}", TAG)
        
        if (showPrintDialog) {
            // Checkbox is CHECKED → Show Android print dialog
            return try {
                // Use a CountDownLatch to ensure the dialog is shown synchronously
                val latch = java.util.concurrent.CountDownLatch(1)
                var errorMessage: String? = null
                
                activity.runOnUiThread {
                    try {
                        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
                        if (printManager == null) {
                            throw IllegalStateException("Print service not available")
                        }
                        
                        // Create a custom print adapter for text content
                        val printAdapter = createTextPrintDocumentAdapter(sanitizedContent)
                        
                        // Get configured paper size from printer profile
                        val profile = printerConfigManager.getLastUsedProfile() 
                            ?: printerConfigManager.getAllProfiles().firstOrNull { it.enabled }
                        
                        val mediaSize = when (profile?.paperWidth) {
                            PrinterConfigManager.PAPER_58MM -> android.print.PrintAttributes.MediaSize.ISO_A7
                            PrinterConfigManager.PAPER_112MM -> android.print.PrintAttributes.MediaSize.ISO_A4
                            else -> android.print.PrintAttributes.MediaSize.ISO_A6
                        }
                        
                        val printAttributes = android.print.PrintAttributes.Builder()
                            .setMediaSize(mediaSize)
                            .setResolution(android.print.PrintAttributes.Resolution("printer", "printer", 600, 600))
                            .setColorMode(android.print.PrintAttributes.COLOR_MODE_MONOCHROME)
                            .build()
                        
                        AppLogger.d("Opening print dialog with content length: ${sanitizedContent.length}", TAG)
                        printManager.print("Web Print Job", printAdapter, printAttributes)
                        AppLogger.d("Print dialog opened successfully", TAG)
                    } catch (e: Exception) {
                        AppLogger.e("Error opening print dialog", e, TAG)
                        errorMessage = e.message ?: "Unknown error"
                    } finally {
                        latch.countDown()
                    }
                }
                
                // Wait for the UI thread operation to complete (with timeout)
                if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    AppLogger.w("Print dialog operation timed out", TAG)
                }
                
                if (errorMessage != null) {
                    return createErrorResponse("Failed to open print dialog: $errorMessage")
                }
                
                createSuccessResponse("Print dialog opened")
            } catch (e: Exception) {
                AppLogger.e("Error in printWebContent", e, TAG)
                createErrorResponse("Failed to open print dialog: ${e.message ?: "Unknown error"}")
            }
        } else {
            // Checkbox is UNCHECKED → Direct print using configured printer SDK (no dialog)
            AppLogger.d("Direct print (no dialog) - showPrintDialog: $showPrintDialog", TAG)
            val result = unifiedPrinterHandler.print(sanitizedContent, printerType)
            return result.toJson()
        }
    }
    
    /**
     * Create a PrintDocumentAdapter for text content
     */
    private fun createTextPrintDocumentAdapter(text: String): PrintDocumentAdapter {
        return object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback,
                extras: android.os.Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                
                val printInfo = PrintDocumentInfo.Builder("Web Print Job")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                
                callback.onLayoutFinished(printInfo, true)
            }
            
            override fun onWrite(
                pages: Array<out android.print.PageRange>,
                destination: android.os.ParcelFileDescriptor,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback
            ) {
                var outputStream: java.io.FileOutputStream? = null
                var writer: java.io.PrintWriter? = null
                
                try {
                    outputStream = java.io.FileOutputStream(destination.fileDescriptor)
                    writer = java.io.PrintWriter(outputStream)
                    
                    // Write text content
                    writer.print(text)
                    writer.flush()
                    
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onWriteCancelled()
                    } else {
                        // Return the pages that were written (all pages in this case)
                        val writtenPages = if (pages.isEmpty()) {
                            arrayOf(PageRange(0, -1)) // All pages
                        } else {
                            pages
                        }
                        callback.onWriteFinished(writtenPages)
                    }
                } catch (e: Exception) {
                    AppLogger.e("Error writing print content", e, TAG)
                    callback.onWriteFailed(e.message)
                } finally {
                    writer?.close()
                    outputStream?.close()
                }
            }
        }
    }
    
    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Show native Android print dialog for the current WebView content
     * This is called when window.print() is invoked from JavaScript
     * ALWAYS shows print dialog (removed conditional check)
     * 
     * @return JSON string with success status
     */
    @JavascriptInterface
    fun showNativePrintDialog(): String {
        return try {
            val showPrintDialog = preferencesManager.showPrintDialog
            
            AppLogger.d("showNativePrintDialog called - showPrintDialog: $showPrintDialog", TAG)
            
            if (showPrintDialog) {
                // Checkbox is CHECKED → Show Android print dialog for WebView content
                activity.runOnUiThread {
                    try {
                        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
                        if (printManager == null) {
                            AppLogger.e("Print service not available", null, TAG)
                            return@runOnUiThread
                        }
                        
                        // Create print adapter from WebView (prints current page content)
                        val printAdapter = webView.createPrintDocumentAdapter("Web Page")
                        
                        // Get configured paper size from printer profile
                        val profile = printerConfigManager.getLastUsedProfile() 
                            ?: printerConfigManager.getAllProfiles().firstOrNull { it.enabled }
                        
                        val mediaSize = when (profile?.paperWidth) {
                            PrinterConfigManager.PAPER_58MM -> android.print.PrintAttributes.MediaSize.ISO_A7
                            PrinterConfigManager.PAPER_112MM -> android.print.PrintAttributes.MediaSize.ISO_A4
                            else -> android.print.PrintAttributes.MediaSize.ISO_A6
                        }
                        
                        val printAttributes = android.print.PrintAttributes.Builder()
                            .setMediaSize(mediaSize)
                            .setResolution(android.print.PrintAttributes.Resolution("printer", "printer", 600, 600))
                            .setColorMode(android.print.PrintAttributes.COLOR_MODE_MONOCHROME)
                            .build()
                        
                        AppLogger.d("Opening native print dialog for WebView content", TAG)
                        printManager.print("Web Page Print", printAdapter, printAttributes)
                        AppLogger.d("Native print dialog opened successfully", TAG)
                    } catch (e: Exception) {
                        AppLogger.e("Error opening native print dialog", e, TAG)
                    }
                }
                createSuccessResponse("Print dialog opened")
            } else {
                // Checkbox is UNCHECKED → Direct print using configured printer SDK (no dialog)
                AppLogger.d("Direct print (no dialog) - extracting page content", TAG)
                
                // Extract page content and print directly
                activity.runOnUiThread {
                    webView.evaluateJavascript("""
                        (function() {
                            try {
                                // Get page content - prefer text content
                                let content = '';
                                // Try to get main content area first
                                const mainContent = document.querySelector('main, .content, .main-content, #content, #main, article');
                                if (mainContent) {
                                    content = mainContent.innerText || mainContent.textContent || '';
                                } else {
                                    content = document.body.innerText || document.body.textContent || '';
                                }
                                // Add page title
                                const title = document.title || 'Web Page';
                                content = title + '\\n' + '='.repeat(title.length) + '\\n\\n' + content;
                                // Return as JSON string to handle special characters
                                return JSON.stringify(content);
                            } catch (e) {
                                return JSON.stringify('Error extracting content: ' + e.message);
                            }
                        })();
                    """.trimIndent()) { jsonContent ->
                        try {
                            if (jsonContent != null && jsonContent != "null" && jsonContent.isNotBlank()) {
                                // Parse JSON string - evaluateJavascript returns a JSON-encoded string
                                val content = try {
                                    // The result is already a JSON string, so parse it
                                    JSONObject("{\"content\":$jsonContent}").getString("content")
                                } catch (e: Exception) {
                                    // Fallback: try to unquote the string directly
                                    jsonContent.removeSurrounding("\"").replace("\\n", "\n").replace("\\\"", "\"").replace("\\r", "\r")
                                }
                                
                                if (content.isNotBlank()) {
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        val result = unifiedPrinterHandler.print(content, "auto")
                                        activity.runOnUiThread {
                                            if (result.success) {
                                                Toast.makeText(activity, "Print sent successfully", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(activity, "Print failed: ${result.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                } else {
                                    AppLogger.w("Empty content extracted from page", TAG)
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.e("Error processing page content for direct print", e, TAG)
                        }
                    }
                }
                createSuccessResponse("Direct print initiated")
            }
        } catch (e: Exception) {
            AppLogger.e("Error in showNativePrintDialog", e, TAG)
            createErrorResponse("Failed to show print dialog: ${e.message ?: "Unknown error"}")
        }
    }
    
    /**
     * Check if print dialog can be opened
     * Returns true if print service is available
     * 
     * @return JSON string with availability status
     */
    @JavascriptInterface
    fun canShowPrintDialog(): String {
        return try {
            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
            val available = printManager != null
            AppLogger.d("canShowPrintDialog - available: $available", TAG)
            createSuccessResponse(
                data = mapOf("available" to available, "canShow" to available)
            )
        } catch (e: Exception) {
            AppLogger.e("Error checking print dialog availability", e, TAG)
            createErrorResponse("Error checking availability: ${e.message ?: "Unknown error"}")
        }
    }
    
    /**
     * Trigger print dialog for current WebView page
     * Convenience method that always shows print dialog
     * 
     * @return JSON string with success status
     */
    @JavascriptInterface
    fun triggerPrintDialog(): String {
        // Simply call showNativePrintDialog
        return showNativePrintDialog()
    }
    
    // ==================== Settings & Configuration Methods ====================
    
    @JavascriptInterface
    fun getKioskEnabled(): Boolean {
        return preferencesManager.kioskEnabled
    }
    
    @JavascriptInterface
    fun setKioskEnabledJs(enabled: Boolean): String {
        return try {
            preferencesManager.kioskEnabled = enabled
            activity.runOnUiThread {
                if (enabled) {
                    // Start lock task mode (kiosk mode)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            activity.startLockTask()
                        } catch (e: Exception) {
                            AppLogger.w("Failed to start lock task: ${e.message}", TAG)
                        }
                    }
                } else {
                    // Stop lock task mode
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            activity.stopLockTask()
                        } catch (e: Exception) {
                            AppLogger.w("Failed to stop lock task: ${e.message}", TAG)
                        }
                    }
                }
            }
            createSuccessResponse("Kiosk mode ${if (enabled) "enabled" else "disabled"}")
        } catch (e: Exception) {
            AppLogger.e("Error setting kiosk mode", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun getAutoStartEnabled(): Boolean {
        return preferencesManager.autoStartEnabled
    }
    
    @JavascriptInterface
    fun setAutoStartEnabledJs(enabled: Boolean): String {
        return try {
            preferencesManager.autoStartEnabled = enabled
            createSuccessResponse("Auto-start ${if (enabled) "enabled" else "disabled"}")
        } catch (e: Exception) {
            AppLogger.e("Error setting auto-start", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Get the current state of "Show Print Dialog" preference
     * 
     * This allows the webapp to check if print dialog is enabled
     * and automatically trigger print dialog when needed (e.g., on checkout submit)
     * 
     * @return true if print dialog should be shown, false for direct print
     */
    @JavascriptInterface
    fun getShowPrintDialog(): Boolean {
        return preferencesManager.showPrintDialog
    }
    
    /**
     * Set the "Show Print Dialog" preference
     * 
     * @param enabled true to show print dialog, false for direct print
     */
    @JavascriptInterface
    fun setShowPrintDialog(enabled: Boolean): String {
        return try {
            preferencesManager.showPrintDialog = enabled
            createSuccessResponse("Print dialog ${if (enabled) "enabled" else "disabled"}")
        } catch (e: Exception) {
            AppLogger.e("Error setting print dialog preference", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== Unified Print Methods ====================
    
    /**
     * STEP 8: Print from WebView - Auto-selects best printer
     * STEP 4: Daily use - no printer type selection needed
     * Always uses "auto" to select best available printer
     */
    @JavascriptInterface
    fun printFromWeb(text: String, prefer: String = "auto"): String {
        AppLogger.d("printFromWeb called with text length: ${text.length}, prefer: $prefer", TAG)
        
        // STEP 4: For daily use, always use "auto" to auto-select best printer
        val actualPrefer = if (prefer.isBlank() || prefer == "auto") "auto" else prefer
        
        // Enhanced HTML detection - check for HTML tags and structure
        val trimmedText = text.trim()
        val isHtml = trimmedText.startsWith("<!DOCTYPE", ignoreCase = true) || 
                     trimmedText.startsWith("<html", ignoreCase = true) ||
                     (trimmedText.contains("<html", ignoreCase = true) && trimmedText.contains("</html>", ignoreCase = true)) ||
                     (trimmedText.contains("<body", ignoreCase = true) || trimmedText.contains("<div", ignoreCase = true) || 
                      trimmedText.contains("<table", ignoreCase = true) || trimmedText.contains("<style", ignoreCase = true))
        
        if (isHtml) {
            AppLogger.d("Detected HTML content (${text.length} chars), using bitmap printing via printHtmlInvoice", TAG)
            // Use bitmap printing for HTML content - this renders HTML in WebView and prints as bitmap
            return printHtmlInvoice(text, actualPrefer)
        }
        
        // For plain text, use text-based printing
        AppLogger.d("Plain text detected, using text-based printing", TAG)
        val result = unifiedPrinterHandler.print(text, actualPrefer)
        
        activity.runOnUiThread {
            if (result.success) {
                Toast.makeText(activity, "Print sent successfully", Toast.LENGTH_SHORT).show()
            } else {
                // STEP 9: Show print failed dialog with Retry/Settings
                showPrintFailedDialog(result.message, text, actualPrefer)
            }
        }
        return result.toJson()
    }
    
    /**
     * STEP 9: Show print failed dialog with Retry and Settings buttons
     */
    private fun showPrintFailedDialog(errorMessage: String, textToPrint: String, printerType: String) {
        AlertDialog.Builder(activity)
            .setTitle("❌ Print Failed")
            .setMessage("Print failed: $errorMessage\n\nPlease check:\n• Printer is powered on\n• Printer is connected\n• Paper is loaded")
            .setPositiveButton("Retry") { _, _ ->
                // Retry print
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val result = unifiedPrinterHandler.print(textToPrint, printerType)
                    activity.runOnUiThread {
                        if (result.success) {
                            Toast.makeText(activity, "Print successful!", Toast.LENGTH_SHORT).show()
                        } else {
                            showPrintFailedDialog(result.message, textToPrint, printerType)
                        }
                    }
                }
            }
            .setNeutralButton("Printer Settings") { _, _ ->
                // STEP 7: Open native printer settings
                openPrinterManagement()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    @JavascriptInterface
    fun printBarcode(
        data: String, 
        barcodeType: Int = 73, 
        height: Int = 50, 
        width: Int = 2, 
        position: Int = 0, 
        prefer: String = "auto"
    ): String {
        val result = unifiedPrinterHandler.printBarcode(data, barcodeType, height, width, position, prefer)
        return result.toJson()
    }
    
    @JavascriptInterface
    fun printQRCode(
        data: String, 
        size: Int = 3, 
        errorCorrection: Int = 0, 
        prefer: String = "auto"
    ): String {
        val result = unifiedPrinterHandler.printQRCode(data, size, errorCorrection, prefer)
        return result.toJson()
    }
    
    // ==================== AutoReplyPrint Methods ====================
    
    @JavascriptInterface
    fun isAutoReplyPrintAvailable(): String {
        return try {
            val available = autoReplyPrint.isAvailable()
            createSuccessResponse(data = mapOf("available" to available))
        } catch (e: Exception) {
            AppLogger.e("Error checking AutoReplyPrint availability", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun autoreplyprintDiscoverPrinters(): String {
        return try {
            if (!autoReplyPrint.isAvailable()) {
                return createErrorResponse("AutoReplyPrint SDK not available")
            }
            
            val discoveryStarted = autoReplyPrint.startDiscover { printerDevice ->
                try {
                    val deviceClass = printerDevice::class.java
                    val printerMap = mutableMapOf<String, Any>()
                    
                    // Extract printer information using reflection
                    try {
                        val nameField = deviceClass.getField("printer_name")
                        printerMap["name"] = nameField.get(printerDevice)?.toString() ?: "Unknown"
                    } catch (_: Exception) {
                        printerMap["name"] = "Unknown Printer"
                    }
                    
                    try {
                        val addressField = deviceClass.getField("printer_address")
                        printerMap["address"] = addressField.get(printerDevice)?.toString() ?: ""
                    } catch (_: Exception) {
                        printerMap["address"] = ""
                    }
                    
                    try {
                        val typeField = deviceClass.getField("connection_type")
                        printerMap["connectionType"] = typeField.get(printerDevice)?.toString() ?: "Unknown"
                    } catch (_: Exception) {
                        printerMap["connectionType"] = "Unknown"
                    }
                    
                    printerMap["deviceObject"] = printerDevice.hashCode().toString()
                    
                    // Notify JavaScript about discovered printer
                    activity.runOnUiThread {
                        val printerJson = JSONObject(printerMap as Map<*, *>)
                        webView.evaluateJavascript(
                            "if (window.onAutoReplyPrintDiscovered) window.onAutoReplyPrintDiscovered($printerJson);",
                            null
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.e("Error processing discovered printer", e, TAG)
                }
            }
            
            if (discoveryStarted) {
                createSuccessResponse("Discovery started")
            } else {
                createErrorResponse("Failed to start discovery")
            }
        } catch (e: Exception) {
            AppLogger.e("Error starting AutoReplyPrint discovery", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun autoreplyprintStopDiscovery(): String {
        return try {
            val stopped = autoReplyPrint.stopDiscover()
            if (stopped) {
                createSuccessResponse()
            } else {
                createErrorResponse("Failed to stop discovery")
            }
        } catch (e: Exception) {
            AppLogger.e("Error stopping AutoReplyPrint discovery", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun autoreplyprintIsConnected(): String {
        return try {
            val connected = autoReplyPrint.isConnected()
            createSuccessResponse(data = mapOf("connected" to connected))
        } catch (e: Exception) {
            AppLogger.e("Error checking AutoReplyPrint connection", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun autoreplyprintDisconnect(): String {
        return try {
            val disconnected = autoReplyPrint.disconnect()
            if (disconnected) {
                createSuccessResponse()
            } else {
                createErrorResponse("Failed to disconnect")
            }
        } catch (e: Exception) {
            AppLogger.e("Error disconnecting AutoReplyPrint", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun autoreplyprintPrintText(text: String): String {
        return try {
            if (!autoReplyPrint.isAvailable()) {
                return createErrorResponse("AutoReplyPrint SDK not available")
            }
            
            if (!autoReplyPrint.isConnected()) {
                return createErrorResponse("Printer not connected")
            }
            
            // Convert text to bitmap for printing
            val bitmap = createTextBitmap(text, 384) // 58mm width default
            val result = autoReplyPrint.printBitmap(bitmap)
            
            if (result) {
                createSuccessResponse()
            } else {
                createErrorResponse("Print failed")
            }
        } catch (e: Exception) {
            AppLogger.e("Error printing text with AutoReplyPrint", e, TAG)
            createErrorResponse(e.message ?: "Print error")
        }
    }
    
    @JavascriptInterface
    fun autoreplyprintPrintBitmap(base64Image: String): String {
        return try {
            if (!autoReplyPrint.isAvailable()) {
                return createErrorResponse("AutoReplyPrint SDK not available")
            }
            
            if (!autoReplyPrint.isConnected()) {
                return createErrorResponse("Printer not connected")
            }
            
            // Decode base64 image
            val imageBytes = android.util.Base64.decode(base64Image, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return createErrorResponse("Failed to decode image")
            
            val result = autoReplyPrint.printBitmap(bitmap)
            
            if (result) {
                """{"ok":true}"""
            } else {
                """{"ok":false,"msg":"Print failed"}"""
            }
        } catch (e: Exception) {
            AppLogger.e("Error printing bitmap with AutoReplyPrint", e, TAG)
            """{"ok":false,"msg":"${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun autoreplyprintGetStatus(): String {
        return try {
            val obj = JSONObject()
            obj.put("available", autoReplyPrint.isAvailable())
            obj.put("connected", autoReplyPrint.isConnected())
            
            val resolution = autoReplyPrint.getPrinterResolution()
            if (resolution != null) {
                try {
                    val resClass = resolution::class.java
                    val widthMethod = resClass.getMethod("getWidthMM")
                    val heightMethod = resClass.getMethod("getHeightMM")
                    val dotsPerMMMethod = resClass.getMethod("getDotsPerMM")
                    
                    obj.put("resolution", JSONObject().apply {
                        put("widthMM", widthMethod.invoke(resolution))
                        put("heightMM", heightMethod.invoke(resolution))
                        put("dotsPerMM", dotsPerMMMethod.invoke(resolution))
                    })
                } catch (_: Exception) {
                    // Resolution info not available
                }
            }
            
            createSuccessResponse(data = mapOf("status" to obj))
        } catch (e: Exception) {
            AppLogger.e("Error getting AutoReplyPrint status", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Helper function to create bitmap from text for printing
     */
    private fun createTextBitmap(text: String, width: Int): android.graphics.Bitmap {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 24f
            isAntiAlias = true
        }
        
        val bounds = android.graphics.Rect()
        val lines = text.lines()
        var maxWidth = 0
        
        lines.forEach { line ->
            paint.getTextBounds(line, 0, line.length, bounds)
            if (bounds.width() > maxWidth) maxWidth = bounds.width()
        }
        
        val height = (bounds.height() * (lines.size + 2)).coerceAtLeast(100)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        
        var y = bounds.height().toFloat()
        lines.forEach { line ->
            canvas.drawText(line, 0f, y, paint)
            y += bounds.height() + 10
        }
        
        return bitmap
    }
    
    // ==================== Permission Methods ====================
    
    @JavascriptInterface
    fun requestNotificationsPermission() {
        // Delegate to activity - will be moved to PermissionManager later
        activity.runOnUiThread {
            activity.requestNotificationsPermissionIfNeeded()
        }
    }
    
    @JavascriptInterface
    fun checkPermissions(permsJson: String?): String {
        return try {
            val list = parsePermissionsJson(permsJson)
            val granted = JSONArray()
            val denied = JSONArray()
            list.forEach { p ->
                val g = ContextCompat.checkSelfPermission(activity, p) == 
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (g) granted.put(p) else denied.put(p)
            }
            createSuccessResponse(data = mapOf("granted" to granted, "denied" to denied))
        } catch (e: Exception) {
            AppLogger.e("Error checking permissions", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Parse permissions from JSON string (array or comma-separated string)
     */
    private fun parsePermissionsJson(permsJson: String?): List<String> {
        if (permsJson.isNullOrBlank()) return emptyList()
        
        return try {
            // Try to parse as JSON array
            val jsonArray = JSONArray(permsJson)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val perm = jsonArray.optString(i, "")
                if (perm.isNotBlank()) {
                    list.add(perm)
                }
            }
            list
        } catch (e: Exception) {
            // If not JSON array, try as comma-separated string
            permsJson.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
    }
    
    @JavascriptInterface
    fun requestPermissions(permsJson: String?): String {
        return try {
            val array = parsePermissionsJson(permsJson).toTypedArray()
            if (array.isEmpty()) {
                return createErrorResponse("No permissions provided")
            }
            // Permissions are now handled via JS bridge callback - simplified
            // The actual permission request happens through the WebView's permission handling
            createSuccessResponse(message = "Permission request initiated")
        } catch (e: Exception) {
            AppLogger.e("Error requesting permissions", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun requestAllPermissions(): String {
        return try {
            // Common permissions that the app might need
            val commonPerms = listOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ).filter {
                // Filter based on Android version requirements
                if (it == android.Manifest.permission.WRITE_EXTERNAL_STORAGE) {
                    android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P
                } else {
                    true
                }
            }
            // Permissions are now handled via JS bridge callback - simplified
            createSuccessResponse(message = "Permission request initiated")
        } catch (e: Exception) {
            AppLogger.e("Error requesting all permissions", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== Printer Connection Methods ====================
    
    @JavascriptInterface
    fun getPrinterConnectionStatus(): String {
        return try {
            val profiles = printerConfigManager.getAllProfiles()
            val statusArray = JSONArray()
            
            profiles.forEach { profile ->
                val status = JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("type", profile.type)
                    put("enabled", profile.enabled)
                    // Connection status would need to be checked per printer type
                    put("connected", false) // Placeholder
                }
                statusArray.put(status)
            }
            
            val result = JSONObject().apply {
                put("ok", true)
                put("printers", statusArray)
            }
            result.toString()
        } catch (e: Exception) {
            AppLogger.e("Error getting printer connection status", e, TAG)
            """{"ok":false,"msg":"${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun getConnectedPrinterNames(): String {
        return try {
            val profiles = printerConfigManager.getAllProfiles().filter { it.enabled }
            val names = profiles.map { it.name }
            val result = JSONObject().apply {
                put("ok", true)
                put("names", JSONArray(names))
            }
            result.toString()
        } catch (e: Exception) {
            AppLogger.e("Error getting printer names", e, TAG)
            """{"ok":false,"msg":"${e.message}"}"""
        }
    }
    
    // ==================== Receipt Formatting Methods ====================
    
    @JavascriptInterface
    fun formatAndPrintInvoice(
        saleDataJson: String, 
        configJson: String = "{}", 
        prefer: String = "auto"
    ): String {
        return try {
            val saleData = JSONObject(saleDataJson)
            val config = JSONObject(configJson)
            
            // Check if we should use bitmap printing (from HTML element)
            val htmlContent = saleData.optString("html_content", "")
            val elementId = saleData.optString("element_id", "")
            val useBitmap = saleData.optBoolean("use_bitmap", true) // Default to bitmap printing
            
            if (htmlContent.isNotEmpty()) {
                // Use bitmap printing with provided HTML
                AppLogger.d("formatAndPrintInvoice: Using bitmap printing with provided HTML", TAG)
                return printHtmlInvoice(htmlContent, prefer)
            }
            
            // Check if bitmap printing is enabled (default: true)
            if (useBitmap) {
                // Convert JSON data to HTML and use bitmap printing via ReceiptFormatter
                AppLogger.d("formatAndPrintInvoice: Converting JSON to HTML for bitmap printing", TAG)
                val receiptConfig = com.elintpos.wrapper.escpos.ReceiptFormatter.ReceiptConfig(
                    paperWidth = config.optInt("paperWidth", com.elintpos.wrapper.printer.PrinterConfigManager.PAPER_80MM),
                    leftMargin = config.optInt("leftMargin", 0),
                    rightMargin = config.optInt("rightMargin", 0),
                    lineSpacing = config.optInt("lineSpacing", 30),
                    includeBarcode = config.optBoolean("includeBarcode", false),
                    includeQR = config.optBoolean("includeQR", false),
                    compactLayout = config.optBoolean("compactLayout", false)
                )
                
                val formatter = com.elintpos.wrapper.escpos.ReceiptFormatter(activity)
                val result = formatter.formatAndPrintInvoiceAsBitmap(saleData, receiptConfig, prefer)
                return result.toJson()
            } else {
                // Text printing removed - always use bitmap printing
                AppLogger.d("formatAndPrintInvoice: Text printing disabled, using bitmap printing", TAG)
                val receiptConfig = com.elintpos.wrapper.escpos.ReceiptFormatter.ReceiptConfig(
                    paperWidth = config.optInt("paperWidth", com.elintpos.wrapper.printer.PrinterConfigManager.PAPER_80MM),
                    leftMargin = config.optInt("leftMargin", 0),
                    rightMargin = config.optInt("rightMargin", 0),
                    lineSpacing = config.optInt("lineSpacing", 30),
                    includeBarcode = config.optBoolean("includeBarcode", false),
                    includeQR = config.optBoolean("includeQR", false),
                    compactLayout = config.optBoolean("compactLayout", false)
                )
                
                val formatter = com.elintpos.wrapper.escpos.ReceiptFormatter(activity)
                val result = formatter.formatAndPrintInvoiceAsBitmap(saleData, receiptConfig, prefer)
                return result.toJson()
            }
            
        } catch (e: Exception) {
            AppLogger.e("Error formatting and printing invoice", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
   
    
    @JavascriptInterface
    fun formatAndPrintReceipt(
        saleDataJson: String, 
        configJson: String = "{}", 
        prefer: String = "auto"
    ): String {
        return try {
            // Text printing removed - use bitmap printing for receipts too
            val saleData = JSONObject(saleDataJson)
            val config = JSONObject(configJson)
            
            val receiptConfig = com.elintpos.wrapper.escpos.ReceiptFormatter.ReceiptConfig(
                paperWidth = config.optInt("paperWidth", com.elintpos.wrapper.printer.PrinterConfigManager.PAPER_80MM),
                leftMargin = config.optInt("leftMargin", 0),
                rightMargin = config.optInt("rightMargin", 0),
                lineSpacing = config.optInt("lineSpacing", 30),
                includeBarcode = config.optBoolean("includeBarcode", false),
                includeQR = config.optBoolean("includeQR", false),
                compactLayout = config.optBoolean("compactLayout", false)
            )
            
            val formatter = com.elintpos.wrapper.escpos.ReceiptFormatter(activity)
            val result = formatter.formatAndPrintInvoiceAsBitmap(saleData, receiptConfig, prefer)
            return result.toJson()
        } catch (e: Exception) {
            AppLogger.e("Error formatting and printing receipt", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun formatAndPrintKitchenOrder(
        orderDataJson: String, 
        configJson: String = "{}", 
        prefer: String = "auto"
    ): String {
        return try {
            // Text printing removed - use bitmap printing for kitchen orders too
            // Convert kitchen order data to invoice format for bitmap printing
            val orderData = JSONObject(orderDataJson)
            val config = JSONObject(configJson)
            
            // Convert kitchen order to invoice format
            val saleData = JSONObject()
            saleData.put("store_name", orderData.optString("store_name", "Kitchen"))
            saleData.put("store_address", orderData.optString("store_address", ""))
            saleData.put("store_phone", orderData.optString("store_phone", ""))
            saleData.put("invoice_number", "ORDER-${orderData.optString("order_number", "")}")
            saleData.put("sale_date", orderData.optString("order_time", ""))
            saleData.put("cashier_name", orderData.optString("table_number", ""))
            saleData.put("items", orderData.optJSONArray("items") ?: org.json.JSONArray())
            saleData.put("total", 0.0)
            saleData.put("payment_method", "")
            
            val receiptConfig = com.elintpos.wrapper.escpos.ReceiptFormatter.ReceiptConfig(
                paperWidth = config.optInt("paperWidth", com.elintpos.wrapper.printer.PrinterConfigManager.PAPER_80MM),
                leftMargin = config.optInt("leftMargin", 0),
                rightMargin = config.optInt("rightMargin", 0),
                lineSpacing = config.optInt("lineSpacing", 30),
                includeBarcode = false,
                includeQR = false,
                compactLayout = true
            )
            
            val formatter = com.elintpos.wrapper.escpos.ReceiptFormatter(activity)
            val result = formatter.formatAndPrintInvoiceAsBitmap(saleData, receiptConfig, prefer)
            return result.toJson()
        } catch (e: Exception) {
            AppLogger.e("Error formatting and printing kitchen order", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== SDK Management Methods ====================
    
    @JavascriptInterface
    fun downloadEpsonSdk(): String {
        return try {
            // Use async operation to avoid blocking main thread
            // Note: @JavascriptInterface methods must return synchronously,
            // so we start the download and return immediately
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = sdkDownloader.downloadEpsonSdk()
                    val response = when (result) {
                        is SdkDownloader.DownloadResult.Success -> 
                            """{"ok":true,"msg":"${result.message}"}"""
                        is SdkDownloader.DownloadResult.Error -> 
                            """{"ok":false,"msg":"${result.message}"}"""
                        is SdkDownloader.DownloadResult.Info -> 
                            """{"ok":true,"msg":"${result.message}"}"""
                    }
                    // Notify JavaScript about completion
                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            "if (window.onEpsonSdkDownloadComplete) window.onEpsonSdkDownloadComplete($response);",
                            null
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.e("Error downloading Epson SDK", e, TAG)
                    val errorResponse = """{"ok":false,"msg":"Download error: ${e.message}"}"""
                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            "if (window.onEpsonSdkDownloadComplete) window.onEpsonSdkDownloadComplete($errorResponse);",
                            null
                        )
                    }
                }
            }
            // Return immediately - download happens asynchronously
            """{"ok":true,"msg":"Download started. Check onEpsonSdkDownloadComplete callback for result."}"""
        } catch (e: Exception) {
            AppLogger.e("Error starting Epson SDK download", e, TAG)
            """{"ok":false,"msg":"Failed to start download: ${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun downloadXPrinterSdk(): String {
        return try {
            // Use async operation to avoid blocking main thread
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = sdkDownloader.downloadXPrinterSdk()
                    val response = when (result) {
                        is SdkDownloader.DownloadResult.Success -> 
                            """{"ok":true,"msg":"${result.message}"}"""
                        is SdkDownloader.DownloadResult.Error -> 
                            """{"ok":false,"msg":"${result.message}"}"""
                        is SdkDownloader.DownloadResult.Info -> 
                            """{"ok":true,"msg":"${result.message}"}"""
                    }
                    // Notify JavaScript about completion
                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            "if (window.onXPrinterSdkDownloadComplete) window.onXPrinterSdkDownloadComplete($response);",
                            null
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.e("Error downloading XPrinter SDK", e, TAG)
                    val errorResponse = """{"ok":false,"msg":"Download error: ${e.message}"}"""
                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            "if (window.onXPrinterSdkDownloadComplete) window.onXPrinterSdkDownloadComplete($errorResponse);",
                            null
                        )
                    }
                }
            }
            // Return immediately - download happens asynchronously
            """{"ok":true,"msg":"Download started. Check onXPrinterSdkDownloadComplete callback for result."}"""
        } catch (e: Exception) {
            AppLogger.e("Error starting XPrinter SDK download", e, TAG)
            """{"ok":false,"msg":"Failed to start download: ${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun checkSdkAvailability(): String {
        return try {
            val availability = sdkDownloader.checkSdkAvailability()
            val json = JSONObject(availability as Map<*, *>)
            """{"ok":true,"availability":$json}"""
        } catch (e: Exception) {
            AppLogger.e("Error checking SDK availability", e, TAG)
            """{"ok":false,"msg":"Error checking SDK availability: ${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun installAllSdks(): String {
        return try {
            // Use async operation to avoid blocking main thread
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val epsonResult = sdkDownloader.downloadEpsonSdk()
                    val xprinterResult = sdkDownloader.downloadXPrinterSdk()
                    
                    val results = mutableListOf<String>()
                    
                    when (epsonResult) {
                        is SdkDownloader.DownloadResult.Success -> 
                            results.add("Epson SDK: ${epsonResult.message}")
                        is SdkDownloader.DownloadResult.Error -> 
                            results.add("Epson SDK Error: ${epsonResult.message}")
                        is SdkDownloader.DownloadResult.Info -> 
                            results.add("Epson SDK: ${epsonResult.message}")
                    }
                    
                    when (xprinterResult) {
                        is SdkDownloader.DownloadResult.Success -> 
                            results.add("XPrinter SDK: ${xprinterResult.message}")
                        is SdkDownloader.DownloadResult.Error -> 
                            results.add("XPrinter SDK Error: ${xprinterResult.message}")
                        is SdkDownloader.DownloadResult.Info -> 
                            results.add("XPrinter SDK: ${xprinterResult.message}")
                    }
                    
                    val response = """{"ok":true,"msg":"${results.joinToString("; ")}"}"""
                    // Notify JavaScript about completion
                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            "if (window.onAllSdksInstallComplete) window.onAllSdksInstallComplete($response);",
                            null
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.e("Error installing all SDKs", e, TAG)
                    val errorResponse = """{"ok":false,"msg":"Install error: ${e.message}"}"""
                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            "if (window.onAllSdksInstallComplete) window.onAllSdksInstallComplete($errorResponse);",
                            null
                        )
                    }
                }
            }
            // Return immediately - installation happens asynchronously
            """{"ok":true,"msg":"Installation started. Check onAllSdksInstallComplete callback for result."}"""
        } catch (e: Exception) {
            AppLogger.e("Error starting SDK installation", e, TAG)
            """{"ok":false,"msg":"Failed to start installation: ${e.message}"}"""
        }
    }
    
    // ==================== Session Management Methods ====================
    
    @JavascriptInterface
    fun logout(): String {
        return try {
            preferencesManager.clearSession()
            activity.runOnUiThread {
                // HIDE InitialSetupActivity - redirect to MainActivity instead
                val intent = android.content.Intent(activity, com.elintpos.wrapper.MainActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                activity.startActivity(intent)
                activity.finish()
            }
            createSuccessResponse("Logged out successfully")
        } catch (e: Exception) {
            AppLogger.e("Error during logout", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun clearSession(): String {
        return try {
            preferencesManager.clearSession()
            createSuccessResponse("Session cleared")
        } catch (e: Exception) {
            AppLogger.e("Error clearing session", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== Printer Management UI Methods ====================
    
    @JavascriptInterface
    fun openPrinterManagement(): String {
        return try {
            activity.runOnUiThread {
                val intent = android.content.Intent(activity, com.elintpos.wrapper.PrinterSetupActivity::class.java)
                activity.startActivity(intent)
            }
            createSuccessResponse("Printer management opened")
        } catch (e: Exception) {
            AppLogger.e("Error opening printer management", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * STEP 6: Show simple Quick Settings popup in WebView
     * Shows: Printer Name, Status, Paper Size, Test Print, Advanced Settings
     * User-friendly - no technical details
     */
    @JavascriptInterface
    fun showPrinterSettingsPopup(): String {
        return try {
            activity.runOnUiThread {
                activity.showPrinterSettingsPopup()
            }
            createSuccessResponse("Printer settings popup shown")
        } catch (e: Exception) {
            AppLogger.e("Error showing printer settings popup", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * STEP 6: Test print from WebView (simple)
     * Returns success/failure - shows dialog on failure
     */
    @JavascriptInterface
    fun testPrint(): String {
        return try {
            val profile = printerConfigManager.getLastUsedProfile() 
                ?: printerConfigManager.getAllProfiles().firstOrNull { it.enabled }
            
            if (profile == null) {
                return createErrorResponse("No printer configured. Please configure a printer first.")
            }
            
            val testText = """
                ================================
                PRINTER TEST
                ================================
                
                Printer: ${profile.name}
                Paper: ${profile.paperWidth} dots
                
                If you can read this,
                your printer is working correctly!
                
                Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}
                
                ================================
                END OF TEST
                ================================
            """.trimIndent()
            
            // Print in background
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val result = unifiedPrinterHandler.print(testText, profile.type)
                activity.runOnUiThread {
                    if (result.success) {
                        Toast.makeText(activity, "Test print sent successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        // Show print failed dialog
                        showPrintFailedDialog(result.message, testText, profile.type)
                    }
                }
            }
            
            createSuccessResponse("Test print initiated")
        } catch (e: Exception) {
            AppLogger.e("Error initiating test print", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== Additional Methods - Batch 1 ====================
    // Methods that access MainActivity properties and internal methods
    
    @JavascriptInterface
    fun choosePrinterAndPrint(text: String): String {
        return try {
            // Use unified printer handler to print with auto-selection
            val result = unifiedPrinterHandler.print(text, "auto")
            activity.runOnUiThread {
                if (result.success) {
                    Toast.makeText(activity, "Print sent successfully", Toast.LENGTH_SHORT).show()
                } else {
                    showPrintFailedDialog(result.message, text, "auto")
                }
            }
            return result.toJson()
        } catch (e: Exception) {
            AppLogger.e("Error choosing printer and printing", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun listCrashLogs(): String {
        return try {
            val dir = java.io.File(activity.filesDir, "crash")
            if (!dir.exists()) return createSuccessResponse(data = mapOf("files" to JSONArray()))
            val arr = JSONArray()
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                val o = JSONObject()
                o.put("name", f.name)
                o.put("path", f.absolutePath)
                o.put("size", f.length())
                o.put("lastModified", f.lastModified())
                arr.put(o)
            }
            createSuccessResponse(data = mapOf("files" to arr))
        } catch (e: Exception) {
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun readCrashLog(path: String): String {
        return try {
            val file = java.io.File(path)
            if (!file.exists()) return createErrorResponse("Not found")
            val txt = file.readText()
            val o = JSONObject()
            o.put("ok", true)
            o.put("name", file.name)
            o.put("path", file.absolutePath)
            o.put("content", txt)
            o.toString()
        } catch (e: Exception) {
            """{"ok":false,"msg":"${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun shareCrashLog(path: String): String {
        return try {
            val file = java.io.File(path)
            if (!file.exists()) return createErrorResponse("Not found")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity, 
                "${activity.packageName}.fileprovider", 
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Crash log: ${file.name}")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share crash log"))
            createSuccessResponse()
        } catch (e: Exception) {
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== ESC/POS Printer Methods ====================
    
    @JavascriptInterface
    fun listPairedPrinters(): String {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "[]"
            val arr = JSONArray()
            adapter.bondedDevices?.forEach { device ->
                val obj = JSONObject()
                obj.put("name", device.name)
                obj.put("mac", device.address)
                arr.put(obj)
            }
            arr.toString()
        } catch (e: Exception) {
            """{"ok":false,"msg":"${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun connectPrinter(mac: String): String {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return """{"ok":false,"msg":"No BT"}"""
            val device = adapter.bondedDevices.firstOrNull { it.address.equals(mac, true) }
                ?: return """{"ok":false,"msg":"Not paired"}"""
            activity.escPosPrinter?.close()
            activity.escPosPrinter = BluetoothEscPosPrinter(activity)
            activity.escPosPrinter!!.connect(device)
            activity.isBtConnected = true
            activity.runOnUiThread {
                Toast.makeText(activity, "Bluetooth printer connected", Toast.LENGTH_SHORT).show()
            }
            """{"ok":true}"""
        } catch (e: Exception) {
            activity.isBtConnected = false
            """{"ok":false,"msg":"${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun printText(text: String, leftMargin: Int, rightMargin: Int, lineSpacing: Int, scale: Int): String {
        return try {
            activity.escPosPrinter?.printText(
                text, 
                leftMarginDots = leftMargin, 
                rightMarginDots = rightMargin, 
                lineSpacing = lineSpacing, 
                widthMultiplier = scale, 
                heightMultiplier = scale
            )
            createSuccessResponse()
        } catch (e: Exception) {
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun printTextScaled(text: String, leftMargin: Int, rightMargin: Int, lineSpacing: Int, widthMul: Int, heightMul: Int, pageWidthDots: Int): String {
        return try {
            activity.escPosPrinter?.printText(
                text, 
                leftMarginDots = leftMargin, 
                rightMarginDots = rightMargin, 
                lineSpacing = lineSpacing, 
                widthMultiplier = widthMul, 
                heightMultiplier = heightMul, 
                pageWidthDots = pageWidthDots
            )
            createSuccessResponse()
        } catch (e: Exception) {
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun listUsbPrinters(): String {
        return try {
            val p = UsbEscPosPrinter(activity)
            val devices = p.listPrinters()
            val arr = JSONArray()
            devices.forEach { d ->
                val obj = JSONObject()
                obj.put("vendorId", d.vendorId)
                obj.put("productId", d.productId)
                obj.put("deviceName", d.deviceName)
                arr.put(obj)
            }
            arr.toString()
        } catch (e: Exception) {
            """{"ok":false,"msg":"${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun requestUsbPermission(deviceName: String): String {
        return try {
            val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
                ?: return """{"ok":false,"msg":"Not found"}"""
            if (usbManager.hasPermission(device)) {
                """{"ok":true,"msg":"Already granted"}"""
            } else {
                activity.pendingUsbDeviceName = deviceName
                val pi = android.app.PendingIntent.getBroadcast(
                    activity,
                    0,
                    Intent(MainActivity.ACTION_USB_PERMISSION),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0
                )
                usbManager.requestPermission(device, pi)
                """{"ok":true,"msg":"Requested"}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"msg":"${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun connectUsbPrinter(deviceName: String): String {
        return try {
            val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
                ?: return """{"ok":false,"msg":"Not found"}"""
            if (!usbManager.hasPermission(device)) {
                activity.pendingUsbDeviceName = deviceName
                val pi = android.app.PendingIntent.getBroadcast(
                    activity,
                    0,
                    Intent(MainActivity.ACTION_USB_PERMISSION),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0
                )
                usbManager.requestPermission(device, pi)
                return """{"ok":false,"msg":"USB permission requested"}"""
            }
            activity.usbPrinter?.close()
            activity.usbPrinter = UsbEscPosPrinter(activity)
            activity.usbPrinter!!.connect(device)
            activity.isUsbConnected = true
            activity.runOnUiThread {
                Toast.makeText(activity, "USB printer connected", Toast.LENGTH_SHORT).show()
            }
            """{"ok":true}"""
        } catch (e: Exception) {
            activity.isUsbConnected = false
            """{"ok":false,"msg":"${e.message}"}"""
        }
    }
    
    @JavascriptInterface
    fun usbPrintText(text: String, leftMargin: Int, rightMargin: Int, lineSpacing: Int, scale: Int): String {
        return try {
            activity.usbPrinter?.printText(
                text, 
                leftMarginDots = leftMargin, 
                rightMarginDots = rightMargin, 
                lineSpacing = lineSpacing, 
                widthMultiplier = scale, 
                heightMultiplier = scale
            )
            createSuccessResponse()
        } catch (e: Exception) {
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun usbPrintTextScaled(text: String, leftMargin: Int, rightMargin: Int, lineSpacing: Int, widthMul: Int, heightMul: Int, pageWidthDots: Int): String {
        return try {
            activity.usbPrinter?.printText(
                text, 
                leftMarginDots = leftMargin, 
                rightMarginDots = rightMargin, 
                lineSpacing = lineSpacing, 
                widthMultiplier = widthMul, 
                heightMultiplier = heightMul, 
                pageWidthDots = pageWidthDots
            )
            createSuccessResponse()
        } catch (e: Exception) {
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun setDefaultPrintConfig(leftMargin: Int, rightMargin: Int, lineSpacing: Int, widthMul: Int, heightMul: Int, pageWidthDots: Int, linesPerPage: Int): String {
        return try {
            // Print config is now handled by PrinterConfigManager
            // This method is kept for backward compatibility but does nothing
            // Print settings should be configured through printer profiles
            AppLogger.d("setDefaultPrintConfig called - using PrinterConfigManager profiles instead", TAG)
            createSuccessResponse(message = "Print config is now managed through printer profiles")
        } catch (e: Exception) {
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    @JavascriptInterface
    fun btPrint(text: String): String {
        val result = unifiedPrinterHandler.print(text, "bluetooth")
        return result.toJson()
    }
    
    @JavascriptInterface
    fun usbPrint(text: String): String {
        val result = unifiedPrinterHandler.print(text, "usb")
        return result.toJson()
    }
    
    /**
     * STEP 5: Simple Printer Status for WebView
     * Returns user-friendly status only (no technical details)
     * Format: { connected: true/false, name: "Printer Name", paperSize: "80mm" }
     */
    @JavascriptInterface
    fun getPrinterStatus(): String {
        return try {
            // Get the active printer profile
            val profile = printerConfigManager.getLastUsedProfile() 
                ?: printerConfigManager.getAllProfiles().firstOrNull { it.enabled }
            
            if (profile == null) {
                return createSuccessResponse(
                    data = mapOf(
                        "connected" to false,
                        "name" to "No Printer",
                        "paperSize" to "Unknown",
                        "status" to "Not Configured"
                    )
                )
            }
            
            // Check connection status (simplified - just check if any connection exists)
            val isConnected = activity.isBtConnected || activity.isUsbConnected || activity.isLanConnected
            
            // Get paper size in user-friendly format
            val paperSize = when (profile.paperWidth) {
                PrinterConfigManager.PAPER_58MM -> "58mm"
                PrinterConfigManager.PAPER_80MM -> "80mm"
                PrinterConfigManager.PAPER_90MM -> "90mm"
                PrinterConfigManager.PAPER_112MM -> "112mm"
                else -> "${profile.paperWidth} dots"
            }
            
            createSuccessResponse(
                data = mapOf(
                    "connected" to isConnected,
                    "name" to profile.name,
                    "paperSize" to paperSize,
                    "status" to if (isConnected) "Connected" else "Not Connected",
                    "type" to profile.type
                )
            )
        } catch (e: Exception) {
            AppLogger.e("Error getting printer status", e, TAG)
            createErrorResponse("Error getting printer status: ${e.message ?: "Unknown error"}")
        }
    }
    
    /**
     * STEP 5: Get simple printer status (alternative method for backward compatibility)
     * Returns: { ok: true, connected: true/false, name: "...", paperSize: "80mm" }
     */
    @JavascriptInterface
    fun getSimplePrinterStatus(): String {
        return getPrinterStatus() // Use the same implementation
    }
    
    /**
     * Print HTML invoice as bitmap
     * 
     * Flow:
     * 1. HTML Invoice (WebView)
     * 2. ↓ fully render (CSS + logo)
     * 3. ↓ capture WebView as bitmap (FULL HEIGHT)
     * 4. ↓ scale bitmap to printer width (58mm / 80mm)
     * 5. ↓ Printer SDK → printBitmap()
     * 
     * @param htmlContent HTML content to render and print
     * @param preferType Preferred printer type (optional, default: "auto")
     * @return JSON string with success status and message
     * 
     * Example:
     * ```javascript
     * const result = ElintPOSNative.printHtmlInvoice(htmlContent, "auto");
     * const json = JSON.parse(result);
     * if (json.ok) {
     *   console.log("Print successful:", json.msg);
     * } else {
     *   console.error("Print failed:", json.msg);
     * }
     * ```
     */
    @JavascriptInterface
    fun printHtmlInvoice(htmlContent: String, preferType: String = "auto"): String {
        return try {
            AppLogger.d("printHtmlInvoice called with preferType: $preferType", TAG)
            
            // Validate HTML content
            if (htmlContent.isBlank()) {
                return createErrorResponse("HTML content is empty")
            }
            
            // Print HTML invoice as bitmap in background
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val result = unifiedPrinterHandler.printHtmlInvoice(htmlContent, preferType)
                    activity.runOnUiThread {
                        if (result.success) {
                            Toast.makeText(activity, "HTML invoice printed successfully", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "Print failed: ${result.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e("Error printing HTML invoice", e, TAG)
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Print error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            
            // Return immediately (async operation)
            createSuccessResponse("HTML invoice print initiated")
        } catch (e: Exception) {
            AppLogger.e("Error in printHtmlInvoice", e, TAG)
            createErrorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== End of JavaScript Interface Methods ====================
}

private fun MainActivity.parsePermissionsJson(permsJson: String?) {}
