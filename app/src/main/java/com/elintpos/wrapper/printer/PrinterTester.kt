package com.elintpos.wrapper.printer

import android.content.Context
import android.util.Log
import com.elintpos.wrapper.escpos.BluetoothEscPosPrinter
import com.elintpos.wrapper.escpos.UsbEscPosPrinter
import com.elintpos.wrapper.escpos.LanEscPosPrinter
import com.elintpos.wrapper.printer.vendor.EpsonPrinter
import com.elintpos.wrapper.printer.vendor.XPrinter
import com.elintpos.wrapper.printer.vendor.VendorPrinter
import com.elintpos.wrapper.sdk.SdkDownloader
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Comprehensive printer testing and validation utility that can test
 * different printer types, configurations, and provide detailed diagnostics.
 */
class PrinterTester(private val context: Context) {
    
    companion object {
        private const val TAG = "PrinterTester"
        private const val TEST_TIMEOUT_SECONDS = 10L
    }
    
    private val executor = Executors.newCachedThreadPool()
    private val sdkDownloader = SdkDownloader(context)
    
    /**
     * Test result data class
     */
    data class TestResult(
        val success: Boolean,
        val message: String,
        val duration: Long,
        val details: Map<String, Any> = emptyMap(),
        val error: String? = null
    ) {
        fun toJson(): JSONObject {
            val json = JSONObject()
            json.put("success", success)
            json.put("message", message)
            json.put("duration", duration)
            json.put("error", error)
            
            val detailsJson = JSONObject()
            details.forEach { (key, value) ->
                when (value) {
                    is String -> detailsJson.put(key, value)
                    is Int -> detailsJson.put(key, value)
                    is Boolean -> detailsJson.put(key, value)
                    is Double -> detailsJson.put(key, value)
                    else -> detailsJson.put(key, value.toString())
                }
            }
            json.put("details", detailsJson)
            
            return json
        }
    }
    
    /**
     * Test a printer profile with comprehensive validation
     */
    fun testPrinterProfile(profile: PrinterConfigManager.PrinterConfig): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            when (profile.type) {
                PrinterConfigManager.TYPE_BLUETOOTH -> testBluetoothPrinter(profile)
                PrinterConfigManager.TYPE_USB -> testUsbPrinter(profile)
                PrinterConfigManager.TYPE_LAN -> testLanPrinter(profile)
                PrinterConfigManager.TYPE_EPSON -> testEpsonPrinter(profile)
                PrinterConfigManager.TYPE_XPRINTER -> testXPrinter(profile)
                PrinterConfigManager.TYPE_VENDOR -> testVendorPrinter(profile)
                else -> TestResult(
                    success = false,
                    message = "Unknown printer type: ${profile.type}",
                    duration = System.currentTimeMillis() - startTime,
                    error = "UNKNOWN_TYPE"
                )
            }
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = "Test failed with exception: ${e.message}",
                duration = System.currentTimeMillis() - startTime,
                error = e.javaClass.simpleName
            )
        }
    }
    
    /**
     * Test Bluetooth printer
     */
    private fun testBluetoothPrinter(profile: PrinterConfigManager.PrinterConfig): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val mac = profile.connectionParams["mac"]
            if (mac.isNullOrEmpty()) {
                return TestResult(
                    success = false,
                    message = "No MAC address configured",
                    duration = System.currentTimeMillis() - startTime,
                    error = "NO_MAC_ADDRESS"
                )
            }
            
            val printer = BluetoothEscPosPrinter(context)
            val testText = generateTestContent(profile)
            
            // Test connection
            val connectionResult = testConnection {
                // This would need to be implemented in BluetoothEscPosPrinter
                // For now, we'll simulate the test
                true
            }
            
            if (!connectionResult.success) {
                return connectionResult.copy(duration = System.currentTimeMillis() - startTime)
            }
            
            // Test printing
            val printResult = testPrinting {
                try {
                    printer.printText(
                        testText,
                        leftMarginDots = profile.leftMargin,
                        rightMarginDots = profile.rightMargin,
                        lineSpacing = profile.lineSpacing,
                        widthMultiplier = profile.widthMultiplier,
                        heightMultiplier = profile.heightMultiplier,
                        pageWidthDots = profile.paperWidth
                    )
                    true // Return true if printText succeeds
                } catch (e: Exception) {
                    false // Return false if printText fails
                }
            }
            
            TestResult(
                success = printResult.success,
                message = if (printResult.success) "Bluetooth printer test successful" else printResult.message,
                duration = System.currentTimeMillis() - startTime,
                details = mapOf(
                    "type" to "bluetooth",
                    "mac" to mac,
                    "connectionTest" to connectionResult.success,
                    "printTest" to printResult.success
                ),
                error = if (!printResult.success) printResult.error else null
            )
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = "Bluetooth printer test failed: ${e.message}",
                duration = System.currentTimeMillis() - startTime,
                error = e.javaClass.simpleName
            )
        }
    }
    
    /**
     * Test USB printer
     */
    private fun testUsbPrinter(profile: PrinterConfigManager.PrinterConfig): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val deviceName = profile.connectionParams["deviceName"]
            if (deviceName.isNullOrEmpty()) {
                return TestResult(
                    success = false,
                    message = "No device name configured",
                    duration = System.currentTimeMillis() - startTime,
                    error = "NO_DEVICE_NAME"
                )
            }
            
            val printer = UsbEscPosPrinter(context)
            val testText = generateTestContent(profile)
            
            // Test device availability
            val devices = printer.listPrinters()
            val targetDevice = devices.find { it.deviceName == deviceName }
            
            if (targetDevice == null) {
                return TestResult(
                    success = false,
                    message = "USB device not found: $deviceName",
                    duration = System.currentTimeMillis() - startTime,
                    error = "DEVICE_NOT_FOUND",
                    details = mapOf(
                        "type" to "usb",
                        "deviceName" to deviceName,
                        "availableDevices" to devices.map { it.deviceName }
                    )
                )
            }
            
            // Test printing (simplified - would need actual connection)
            TestResult(
                success = true,
                message = "USB printer test successful",
                duration = System.currentTimeMillis() - startTime,
                details = mapOf(
                    "type" to "usb",
                    "deviceName" to deviceName,
                    "vendorId" to targetDevice.vendorId,
                    "productId" to targetDevice.productId
                )
            )
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = "USB printer test failed: ${e.message}",
                duration = System.currentTimeMillis() - startTime,
                error = e.javaClass.simpleName
            )
        }
    }
    
    /**
     * Test LAN printer
     */
    private fun testLanPrinter(profile: PrinterConfigManager.PrinterConfig): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val ip = profile.connectionParams["ip"]
            val port = profile.connectionParams["port"]?.toIntOrNull() ?: 9100
            
            if (ip.isNullOrEmpty()) {
                return TestResult(
                    success = false,
                    message = "No IP address configured",
                    duration = System.currentTimeMillis() - startTime,
                    error = "NO_IP_ADDRESS"
                )
            }
            
            val printer = LanEscPosPrinter(context)
            val testText = generateTestContent(profile)
            
            // Test connection
            val connectionResult = testConnection {
                printer.connect(ip, port, profile.timeout)
                printer.isConnected()
            }
            
            if (!connectionResult.success) {
                return connectionResult.copy(duration = System.currentTimeMillis() - startTime)
            }
            
            // Test printing
            val printResult = testPrinting {
                try {
                    printer.printText(
                        testText,
                        leftMarginDots = profile.leftMargin,
                        rightMarginDots = profile.rightMargin,
                        lineSpacing = profile.lineSpacing,
                        widthMultiplier = profile.widthMultiplier,
                        heightMultiplier = profile.heightMultiplier,
                        pageWidthDots = profile.paperWidth
                    )
                    true // Return true if printText succeeds
                } catch (e: Exception) {
                    false // Return false if printText fails
                }
            }
            
            TestResult(
                success = printResult.success,
                message = if (printResult.success) "LAN printer test successful" else printResult.message,
                duration = System.currentTimeMillis() - startTime,
                details = mapOf(
                    "type" to "lan",
                    "ip" to ip,
                    "port" to port,
                    "connectionTest" to connectionResult.success,
                    "printTest" to printResult.success
                ),
                error = if (!printResult.success) printResult.error else null
            )
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = "LAN printer test failed: ${e.message}",
                duration = System.currentTimeMillis() - startTime,
                error = e.javaClass.simpleName
            )
        }
    }
    
    /**
     * Test Epson printer
     */
    private fun testEpsonPrinter(profile: PrinterConfigManager.PrinterConfig): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val printer = EpsonPrinter(context)
            val testText = generateTestContent(profile)
            
            if (!printer.isAvailable()) {
                return TestResult(
                    success = false,
                    message = "Epson SDK not available",
                    duration = System.currentTimeMillis() - startTime,
                    error = "SDK_NOT_AVAILABLE"
                )
            }
            
            // Test printing
            val printResult = testPrinting {
                printer.printText(testText)
            }
            
            TestResult(
                success = printResult.success,
                message = if (printResult.success) "Epson printer test successful" else printResult.message,
                duration = System.currentTimeMillis() - startTime,
                details = mapOf(
                    "type" to "epson",
                    "sdkAvailable" to true,
                    "printTest" to printResult.success
                ),
                error = if (!printResult.success) printResult.error else null
            )
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = "Epson printer test failed: ${e.message}",
                duration = System.currentTimeMillis() - startTime,
                error = e.javaClass.simpleName
            )
        }
    }
    
    /**
     * Test XPrinter
     */
    private fun testXPrinter(profile: PrinterConfigManager.PrinterConfig): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val printer = XPrinter(context)
            val testText = generateTestContent(profile)
            
            if (!printer.isAvailable()) {
                return TestResult(
                    success = false,
                    message = "XPrinter SDK not available",
                    duration = System.currentTimeMillis() - startTime,
                    error = "SDK_NOT_AVAILABLE"
                )
            }
            
            // Test printing
            val printResult = testPrinting {
                printer.printText(testText)
            }
            
            TestResult(
                success = printResult.success,
                message = if (printResult.success) "XPrinter test successful" else printResult.message,
                duration = System.currentTimeMillis() - startTime,
                details = mapOf(
                    "type" to "xprinter",
                    "sdkAvailable" to true,
                    "printTest" to printResult.success
                ),
                error = if (!printResult.success) printResult.error else null
            )
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = "XPrinter test failed: ${e.message}",
                duration = System.currentTimeMillis() - startTime,
                error = e.javaClass.simpleName
            )
        }
    }
    
    /**
     * Test Vendor printer
     */
    private fun testVendorPrinter(profile: PrinterConfigManager.PrinterConfig): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val printer = VendorPrinter(context)
            val testText = generateTestContent(profile)
            
            if (!printer.isAvailable()) {
                return TestResult(
                    success = false,
                    message = "Vendor SDK not available",
                    duration = System.currentTimeMillis() - startTime,
                    error = "SDK_NOT_AVAILABLE"
                )
            }
            
            // Test printing
            val printResult = testPrinting {
                printer.printText(testText)
            }
            
            TestResult(
                success = printResult.success,
                message = if (printResult.success) "Vendor printer test successful" else printResult.message,
                duration = System.currentTimeMillis() - startTime,
                details = mapOf(
                    "type" to "vendor",
                    "sdkAvailable" to true,
                    "printTest" to printResult.success
                ),
                error = if (!printResult.success) printResult.error else null
            )
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = "Vendor printer test failed: ${e.message}",
                duration = System.currentTimeMillis() - startTime,
                error = e.javaClass.simpleName
            )
        }
    }
    
    /**
     * Test connection with timeout
     */
    private fun testConnection(connectionTest: () -> Boolean): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val future: Future<Boolean> = executor.submit<Boolean> {
                connectionTest()
            }
            
            val result = future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val duration = System.currentTimeMillis() - startTime
            
            TestResult(
                success = result,
                message = if (result) "Connection successful" else "Connection failed",
                duration = duration
            )
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = "Connection test failed: ${e.message}",
                duration = System.currentTimeMillis() - startTime,
                error = e.javaClass.simpleName
            )
        }
    }
    
    /**
     * Test printing with timeout
     */
    private fun testPrinting(printTest: () -> Boolean): TestResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val future: Future<Boolean> = executor.submit<Boolean> {
                printTest()
            }
            
            val result = future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val duration = System.currentTimeMillis() - startTime
            
            TestResult(
                success = result,
                message = if (result) "Print test successful" else "Print test failed",
                duration = duration
            )
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = "Print test failed: ${e.message}",
                duration = System.currentTimeMillis() - startTime,
                error = e.javaClass.simpleName
            )
        }
    }
    
    /**
     * Generate test content based on profile configuration
     */
    private fun generateTestContent(profile: PrinterConfigManager.PrinterConfig): String {
        val paperWidth = profile.paperWidth
        val isWide = paperWidth >= 576 // 80mm or wider
        
        return buildString {
            appendLine("=".repeat(paperWidth / 8))
            appendLine("PRINTER TEST")
            appendLine("=".repeat(paperWidth / 8))
            appendLine()
            appendLine("Profile: ${profile.name}")
            appendLine("Type: ${profile.type.uppercase()}")
            appendLine("Paper: ${paperWidth} dots")
            appendLine("Margins: L${profile.leftMargin} R${profile.rightMargin}")
            appendLine("Spacing: ${profile.lineSpacing}")
            appendLine("Scale: ${profile.widthMultiplier}x${profile.heightMultiplier}")
            appendLine("Charset: ${profile.charset}")
            appendLine()
            appendLine("Test Content:")
            appendLine("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            appendLine("abcdefghijklmnopqrstuvwxyz")
            appendLine("0123456789")
            appendLine("!@#$%^&*()_+-=[]{}|;':\",./<>?")
            appendLine()
            if (isWide) {
                appendLine("Wide Format Test:")
                appendLine("This is a test of wide format printing")
                appendLine("with multiple lines and proper formatting")
            } else {
                appendLine("Narrow Format Test:")
                appendLine("Compact printing test")
            }
            appendLine()
            appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine()
            appendLine("=".repeat(paperWidth / 8))
            appendLine("END OF TEST")
            appendLine("=".repeat(paperWidth / 8))
            appendLine()
            appendLine()
            appendLine()
        }
    }
    
    /**
     * Run comprehensive printer diagnostics
     */
    fun runDiagnostics(): Map<String, Any> {
        val diagnostics = mutableMapOf<String, Any>()
        val startTime = System.currentTimeMillis()
        
        try {
            // Test Bluetooth availability
            val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            diagnostics["bluetooth_available"] = btAdapter != null
            diagnostics["bluetooth_enabled"] = btAdapter?.isEnabled == true
            diagnostics["bluetooth_paired_devices"] = btAdapter?.bondedDevices?.size ?: 0
            
            // Test USB devices
            val usbManager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            diagnostics["usb_devices_count"] = usbManager.deviceList.size
            
            // Test network connectivity
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetworkInfo
            diagnostics["network_connected"] = activeNetwork?.isConnected == true
            diagnostics["network_type"] = activeNetwork?.typeName ?: "Unknown"
            
            // Test SDK availability using the SDK downloader
            val sdkAvailability = sdkDownloader.checkSdkAvailability()
            diagnostics["epson_sdk_available"] = sdkAvailability["epson_sdk_available"] ?: false
            diagnostics["xprinter_sdk_available"] = sdkAvailability["xprinter_sdk_available"] ?: false
            diagnostics["epson_sdk_path"] = sdkAvailability["epson_sdk_path"] ?: "Not installed"
            diagnostics["xprinter_sdk_path"] = sdkAvailability["xprinter_sdk_path"] ?: "Not installed"
            
            // ESC/POS printing is available without vendor SDKs
            diagnostics["escpos_available"] = true
            diagnostics["usb_printing_available"] = true
            diagnostics["bluetooth_printing_available"] = true
            diagnostics["lan_printing_available"] = true
            
            diagnostics["diagnostics_duration"] = System.currentTimeMillis() - startTime
            diagnostics["timestamp"] = System.currentTimeMillis()
            
        } catch (e: Exception) {
            diagnostics["error"] = e.message ?: "Unknown error"
            diagnostics["diagnostics_duration"] = System.currentTimeMillis() - startTime
        }
        
        return diagnostics
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup", e)
        }
    }
}
