package com.elintpos.wrapper.printer.vendor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.lang.reflect.Method

/**
 * AutoReplyPrint SDK Wrapper
 * 
 * This wrapper provides integration with the AutoReplyPrint SDK (autoreplyprint.aar).
 * It uses reflection to access SDK classes, allowing the app to compile even if the AAR is missing.
 * 
 * Key SDK Classes:
 * - CAPrinterConnector: Manages printer connections
 * - CAPrinterDiscover: Discovers available printers
 * - CAPrinterDevice: Represents a printer device
 * - CAPrintCommon: Provides printing utilities
 * 
 * Based on integration guide: INTEGRATION_GUIDE.md
 */
class AutoReplyPrint(private val context: Context) {

	companion object {
		private const val TAG = "AutoReplyPrint"
		
		// SDK class names
		private const val CLASS_PRINTER_CONNECTOR = "com.caysn.autoreplyprint.caprint.CAPrinterConnector"
		private const val CLASS_PRINTER_DISCOVER = "com.caysn.autoreplyprint.caprint.CAPrinterDiscover"
		private const val CLASS_PRINTER_DEVICE = "com.caysn.autoreplyprint.caprint.CAPrinterDevice"
		private const val CLASS_PRINT_COMMON = "com.caysn.autoreplyprint.caprint.CAPrintCommon"
	}

	private var printerConnector: Any? = null
	private var printerDiscover: Any? = null

	/**
	 * Returns true if AutoReplyPrint SDK classes are present on classpath
	 */
	fun isAvailable(): Boolean {
		return try {
			Class.forName(CLASS_PRINTER_CONNECTOR)
			true
		} catch (e: Throwable) {
			Log.d(TAG, "AutoReplyPrint SDK not available: ${e.message}")
			false
		}
	}

	/**
	 * Initializes the printer connector
	 * Should be called once, typically in Application.onCreate()
	 */
	fun initialize(): Boolean {
		if (!isAvailable()) return false
		
		return try {
			val connectorClass = Class.forName(CLASS_PRINTER_CONNECTOR)
			val constructor = connectorClass.getConstructor()
			printerConnector = constructor.newInstance()
			
			val discoverClass = Class.forName(CLASS_PRINTER_DISCOVER)
			val discoverConstructor = discoverClass.getConstructor()
			printerDiscover = discoverConstructor.newInstance()
			
			Log.d(TAG, "AutoReplyPrint SDK initialized successfully")
			true
		} catch (e: Exception) {
			Log.e(TAG, "Failed to initialize AutoReplyPrint SDK", e)
			false
		}
	}

	/**
	 * Gets the printer connector instance
	 */
	fun getPrinterConnector(): Any? = printerConnector

	/**
	 * Gets the printer discover instance
	 */
	fun getPrinterDiscover(): Any? = printerDiscover

	/**
	 * Checks if a printer is currently connected
	 */
	fun isConnected(): Boolean {
		if (!isAvailable() || printerConnector == null) return false
		
		return try {
			val connectorClass = printerConnector!!::class.java
			val method: Method = connectorClass.getMethod("isCurrentConnectedPrinter")
			method.invoke(printerConnector) as Boolean
		} catch (e: Exception) {
			Log.e(TAG, "Failed to check connection status", e)
			false
		}
	}

	/**
	 * Connects to a printer device asynchronously
	 * 
	 * @param printerDevice The printer device to connect to (from discovery)
	 */
	fun connectAsync(printerDevice: Any): Boolean {
		if (!isAvailable() || printerConnector == null) return false
		
		return try {
			val connectorClass = printerConnector!!::class.java
			val method: Method = connectorClass.getMethod("connectPrinterAsync", Class.forName(CLASS_PRINTER_DEVICE))
			method.invoke(printerConnector, printerDevice)
			true
		} catch (e: Exception) {
			Log.e(TAG, "Failed to connect printer asynchronously", e)
			false
		}
	}

	/**
	 * Connects to a printer device synchronously
	 * Should be called from a background thread
	 * 
	 * @param printerDevice The printer device to connect to
	 * @return true if connection successful
	 */
	fun connectSync(printerDevice: Any): Boolean {
		if (!isAvailable() || printerConnector == null) return false
		
		return try {
			val connectorClass = printerConnector!!::class.java
			val method: Method = connectorClass.getMethod("connectPrinterSync", Class.forName(CLASS_PRINTER_DEVICE))
			method.invoke(printerConnector, printerDevice) as Boolean
		} catch (e: Exception) {
			Log.e(TAG, "Failed to connect printer synchronously", e)
			false
		}
	}

	/**
	 * Disconnects from the current printer
	 */
	fun disconnect(): Boolean {
		if (!isAvailable() || printerConnector == null) return false
		
		return try {
			val connectorClass = printerConnector!!::class.java
			val method: Method = connectorClass.getMethod("disconnectPrinter")
			method.invoke(printerConnector)
			true
		} catch (e: Exception) {
			Log.e(TAG, "Failed to disconnect printer", e)
			false
		}
	}

	/**
	 * Gets the currently connected printer device
	 */
	fun getCurrentPrinterDevice(): Any? {
		if (!isAvailable() || printerConnector == null || !isConnected()) return null
		
		return try {
			val connectorClass = printerConnector!!::class.java
			val method: Method = connectorClass.getMethod("getCurrentPrinterDevice")
			method.invoke(printerConnector)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to get current printer device", e)
			null
		}
	}

	/**
	 * Starts discovering printers
	 * 
	 * @param onPrinterDiscovered Callback when a printer is discovered
	 */
	fun startDiscover(onPrinterDiscovered: (Any) -> Unit): Boolean {
		if (!isAvailable() || printerDiscover == null) return false
		
		return try {
			val discoverClass = printerDiscover!!::class.java
			
			// Create listener interface implementation
			val listenerClass = Class.forName("com.caysn.autoreplyprint.caprint.CAPrinterDiscover\$OnPrinterDiscoveredListener")
			val listener = java.lang.reflect.Proxy.newProxyInstance(
				listenerClass.classLoader,
				arrayOf(listenerClass)
			) { _, method, args ->
				if (method.name == "onPrinterDiscovered" && args != null && args.isNotEmpty()) {
					onPrinterDiscovered(args[0])
				}
				null
			}
			
			val setListenerMethod: Method = discoverClass.getMethod("setOnPrinterDiscoveredListener", listenerClass)
			setListenerMethod.invoke(printerDiscover, listener)
			
			val startMethod: Method = discoverClass.getMethod("startDiscover")
			startMethod.invoke(printerDiscover)
			
			true
		} catch (e: Exception) {
			Log.e(TAG, "Failed to start printer discovery", e)
			false
		}
	}

	/**
	 * Stops discovering printers
	 */
	fun stopDiscover(): Boolean {
		if (!isAvailable() || printerDiscover == null) return false
		
		return try {
			val discoverClass = printerDiscover!!::class.java
			val method: Method = discoverClass.getMethod("stopDiscover")
			method.invoke(printerDiscover)
			true
		} catch (e: Exception) {
			Log.e(TAG, "Failed to stop printer discovery", e)
			false
		}
	}

	/**
	 * Prints a bitmap image
	 * 
	 * @param bitmap The bitmap to print
	 * @param binaryzationMethod Binaryzation method (default: 2 for error diffusion)
	 * @param compressionMethod Compression method (default: 0)
	 * @param paperType Paper type (1=Serial, 2=Gap, 3=BlackMarker)
	 * @param printAlignment Alignment (default: 1)
	 * @param printSpeed Print speed (default: 150)
	 * @param printDensity Print density 0-15 (default: 7)
	 * @param kickDrawer Open cash drawer before print (default: false)
	 * @param feedPaper Feed paper after print in mm (default: 10.0)
	 * @param cutPaper Cut paper after print (0=No, 1=Yes, default: 0)
	 * @param waitPrintFinished Wait time for print completion in ms (default: 30000)
	 * @return true if print successful
	 */
	fun printBitmap(
		bitmap: Bitmap,
		binaryzationMethod: Int = 2,
		compressionMethod: Int = 0,
		paperType: Int = 1,
		printAlignment: Int = 1,
		printSpeed: Int = 150,
		printDensity: Int = 7,
		kickDrawer: Boolean = false,
		feedPaper: Double = 10.0,
		cutPaper: Int = 0,
		waitPrintFinished: Int = 30000
	): Boolean {
		if (!isAvailable() || printerConnector == null || !isConnected()) {
			Log.w(TAG, "Cannot print: SDK not available or printer not connected")
			return false
		}
		
		return try {
			val printCommonClass = Class.forName(CLASS_PRINT_COMMON)
			val method: Method = printCommonClass.getMethod(
				"printBitmap",
				Class.forName(CLASS_PRINTER_CONNECTOR),
				Bitmap::class.java,
				Int::class.javaPrimitiveType,
				Int::class.javaPrimitiveType,
				Int::class.javaPrimitiveType,
				Int::class.javaPrimitiveType,
				Int::class.javaPrimitiveType,
				Int::class.javaPrimitiveType,
				Boolean::class.javaPrimitiveType,
				Double::class.javaPrimitiveType,
				Int::class.javaPrimitiveType,
				Int::class.javaPrimitiveType
			)
			
			val result = method.invoke(
				null, // Static method
				printerConnector,
				bitmap,
				binaryzationMethod,
				compressionMethod,
				paperType,
				printAlignment,
				printSpeed,
				printDensity,
				kickDrawer,
				feedPaper,
				cutPaper,
				waitPrintFinished
			) as Boolean
			
			Log.d(TAG, "Print result: $result")
			result
		} catch (e: Exception) {
			Log.e(TAG, "Failed to print bitmap", e)
			false
		}
	}

	/**
	 * Gets printer resolution
	 */
	fun getPrinterResolution(): Any? {
		if (!isAvailable() || printerConnector == null || !isConnected()) return null
		
		return try {
			val printCommonClass = Class.forName(CLASS_PRINT_COMMON)
			val method: Method = printCommonClass.getMethod(
				"getPrinterResolution",
				Class.forName(CLASS_PRINTER_CONNECTOR)
			)
			method.invoke(null, printerConnector)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to get printer resolution", e)
			null
		}
	}

	/**
	 * Gets printer status
	 */
	fun getPrinterStatus(): Any? {
		if (!isAvailable() || printerConnector == null || !isConnected()) return null
		
		return try {
			val printCommonClass = Class.forName(CLASS_PRINT_COMMON)
			val method: Method = printCommonClass.getMethod(
				"getPrinterStatus",
				Class.forName(CLASS_PRINTER_CONNECTOR)
			)
			method.invoke(null, printerConnector)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to get printer status", e)
			null
		}
	}

	/**
	 * Registers a connection status changed listener
	 * 
	 * @param onStatusChanged Callback when connection status changes
	 */
	fun registerConnectionStatusListener(onStatusChanged: () -> Unit): Boolean {
		if (!isAvailable() || printerConnector == null) return false
		
		return try {
			val connectorClass = printerConnector!!::class.java
			val listenerClass = Class.forName("com.caysn.autoreplyprint.caprint.CAPrinterConnector\$ConnectionStatusChangedInterface")
			
			val listener = java.lang.reflect.Proxy.newProxyInstance(
				listenerClass.classLoader,
				arrayOf(listenerClass)
			) { _, method, _ ->
				if (method.name == "onConnectionStatusChanged") {
					onStatusChanged()
				}
				null
			}
			
			val method: Method = connectorClass.getMethod("registerConnectionStatusChangedEvent", listenerClass)
			method.invoke(printerConnector, listener)
			true
		} catch (e: Exception) {
			Log.e(TAG, "Failed to register connection status listener", e)
			false
		}
	}
}

