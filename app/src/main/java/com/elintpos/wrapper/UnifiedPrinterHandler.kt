package com.elintpos.wrapper

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.elintpos.wrapper.escpos.BluetoothEscPosPrinter
import com.elintpos.wrapper.escpos.LanEscPosPrinter
import com.elintpos.wrapper.escpos.UsbEscPosPrinter
import com.elintpos.wrapper.printer.PrinterConfigManager
import com.elintpos.wrapper.printer.vendor.EpsonPrinter
import com.elintpos.wrapper.printer.vendor.XPrinter
import com.elintpos.wrapper.printer.vendor.VendorPrinter
import java.io.IOException

/**
 * Unified Printer Handler - Direct printing using configured printer SDK
 * 
 * This class handles all printing operations by automatically:
 * 1. Getting the configured printer from PrinterConfigManager
 * 2. Connecting to the printer if needed
 * 3. Printing using the printer SDK with configured settings
 */
class UnifiedPrinterHandler(private val context: Context) {
    
    private val printerConfigManager = PrinterConfigManager(context)
    private val epsonPrinter = EpsonPrinter(context)
    private val xPrinter = XPrinter(context)
    private val vendorPrinter = VendorPrinter(context)
    
    // Printer instances (created on demand)
    private var bluetoothPrinter: BluetoothEscPosPrinter? = null
    private var usbPrinter: UsbEscPosPrinter? = null
    private var lanPrinter: LanEscPosPrinter? = null
    
    companion object {
        private const val TAG = "UnifiedPrinterHandler"
    }
    
    /**
     * Main print function - automatically uses configured printer
     */
    fun print(text: String, preferType: String? = null): PrintResult {
        return try {
            // Get the printer profile to use
            val profile = getPrinterProfile(preferType)
            
            if (profile == null) {
                return PrintResult(
                    success = false,
                    message = "No printer configured. Please configure a printer in settings."
                )
            }
            
            // Log the paper size being used
            val paperSizeMm = when (profile.paperWidth) {
                PrinterConfigManager.PAPER_58MM -> "58mm"
                PrinterConfigManager.PAPER_80MM -> "80mm"
                PrinterConfigManager.PAPER_112MM -> "112mm"
                else -> "${profile.paperWidth} dots"
            }
            Log.d(TAG, "Printing with profile: ${profile.name}, type: ${profile.type}, paper size: $paperSizeMm (${profile.paperWidth} dots)")
            
            // Connect to printer if needed
            val connectResult = connectToPrinter(profile)
            if (!connectResult.success) {
                return connectResult
            }
            
            // Print using the configured printer with configured paper size
            val printResult = printWithProfile(profile, text)
            
            // Update last used
            printerConfigManager.setLastUsedProfile(profile.id)
            
            printResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Print error: ${e.message}", e)
            PrintResult(
                success = false,
                message = "Print error: ${e.message}"
            )
        }
    }
    
    /**
     * Get the printer profile to use
     */
    private fun getPrinterProfile(preferType: String?): PrinterConfigManager.PrinterConfig? {
        // If preferType is specified, try to get default for that type
        if (!preferType.isNullOrBlank()) {
            val type = when (preferType.lowercase()) {
                "bt", "bluetooth" -> PrinterConfigManager.TYPE_BLUETOOTH
                "usb" -> PrinterConfigManager.TYPE_USB
                "lan", "wifi", "network" -> PrinterConfigManager.TYPE_LAN
                "epson" -> PrinterConfigManager.TYPE_EPSON
                "xprinter" -> PrinterConfigManager.TYPE_XPRINTER
                "vendor" -> PrinterConfigManager.TYPE_VENDOR
                else -> preferType.lowercase()
            }
            
            val profile = printerConfigManager.getDefaultProfile(type)
            if (profile != null && profile.enabled) {
                return profile
            }
        }
        
        // Try last used profile
        val lastUsed = printerConfigManager.getLastUsedProfile()
        if (lastUsed != null && lastUsed.enabled) {
            return lastUsed
        }
        
        // Try to find any enabled default profile
        val allProfiles = printerConfigManager.getAllProfiles()
        val defaultProfile = allProfiles.firstOrNull { it.isDefault && it.enabled }
        if (defaultProfile != null) {
            return defaultProfile
        }
        
        // Use first enabled profile
        return allProfiles.firstOrNull { it.enabled }
    }
    
    /**
     * Connect to printer based on profile
     */
    @SuppressLint("MissingPermission")
    private fun connectToPrinter(profile: PrinterConfigManager.PrinterConfig): PrintResult {
        return try {
            when (profile.type) {
                PrinterConfigManager.TYPE_BLUETOOTH -> {
                    val mac = profile.connectionParams["mac"]
                    if (mac.isNullOrBlank()) {
                        return PrintResult(false, "No MAC address configured")
                    }
                    
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    if (adapter == null) {
                        return PrintResult(false, "Bluetooth not available")
                    }
                    
                    if (!adapter.isEnabled) {
                        return PrintResult(false, "Bluetooth is not enabled")
                    }
                    
                    val device = adapter.getRemoteDevice(mac)
                    if (device == null) {
                        return PrintResult(false, "Bluetooth device not found: $mac")
                    }
                    
                    bluetoothPrinter = BluetoothEscPosPrinter(context)
                    try {
                        bluetoothPrinter!!.connect(device)
                        PrintResult(true, "Connected to Bluetooth printer")
                    } catch (e: IOException) {
                        Log.e(TAG, "Bluetooth connection failed: ${e.message}", e)
                        PrintResult(false, "Bluetooth connection failed: ${e.message}")
                    }
                }
                
                PrinterConfigManager.TYPE_USB -> {
                    val deviceName = profile.connectionParams["deviceName"]
                    val vendorIdStr = profile.connectionParams["vendorId"]
                    val productIdStr = profile.connectionParams["productId"]
                    
                    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                    var device: UsbDevice? = null
                    
                    // Try to find device by deviceName first
                    if (!deviceName.isNullOrBlank()) {
                        device = usbManager.deviceList.values.firstOrNull { 
                            it.deviceName == deviceName 
                        }
                    }
                    
                    // If not found by deviceName, try vendorId:productId
                    if (device == null && !vendorIdStr.isNullOrBlank() && !productIdStr.isNullOrBlank()) {
                        val vendorId = vendorIdStr.toIntOrNull()
                        val productId = productIdStr.toIntOrNull()
                        if (vendorId != null && productId != null) {
                            device = usbManager.deviceList.values.firstOrNull { 
                                it.vendorId == vendorId && it.productId == productId
                            }
                        }
                    }
                    
                    // If still not found, try any available USB printer
                    if (device == null) {
                        val printers = UsbEscPosPrinter(context).listPrinters()
                        device = printers.firstOrNull()
                    }
                    
                    if (device == null) {
                        return PrintResult(false, "USB device not found. Please connect a USB printer.")
                    }
                    
                    if (!usbManager.hasPermission(device)) {
                        return PrintResult(false, "USB permission not granted for device: ${device.deviceName}")
                    }
                    
                    usbPrinter = UsbEscPosPrinter(context)
                    try {
                        usbPrinter!!.connect(device)
                        PrintResult(true, "Connected to USB printer: ${device.deviceName}")
                    } catch (e: IOException) {
                        Log.e(TAG, "USB connection failed: ${e.message}", e)
                        PrintResult(false, "USB connection failed: ${e.message}")
                    }
                }
                
                PrinterConfigManager.TYPE_LAN -> {
                    val ip = profile.connectionParams["ip"]
                    val portStr = profile.connectionParams["port"] ?: "9100"
                    val port = portStr.toIntOrNull() ?: 9100
                    
                    if (ip.isNullOrBlank()) {
                        return PrintResult(false, "No IP address configured")
                    }
                    
                    lanPrinter = LanEscPosPrinter(context)
                    try {
                        lanPrinter!!.connect(ip, port, profile.timeout)
                        PrintResult(true, "Connected to LAN printer")
                    } catch (e: IOException) {
                        Log.e(TAG, "LAN connection failed: ${e.message}", e)
                        PrintResult(false, "LAN connection failed: ${e.message}")
                    }
                }
                
                PrinterConfigManager.TYPE_EPSON -> {
                    if (!epsonPrinter.isAvailable()) {
                        return PrintResult(false, "Epson SDK not available")
                    }
                    PrintResult(true, "Epson printer ready")
                }
                
                PrinterConfigManager.TYPE_XPRINTER -> {
                    if (!xPrinter.isAvailable()) {
                        return PrintResult(false, "XPrinter SDK not available")
                    }
                    PrintResult(true, "XPrinter ready")
                }
                
                PrinterConfigManager.TYPE_VENDOR -> {
                    if (!vendorPrinter.isAvailable()) {
                        return PrintResult(false, "Vendor SDK not available")
                    }
                    PrintResult(true, "Vendor printer ready")
                }
                
                else -> PrintResult(false, "Unknown printer type: ${profile.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}", e)
            PrintResult(false, "Connection failed: ${e.message}")
        }
    }
    
    /**
     * Print text using the configured printer profile
     */
    private fun printWithProfile(
        profile: PrinterConfigManager.PrinterConfig,
        text: String
    ): PrintResult {
        return try {
            when (profile.type) {
                PrinterConfigManager.TYPE_BLUETOOTH -> {
                    if (bluetoothPrinter == null) {
                        return PrintResult(false, "Bluetooth printer not connected")
                    }
                    try {
                        bluetoothPrinter!!.printText(
                            text,
                            leftMarginDots = profile.leftMargin,
                            rightMarginDots = profile.rightMargin,
                            lineSpacing = profile.lineSpacing,
                            widthMultiplier = profile.widthMultiplier,
                            heightMultiplier = profile.heightMultiplier,
                            pageWidthDots = profile.paperWidth
                        )
                        PrintResult(true, "Printed via Bluetooth")
                    } catch (e: IOException) {
                        Log.e(TAG, "Bluetooth print failed: ${e.message}", e)
                        PrintResult(false, "Bluetooth print failed: ${e.message}")
                    }
                }
                
                PrinterConfigManager.TYPE_USB -> {
                    if (usbPrinter == null) {
                        return PrintResult(false, "USB printer not connected")
                    }
                    try {
                        usbPrinter!!.printText(
                            text,
                            leftMarginDots = profile.leftMargin,
                            rightMarginDots = profile.rightMargin,
                            lineSpacing = profile.lineSpacing,
                            widthMultiplier = profile.widthMultiplier,
                            heightMultiplier = profile.heightMultiplier,
                            pageWidthDots = profile.paperWidth
                        )
                        PrintResult(true, "Printed via USB")
                    } catch (e: IOException) {
                        Log.e(TAG, "USB print failed: ${e.message}", e)
                        PrintResult(false, "USB print failed: ${e.message}")
                    }
                }
                
                PrinterConfigManager.TYPE_LAN -> {
                    if (lanPrinter == null) {
                        return PrintResult(false, "LAN printer not connected")
                    }
                    try {
                        lanPrinter!!.printText(
                            text,
                            leftMarginDots = profile.leftMargin,
                            rightMarginDots = profile.rightMargin,
                            lineSpacing = profile.lineSpacing,
                            widthMultiplier = profile.widthMultiplier,
                            heightMultiplier = profile.heightMultiplier,
                            pageWidthDots = profile.paperWidth
                        )
                        PrintResult(true, "Printed via LAN")
                    } catch (e: IOException) {
                        Log.e(TAG, "LAN print failed: ${e.message}", e)
                        PrintResult(false, "LAN print failed: ${e.message}")
                    }
                }
                
                PrinterConfigManager.TYPE_EPSON -> {
                    if (!epsonPrinter.isAvailable()) {
                        return PrintResult(false, "Epson SDK not available")
                    }
                    val success = epsonPrinter.printText(text)
                    if (success) {
                        PrintResult(true, "Printed via Epson SDK")
                    } else {
                        PrintResult(false, "Epson print failed")
                    }
                }
                
                PrinterConfigManager.TYPE_XPRINTER -> {
                    if (!xPrinter.isAvailable()) {
                        return PrintResult(false, "XPrinter SDK not available")
                    }
                    val success = xPrinter.printText(text)
                    if (success) {
                        PrintResult(true, "Printed via XPrinter SDK")
                    } else {
                        PrintResult(false, "XPrinter print failed")
                    }
                }
                
                PrinterConfigManager.TYPE_VENDOR -> {
                    if (!vendorPrinter.isAvailable()) {
                        return PrintResult(false, "Vendor SDK not available")
                    }
                    val success = vendorPrinter.printText(text)
                    if (success) {
                        PrintResult(true, "Printed via Vendor SDK")
                    } else {
                        PrintResult(false, "Vendor print failed")
                    }
                }
                
                else -> PrintResult(false, "Unknown printer type: ${profile.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Print error: ${e.message}", e)
            PrintResult(false, "Print error: ${e.message}")
        }
    }
    
    /**
     * Print barcode
     */
    fun printBarcode(
        data: String,
        barcodeType: Int = 73,
        height: Int = 50,
        width: Int = 2,
        position: Int = 0,
        preferType: String? = null
    ): PrintResult {
        val profile = getPrinterProfile(preferType) ?: return PrintResult(false, "No printer configured")
        
        if (profile.type == PrinterConfigManager.TYPE_LAN && lanPrinter != null) {
            return try {
                val connectResult = connectToPrinter(profile)
                if (!connectResult.success) return connectResult
                
                lanPrinter!!.printBarcode(data, barcodeType, height, width, position)
                PrintResult(true, "Barcode printed")
            } catch (e: Exception) {
                PrintResult(false, "Barcode print error: ${e.message}")
            }
        }
        
        // For other types, convert barcode to text
        return print("Barcode: $data", preferType)
    }
    
    /**
     * Print QR code
     */
    fun printQRCode(
        data: String,
        size: Int = 3,
        errorCorrection: Int = 0,
        preferType: String? = null
    ): PrintResult {
        val profile = getPrinterProfile(preferType) ?: return PrintResult(false, "No printer configured")
        
        if (profile.type == PrinterConfigManager.TYPE_LAN && lanPrinter != null) {
            return try {
                val connectResult = connectToPrinter(profile)
                if (!connectResult.success) return connectResult
                
                lanPrinter!!.printQRCode(data, size, errorCorrection)
                PrintResult(true, "QR code printed")
            } catch (e: Exception) {
                PrintResult(false, "QR code print error: ${e.message}")
            }
        }
        
        // For other types, convert QR to text
        return print("QR Code: $data", preferType)
    }
    
    /**
     * Print HTML invoice as bitmap
     * 
     * Renders HTML in WebView, captures as bitmap, scales to printer width, and prints
     * 
     * @param htmlContent HTML content to render and print
     * @param preferType Preferred printer type (optional)
     * @return PrintResult with success status and message
     */
    fun printHtmlInvoice(htmlContent: String, preferType: String? = null): PrintResult {
        return try {
            val htmlInvoicePrinter = com.elintpos.wrapper.printer.HtmlInvoicePrinter(context)
            htmlInvoicePrinter.printHtmlInvoice(htmlContent, preferType)
        } catch (e: Exception) {
            Log.e(TAG, "HTML invoice print error: ${e.message}", e)
            PrintResult(
                success = false,
                message = "Print error: ${e.message}"
            )
        }
    }
    
    /**
     * Close all printer connections
     */
    fun closeAll() {
        try {
            bluetoothPrinter?.close()
            usbPrinter?.close()
            lanPrinter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing printers: ${e.message}", e)
        }
    }
    
    /**
     * Print result data class
     */
    data class PrintResult(
        val success: Boolean,
        val message: String
    ) {
        fun toJson(): String {
            return "{\"ok\":$success,\"msg\":\"$message\"}"
        }
    }
}

