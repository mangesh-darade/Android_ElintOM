package com.elintpos.wrapper

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.print.PrintManager
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import org.json.JSONArray
import org.json.JSONObject
import com.elintpos.wrapper.escpos.BluetoothEscPosPrinter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.elintpos.wrapper.escpos.UsbEscPosPrinter
import com.elintpos.wrapper.escpos.LanEscPosPrinter
import com.elintpos.wrapper.escpos.ReceiptFormatter
import com.elintpos.wrapper.pdf.PdfDownloader
import com.elintpos.wrapper.export.CsvExporter
import android.content.SharedPreferences
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName

class MainActivity : ComponentActivity() {

	companion object {
		// Replace YOUR_DOMAIN_HERE with your actual domain (no scheme). Example: "pos.example.com"
		private const val BASE_DOMAIN = "devmeenatshi.elintpos.in"
		private const val BASE_URL = "http://$BASE_DOMAIN/"
		private const val USER_AGENT_SUFFIX = " DesktopAndroidWebView/1366x768"
		private const val TAG = "ElintPOS"
	}

	private lateinit var webView: WebView

	private var filePathCallback: ValueCallback<Array<Uri>>? = null
	private var cameraImageUri: Uri? = null

	private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
	private lateinit var genericPermissionsLauncher: ActivityResultLauncher<Array<String>>
	private var pendingPermissionsCallback: ((Map<String, Boolean>) -> Unit)? = null

	private val notificationPermissionLauncher =
		registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
			Toast.makeText(
				this,
				if (granted) "Notifications permission granted" else "Notifications permission denied",
				Toast.LENGTH_SHORT
			).show()
		}

	private val storagePermissionLauncher =
		registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
			val allGranted = permissions.values.all { it }
			Toast.makeText(
				this,
				if (allGranted) "Storage permissions granted" else "Storage permissions denied",
				Toast.LENGTH_SHORT
			).show()
		}

	private var escPosPrinter: BluetoothEscPosPrinter? = null
	private var usbPrinter: UsbEscPosPrinter? = null
	private var lanPrinter: LanEscPosPrinter? = null
	private var isBtConnected: Boolean = false
	private var isUsbConnected: Boolean = false
	private var isLanConnected: Boolean = false
	private var receiptFormatter: ReceiptFormatter = ReceiptFormatter()
	private var pdfDownloader: PdfDownloader = PdfDownloader(this)
	private var csvExporter: CsvExporter = CsvExporter(this)

	private val prefs: SharedPreferences by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

	private fun isAutoStartEnabled(): Boolean = prefs.getBoolean("auto_start_enabled", true)
	private fun setAutoStartEnabled(enabled: Boolean) { prefs.edit().putBoolean("auto_start_enabled", enabled).apply() }
	private fun isKioskEnabled(): Boolean = prefs.getBoolean("kiosk_enabled", false)
	private fun setKioskEnabled(enabled: Boolean) { prefs.edit().putBoolean("kiosk_enabled", enabled).apply() }

	private fun ensureLockTaskWhitelisted() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			try {
				val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
				val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
				dpm.setLockTaskPackages(admin, arrayOf(packageName))
			} catch (_: Exception) {}
		}
	}

	data class PrintConfig(
		var leftMargin: Int = 0,
		var rightMargin: Int = 0,
		var lineSpacing: Int = 30,
		var widthMul: Int = 0,
		var heightMul: Int = 0,
		var pageWidthDots: Int = 576,
		var linesPerPage: Int = 0
	)

	private var defaultPrintConfig = PrintConfig()

	@Suppress("DEPRECATION")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setupFullscreen()

		webView = WebView(this)
		setContentView(webView)

		// Generic multi-permission launcher used by JS bridge
		genericPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
			try {
				pendingPermissionsCallback?.invoke(result)
			} finally {
				pendingPermissionsCallback = null
			}
		}

		webView.addJavascriptInterface(object {
			@android.webkit.JavascriptInterface
			fun getKioskEnabled(): Boolean { return isKioskEnabled() }

			@android.webkit.JavascriptInterface
			fun setKioskEnabledJs(enabled: Boolean): String {
				return try {
					setKioskEnabled(enabled)
					if (enabled) {
						ensureLockTaskWhitelisted()
						try {
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) startLockTask()
						} catch (_: Exception) {}
					} else {
						try {
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stopLockTask()
						} catch (_: Exception) {}
					}
					"{\"ok\":true,\"enabled\":${'$'}enabled}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}
			@android.webkit.JavascriptInterface
			fun getAutoStartEnabled(): Boolean { return isAutoStartEnabled() }

			@android.webkit.JavascriptInterface
			fun setAutoStartEnabledJs(enabled: Boolean): String {
				return try { setAutoStartEnabled(enabled); "{\"ok\":true,\"enabled\":${'$'}enabled}" } catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}
			@android.webkit.JavascriptInterface
			fun requestAllPermissions(): String {
				return try {
					val perms = buildCommonPermissions()
					runOnUiThread {
						requestGenericPermissions(perms) { res ->
							val granted = res.filterValues { it }.keys
							val denied = res.filterValues { !it }.keys
							Toast.makeText(this@MainActivity, "Permissions: granted=${'$'}granted denied=${'$'}denied", Toast.LENGTH_SHORT).show()
						}
					}
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun checkPermissions(permsJson: String?): String {
				return try {
					val list = parsePermissionsJson(permsJson)
					val granted = org.json.JSONArray()
					val denied = org.json.JSONArray()
					list.forEach { p ->
						val g = ContextCompat.checkSelfPermission(this@MainActivity, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
						if (g) granted.put(p) else denied.put(p)
					}
					"{\"ok\":true,\"granted\":${'$'}granted,\"denied\":${'$'}denied}"
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun requestPermissions(permsJson: String?): String {
				return try {
					val array = parsePermissionsJson(permsJson).toTypedArray()
					runOnUiThread { requestGenericPermissions(array) { } }
					"{\"ok\":true}"
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}
			@android.webkit.JavascriptInterface
			fun requestNotificationsPermission() {
				requestNotificationsPermissionIfNeeded()
			}

			@android.webkit.JavascriptInterface
			fun listPairedPrinters(): String {
				val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "[]"
				val arr = JSONArray()
				adapter.bondedDevices?.forEach { device ->
					val obj = JSONObject()
					obj.put("name", device.name)
					obj.put("mac", device.address)
					arr.put(obj)
				}
				return arr.toString()
			}

			@android.webkit.JavascriptInterface
			fun connectPrinter(mac: String): String {
				return try {
					val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "{\"ok\":false,\"msg\":\"No BT\"}"
					val device = adapter.bondedDevices.firstOrNull { it.address.equals(mac, true) }
						?: return "{\"ok\":false,\"msg\":\"Not paired\"}"
					escPosPrinter?.close()
					escPosPrinter = BluetoothEscPosPrinter(this@MainActivity)
					escPosPrinter!!.connect(device)
					isBtConnected = true
					Toast.makeText(this@MainActivity, "Bluetooth printer connected", Toast.LENGTH_SHORT).show()
					"{\"ok\":true}"
				} catch (e: Exception) {
					isBtConnected = false
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun printText(text: String, leftMargin: Int, rightMargin: Int, lineSpacing: Int, scale: Int): String {
				return try {
					escPosPrinter?.printText(text, leftMarginDots = leftMargin, rightMarginDots = rightMargin, lineSpacing = lineSpacing, widthMultiplier = scale, heightMultiplier = scale)
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun printTextScaled(text: String, leftMargin: Int, rightMargin: Int, lineSpacing: Int, widthMul: Int, heightMul: Int, pageWidthDots: Int): String {
				return try {
					escPosPrinter?.printText(text, leftMarginDots = leftMargin, rightMarginDots = rightMargin, lineSpacing = lineSpacing, widthMultiplier = widthMul, heightMultiplier = heightMul, pageWidthDots = pageWidthDots)
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun listUsbPrinters(): String {
				val p = UsbEscPosPrinter(this@MainActivity)
				val devices = p.listPrinters()
				val arr = JSONArray()
				devices.forEach { d ->
					val obj = JSONObject()
					obj.put("vendorId", d.vendorId)
					obj.put("productId", d.productId)
					obj.put("deviceName", d.deviceName)
					arr.put(obj)
				}
				return arr.toString()
			}

			@android.webkit.JavascriptInterface
			fun connectUsbPrinter(deviceName: String): String {
				return try {
					val usbManager = getSystemService(USB_SERVICE) as UsbManager
					val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
						?: return "{\"ok\":false,\"msg\":\"Not found\"}"
					usbPrinter?.close()
					usbPrinter = UsbEscPosPrinter(this@MainActivity)
					usbPrinter!!.connect(device)
					isUsbConnected = true
					Toast.makeText(this@MainActivity, "USB printer connected", Toast.LENGTH_SHORT).show()
					"{\"ok\":true}"
				} catch (e: Exception) {
					isUsbConnected = false
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun usbPrintText(text: String, leftMargin: Int, rightMargin: Int, lineSpacing: Int, scale: Int): String {
				return try {
					usbPrinter?.printText(text, leftMarginDots = leftMargin, rightMarginDots = rightMargin, lineSpacing = lineSpacing, widthMultiplier = scale, heightMultiplier = scale)
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun usbPrintTextScaled(text: String, leftMargin: Int, rightMargin: Int, lineSpacing: Int, widthMul: Int, heightMul: Int, pageWidthDots: Int): String {
				return try {
					usbPrinter?.printText(text, leftMarginDots = leftMargin, rightMarginDots = rightMargin, lineSpacing = lineSpacing, widthMultiplier = widthMul, heightMultiplier = heightMul, pageWidthDots = pageWidthDots)
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun setDefaultPrintConfig(leftMargin: Int, rightMargin: Int, lineSpacing: Int, widthMul: Int, heightMul: Int, pageWidthDots: Int, linesPerPage: Int): String {
				defaultPrintConfig = PrintConfig(leftMargin, rightMargin, lineSpacing, widthMul, heightMul, pageWidthDots, linesPerPage)
				return "{\"ok\":true}"
			}

			@android.webkit.JavascriptInterface
			fun btPrint(text: String): String {
				return try {
					escPosPrinter?.printText(
						text,
						leftMarginDots = defaultPrintConfig.leftMargin,
						rightMarginDots = defaultPrintConfig.rightMargin,
						lineSpacing = defaultPrintConfig.lineSpacing,
						widthMultiplier = defaultPrintConfig.widthMul,
						heightMultiplier = defaultPrintConfig.heightMul,
						pageWidthDots = defaultPrintConfig.pageWidthDots,
						linesPerPage = defaultPrintConfig.linesPerPage.takeIf { it > 0 }
					)
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun usbPrint(text: String): String {
				return try {
					usbPrinter?.printText(
						text,
						leftMarginDots = defaultPrintConfig.leftMargin,
						rightMarginDots = defaultPrintConfig.rightMargin,
						lineSpacing = defaultPrintConfig.lineSpacing,
						widthMultiplier = defaultPrintConfig.widthMul,
						heightMultiplier = defaultPrintConfig.heightMul,
						pageWidthDots = defaultPrintConfig.pageWidthDots,
						linesPerPage = defaultPrintConfig.linesPerPage.takeIf { it > 0 }
					)
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun getPrinterStatus(): String {
				val obj = JSONObject()
				obj.put("bt", isBtConnected)
				obj.put("usb", isUsbConnected)
				obj.put("lan", isLanConnected)
				obj.put("any", isBtConnected || isUsbConnected || isLanConnected)
				return obj.toString()
			}

			@android.webkit.JavascriptInterface
			fun showToast(message: String) {
				Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
			}

			@android.webkit.JavascriptInterface
			fun printFromWeb(text: String, prefer: String): String {
				return try {
					val target = prefer.lowercase()
					if (target == "bt" && isBtConnected) {
						btPrint(text)
					} else if (target == "usb" && isUsbConnected) {
						usbPrint(text)
					} else if (target == "lan" && isLanConnected) {
						lanPrint(text)
					} else if (isBtConnected) {
						btPrint(text)
					} else if (isUsbConnected) {
						usbPrint(text)
					} else if (isLanConnected) {
						lanPrint(text)
					} else {
						return "{\"ok\":false,\"msg\":\"No printer connected\"}"
					}
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			// LAN Printer Functions
			@android.webkit.JavascriptInterface
			fun connectLanPrinter(ipAddress: String, port: Int = 9100): String {
				return try {
					lanPrinter?.close()
					lanPrinter = LanEscPosPrinter(this@MainActivity)
					lanPrinter!!.connect(ipAddress, port)
					isLanConnected = true
					Toast.makeText(this@MainActivity, "LAN printer connected: $ipAddress", Toast.LENGTH_SHORT).show()
					"{\"ok\":true}"
				} catch (e: Exception) {
					isLanConnected = false
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun lanPrint(text: String): String {
				return try {
					lanPrinter?.printText(
						text,
						leftMarginDots = defaultPrintConfig.leftMargin,
						rightMarginDots = defaultPrintConfig.rightMargin,
						lineSpacing = defaultPrintConfig.lineSpacing,
						widthMultiplier = defaultPrintConfig.widthMul,
						heightMultiplier = defaultPrintConfig.heightMul,
						pageWidthDots = defaultPrintConfig.pageWidthDots,
						linesPerPage = defaultPrintConfig.linesPerPage.takeIf { it > 0 }
					)
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			// Barcode and QR Code Functions
			@android.webkit.JavascriptInterface
			fun printBarcode(data: String, barcodeType: Int = 73, height: Int = 50, width: Int = 2, position: Int = 0, prefer: String = "auto"): String {
				return try {
					val target = prefer.lowercase()
					when {
						target == "bt" && isBtConnected -> {
							// Bluetooth barcode printing (simplified - would need BT printer support)
							btPrint("Barcode: $data")
						}
						target == "usb" && isUsbConnected -> {
							// USB barcode printing (simplified - would need USB printer support)
							usbPrint("Barcode: $data")
						}
						target == "lan" && isLanConnected -> {
							lanPrinter?.printBarcode(data, barcodeType, height, width, position)
						}
						isLanConnected -> {
							lanPrinter?.printBarcode(data, barcodeType, height, width, position)
						}
						else -> return "{\"ok\":false,\"msg\":\"No suitable printer connected\"}"
					}
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun printQRCode(data: String, size: Int = 3, errorCorrection: Int = 0, prefer: String = "auto"): String {
				return try {
					val target = prefer.lowercase()
					when {
						target == "bt" && isBtConnected -> {
							btPrint("QR Code: $data")
						}
						target == "usb" && isUsbConnected -> {
							usbPrint("QR Code: $data")
						}
						target == "lan" && isLanConnected -> {
							lanPrinter?.printQRCode(data, size, errorCorrection)
						}
						isLanConnected -> {
							lanPrinter?.printQRCode(data, size, errorCorrection)
						}
						else -> return "{\"ok\":false,\"msg\":\"No suitable printer connected\"}"
					}
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			// Enhanced Receipt Formatting
			@android.webkit.JavascriptInterface
			fun formatAndPrintInvoice(saleDataJson: String, configJson: String = "{}", prefer: String = "auto"): String {
				return try {
					val saleData = JSONObject(saleDataJson)
					val configObj = JSONObject(configJson)
					
					val config = ReceiptFormatter.ReceiptConfig(
						paperWidth = configObj.optInt("paperWidth", ReceiptFormatter.PAPER_WIDTH_80MM),
						leftMargin = configObj.optInt("leftMargin", 0),
						rightMargin = configObj.optInt("rightMargin", 0),
						lineSpacing = configObj.optInt("lineSpacing", 30),
						widthMultiplier = configObj.optInt("widthMultiplier", 0),
						heightMultiplier = configObj.optInt("heightMultiplier", 0),
						includeBarcode = configObj.optBoolean("includeBarcode", false),
						includeQR = configObj.optBoolean("includeQR", false),
						compactLayout = configObj.optBoolean("compactLayout", false)
					)
					
					val formattedText = receiptFormatter.formatInvoice(saleData, config)
					val result = printFromWeb(formattedText, prefer)
					
					// Print barcode/QR if requested
					if (config.includeBarcode) {
						val invoiceNumber = saleData.optString("invoice_number", "")
						if (invoiceNumber.isNotEmpty()) {
							printBarcode(invoiceNumber, ReceiptFormatter.BARCODE_CODE128, prefer = prefer)
						}
					}
					
					if (config.includeQR) {
						val invoiceNumber = saleData.optString("invoice_number", "")
						if (invoiceNumber.isNotEmpty()) {
							printQRCode(invoiceNumber, prefer = prefer)
						}
					}
					
					result
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun formatAndPrintReceipt(saleDataJson: String, configJson: String = "{}", prefer: String = "auto"): String {
				return try {
					val saleData = JSONObject(saleDataJson)
					val configObj = JSONObject(configJson)
					
					val config = ReceiptFormatter.ReceiptConfig(
						paperWidth = configObj.optInt("paperWidth", ReceiptFormatter.PAPER_WIDTH_80MM),
						leftMargin = configObj.optInt("leftMargin", 0),
						rightMargin = configObj.optInt("rightMargin", 0),
						lineSpacing = configObj.optInt("lineSpacing", 30),
						widthMultiplier = configObj.optInt("widthMultiplier", 0),
						heightMultiplier = configObj.optInt("heightMultiplier", 0),
						compactLayout = configObj.optBoolean("compactLayout", true)
					)
					
					val formattedText = receiptFormatter.formatReceipt(saleData, config)
					printFromWeb(formattedText, prefer)
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun formatAndPrintKitchenOrder(orderDataJson: String, configJson: String = "{}", prefer: String = "auto"): String {
				return try {
					val orderData = JSONObject(orderDataJson)
					val configObj = JSONObject(configJson)
					
					val config = ReceiptFormatter.ReceiptConfig(
						paperWidth = configObj.optInt("paperWidth", ReceiptFormatter.PAPER_WIDTH_80MM),
						leftMargin = configObj.optInt("leftMargin", 0),
						rightMargin = configObj.optInt("rightMargin", 0),
						lineSpacing = configObj.optInt("lineSpacing", 30),
						widthMultiplier = configObj.optInt("widthMultiplier", 1),
						heightMultiplier = configObj.optInt("heightMultiplier", 1)
					)
					
					val formattedText = receiptFormatter.formatKitchenOrder(orderData, config)
					printFromWeb(formattedText, prefer)
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			// Printer Management UI
			@android.webkit.JavascriptInterface
			fun openPrinterManagement(): String {
				return try {
					webView.loadUrl("file:///android_asset/printer_management.html")
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			// Utility functions
			@android.webkit.JavascriptInterface
			fun getAvailablePrinters(): String {
				return try {
					val obj = JSONObject()
					obj.put("bluetooth", JSONArray(listPairedPrinters()))
					obj.put("usb", JSONArray(listUsbPrinters()))
					obj.put("status", JSONObject(getPrinterStatus()))
					obj.toString()
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			// PDF Download Functions
			@android.webkit.JavascriptInterface
			fun downloadCurrentPageAsPdf(fileName: String? = null): String {
				return try {
					pdfDownloader.downloadCurrentPageAsPdf(webView, fileName)
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun systemPrint(jobName: String?): String {
				return try {
					runOnUiThread {
						val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
						val adapter: PrintDocumentAdapter = webView.createPrintDocumentAdapter(jobName ?: "POS Print")
						// Request high quality output and zero margins to avoid blurry text and extra whitespace
						val attrs = PrintAttributes.Builder()
							// Many services ignore custom resolution/media size, but when honored it improves sharpness and removes margins
							.setResolution(PrintAttributes.Resolution("high", "High quality", 600, 600))
							// Let the printer choose its best available paper (A4, Letter, 58mm/80mm roll, etc.)
							.setMediaSize(PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
							.setColorMode(PrintAttributes.COLOR_MODE_COLOR)
							.setMinMargins(PrintAttributes.Margins.NO_MARGINS)
							.build()
						pm.print(jobName ?: "POS Print", adapter, attrs)
					}
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			// Convenience alias for web apps that want a Save As PDF flow (opens the system print dialog)
			@android.webkit.JavascriptInterface
			fun saveAsPdf(jobName: String?): String {
				return systemPrint(jobName)
			}

			// CSV/Excel Export Functions
			@android.webkit.JavascriptInterface
			fun saveCsv(csvContent: String, fileName: String? = null): String {
				return try { csvExporter.saveCsv(csvContent, fileName) } catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun jsonArrayToCsv(jsonArray: String, fileName: String? = null): String {
				return try {
					val arr = org.json.JSONArray(jsonArray)
					csvExporter.jsonArrayToCsv(arr, fileName)
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun jsonObjectArrayFieldToCsv(containerJson: String, arrayField: String, fileName: String? = null): String {
				return try {
					val obj = org.json.JSONObject(containerJson)
					csvExporter.jsonObjectArrayFieldToCsv(obj, arrayField, fileName)
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun openCsv(filePath: String): String {
				return try { "{\"ok\":${'$'}{csvExporter.openCsv(filePath)}}" } catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun shareCsv(filePath: String): String {
				return try { "{\"ok\":${'$'}{csvExporter.shareCsv(filePath)}}" } catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun listCsv(prefix: String = "ElintPOS_"): String {
				return try { "{\"ok\":true,\"files\":${'$'}{csvExporter.listCsv(prefix).toString()}}" } catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun deleteCsv(filePath: String): String {
				return try { "{\"ok\":${'$'}{csvExporter.deleteCsv(filePath)}}" } catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun downloadUrlAsPdf(url: String, fileName: String? = null): String {
				return try {
					pdfDownloader.downloadUrlAsPdf(url, fileName)
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun exportHtmlAsPdf(htmlContent: String, fileName: String? = null): String {
				return try {
					pdfDownloader.exportHtmlAsPdf(htmlContent, fileName)
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun openPdfFile(filePath: String): String {
				return try {
					val success = pdfDownloader.openPdfFile(filePath)
					"{\"ok\":$success}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun sharePdfFile(filePath: String): String {
				return try {
					val success = pdfDownloader.sharePdfFile(filePath)
					"{\"ok\":$success}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun getDownloadedPdfs(): String {
				return try {
					val pdfFiles = pdfDownloader.getDownloadedPdfs()
					val arr = JSONArray()
					pdfFiles.forEach { file ->
						val obj = JSONObject()
						obj.put("name", file.name)
						obj.put("path", file.absolutePath)
						obj.put("size", file.length())
						obj.put("lastModified", file.lastModified())
						arr.put(obj)
					}
					"{\"ok\":true,\"files\":$arr}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun deletePdfFile(filePath: String): String {
				return try {
					val success = pdfDownloader.deletePdfFile(filePath)
					"{\"ok\":$success}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			// In-app viewers
			@android.webkit.JavascriptInterface
			fun openPdfInApp(path: String): String {
				return try {
					val intent = Intent(this@MainActivity, com.elintpos.wrapper.viewer.PdfViewerActivity::class.java)
					intent.putExtra("path", path)
					startActivity(intent)
					"{\"ok\":true}"
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun openCsvInApp(path: String): String {
				return try {
					val intent = Intent(this@MainActivity, com.elintpos.wrapper.viewer.CsvViewerActivity::class.java)
					intent.putExtra("path", path)
					startActivity(intent)
					"{\"ok\":true}"
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun openExcelInApp(path: String): String {
				return try {
					val intent = Intent(this@MainActivity, com.elintpos.wrapper.viewer.ExcelViewerActivity::class.java)
					intent.putExtra("path", path)
					startActivity(intent)
					"{\"ok\":true}"
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun requestStoragePermissions(): String {
				return try {
					requestStoragePermissionsIfNeeded()
					"{\"ok\":true,\"msg\":\"Storage permission request initiated\"}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}
		}, "ElintPOSNative")

		setupFileChooserLauncher()
		configureWebView(webView)
		setupDownloadHandling(webView)

		if (savedInstanceState != null) {
			webView.restoreState(savedInstanceState)
		} else {
			webView.loadUrl(BASE_URL)
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			requestNotificationsPermissionIfNeeded()
		}
	}

	private fun setupFullscreen() {
		WindowCompat.setDecorFitsSystemWindows(window, false)
		val controller = WindowInsetsControllerCompat(window, window.decorView)
		controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		controller.hide(WindowInsetsCompat.Type.systemBars())
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun configureWebView(view: WebView) {
		CookieManager.getInstance().setAcceptCookie(true)
		CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

		with(view.settings) {
			javaScriptEnabled = true
			domStorageEnabled = true
			databaseEnabled = true
			setSupportMultipleWindows(true)
			javaScriptCanOpenWindowsAutomatically = true
			mediaPlaybackRequiresUserGesture = false
			useWideViewPort = true
			loadWithOverviewMode = true
			builtInZoomControls = true
			displayZoomControls = false
			mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

			userAgentString = userAgentString + USER_AGENT_SUFFIX
		}

		view.isFocusable = true
		view.isFocusableInTouchMode = true
		view.overScrollMode = WebView.OVER_SCROLL_NEVER

		view.webViewClient = object : WebViewClient() {
			override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
				if (request == null) return false
				val uri = request.url

				// If the URL looks like a receipt view, open it in dedicated ReceiptActivity
				try {
					val path = uri.encodedPath ?: ""
					if (path.contains("/pos/view/") || path.contains("/sales/view/")) {
						val intent = Intent(this@MainActivity, com.elintpos.wrapper.viewer.ReceiptActivity::class.java)
						intent.putExtra(com.elintpos.wrapper.viewer.ReceiptActivity.EXTRA_URL, uri.toString())
						startActivity(intent)
						return true
					}
				} catch (_: Exception) {}

				val scheme = uri.scheme ?: return false
				when (scheme) {
					"tel", "mailto", "upi" -> {
						openExternalIntent(Intent(Intent.ACTION_VIEW, uri))
						return true
					}
					"intent" -> {
						try {
							val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
							startActivity(intent)
						} catch (e: Exception) {
							Toast.makeText(this@MainActivity, "No app to handle intent", Toast.LENGTH_SHORT).show()
						}
						return true
					}
				}

				// Stay within WebView for our domain; otherwise let OS handle http/https externals
				val host = uri.host ?: return false
				return if (host.endsWith(BASE_DOMAIN)) {
					false
				} else {
					openExternalIntent(Intent(Intent.ACTION_VIEW, uri))
					true
				}
			}

			override fun onPageFinished(view: WebView?, url: String?) {
				super.onPageFinished(view, url)
				// Inject a window.print override that sends the page text to native printing
				val js = """
					(function(){
						try{
							if(window.__elintposPatched) return; window.__elintposPatched = true;
							window.ElintPOS = {
								print: function(txt, prefer){ return ElintPOSNative.printFromWeb(String(txt||document.body.innerText||''), String(prefer||'auto')); },
								printSelector: function(selector){
									try{
										var el = document.querySelector(selector);
										if(!el){ ElintPOSNative.systemPrint('POS Print'); return; }
										var original = document.body.innerHTML;
										document.body.setAttribute('data-elintpos','printing');
										document.body.innerHTML = el.outerHTML;
										try{ ElintPOSNative.systemPrint('POS Print'); }catch(e){ try{ window.print(); }catch(_){} }
										setTimeout(function(){ try{ document.body.innerHTML = original; }catch(_){} }, 1500);
									}catch(e){ try{ ElintPOSNative.systemPrint('POS Print'); }catch(_){} }
								},
								status: function(){ return ElintPOSNative.getPrinterStatus(); }
							};
							var origPrint = window.print;
							window.print = function(){ try{ if(ElintPOSNative && ElintPOSNative.systemPrint){ ElintPOSNative.systemPrint('POS Print'); } else { ElintPOSNative.printFromWeb(String(document.body.innerText||''), 'auto'); } }catch(e){ if(origPrint) origPrint(); } };
							// Force target=_blank links and window.open to stay in same WebView
							try{ window.open = function(u){ try{ location.href = u; }catch(e){} return null; }; }catch(_){ }
							try{
								var bl = document.querySelectorAll('a[target="_blank"]');
								for(var i=0;i<bl.length;i++){ bl[i].setAttribute('target','_self'); }
							}catch(_){ }
							// Bind POS action buttons to Android system print
							var selectors = ['.cmdprint', '.cmdprint1', '.splitcheck'];
							selectors.forEach(function(sel){
								var els = document.querySelectorAll(sel);
								for(var i=0;i<els.length;i++){
									els[i].addEventListener('click', function(ev){
										try{ ElintPOS.printSelector('#paymentModal .modal-content'); }catch(e){ try{ ElintPOSNative.systemPrint('POS Print'); }catch(_){} }
									}, true);
								}
							});
						}catch(e){}
					})();
				"""
				view?.evaluateJavascript(js, null)
				// If this looks like a receipt view or blank print page, trigger a safe print after render
				if (url != null && (url == "about:blank" || url.indexOf("/pos/view/") >= 0 || url.indexOf("/sales/view/") >= 0)) {
					view?.postDelayed({
						try {
							view.evaluateJavascript(
								"(function(){try{ElintPOS.printSelector('#paymentModal .modal-content');}catch(e){try{window.print();}catch(_){}}})();",
								null
							)
						} catch (_: Exception) {
							try {
								runOnUiThread {
									val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
									pm.print("Receipt", webView.createPrintDocumentAdapter("Receipt"), PrintAttributes.Builder().build())
								}
							} catch (_: Exception) {}
						}
					}, 400)
				}
			}

			override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
				// Avoid app crash on misconfigured certs while viewing receipts. Prefer proceed; fix server SSL in production.
				try { handler?.proceed() } catch (_: Exception) { handler?.cancel() }
			}
		}

		view.webChromeClient = object : WebChromeClient() {
			override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
				// Open new window requests in the same WebView
				val transport = resultMsg?.obj as? WebView.WebViewTransport
				transport?.webView = this@MainActivity.webView
				resultMsg?.sendToTarget()
				// Fallback: some web apps open a blank window then call print(). Trigger print dialog to mimic browser behavior.
				this@MainActivity.webView.postDelayed({
					try {
						val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
						pm.print("POS Print", webView.createPrintDocumentAdapter("POS Print"), PrintAttributes.Builder().build())
					} catch (_: Exception) {}
				}, 500)
				return true
			}
			override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
				// Auto-grant geolocation for this session
				callback?.invoke(origin, true, false)
			}

			override fun onPermissionRequest(request: PermissionRequest?) {
				request?.grant(request.resources)
			}

			override fun onShowFileChooser(
				webView: WebView?,
				filePathCallback: ValueCallback<Array<Uri>>?,
				fileChooserParams: FileChooserParams?
			): Boolean {
				this@MainActivity.filePathCallback = filePathCallback
				launchFilePicker(fileChooserParams)
				return true
			}

			override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
				Log.d(TAG, "Console: ${'$'}{message?.message()} @ ${'$'}{message?.sourceId()}:${'$'}{message?.lineNumber()}")
				return super.onConsoleMessage(message)
			}
		}
	}

	private fun setupDownloadHandling(view: WebView) {
		view.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
			try {
				val request = DownloadManager.Request(Uri.parse(url)).apply {
					setMimeType(mimeType)
					if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)
					if (!contentDisposition.isNullOrEmpty()) addRequestHeader("Content-Disposition", contentDisposition)
					setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
					val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
					setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
					setAllowedOverMetered(true)
					setAllowedOverRoaming(true)
				}
				val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
				dm.enqueue(request)
				Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
			} catch (_: Exception) {
				try {
					val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
					startActivity(intent)
				} catch (_: Exception) {
					Toast.makeText(this, "No app to handle download", Toast.LENGTH_SHORT).show()
				}
			}
		})
	}

	private fun openExternalIntent(intent: Intent) {
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		try {
			startActivity(intent)
		} catch (_: ActivityNotFoundException) {
			Toast.makeText(this, "No app found", Toast.LENGTH_SHORT).show()
		}
	}

	private fun setupFileChooserLauncher() {
		fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
			val callback = filePathCallback ?: return@registerForActivityResult
			var results: Array<Uri>? = null

			if (result.resultCode == RESULT_OK) {
				val data = result.data
				if (data == null || data.data == null) {
					// Possibly camera capture
					cameraImageUri?.let { results = arrayOf(it) }
				} else {
					// Selected from picker
					data.data?.let { results = arrayOf(it) }
				}
			}

			callback.onReceiveValue(results ?: emptyArray())
			filePathCallback = null
			cameraImageUri = null
		}
	}

	private fun launchFilePicker(params: WebChromeClient.FileChooserParams?) {
		// Prepare camera capture
		val imageFile = createImageFile()
		cameraImageUri = FileProvider.getUriForFile(
			this,
			"${packageName}.fileprovider",
			imageFile
		)
		val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
			putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
			addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}

		// Content picker for any file types accepted by the page
		val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = params?.acceptTypes?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
			putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
		}

		val chooser = Intent(Intent.ACTION_CHOOSER).apply {
			putExtra(Intent.EXTRA_INTENT, contentIntent)
			putExtra(Intent.EXTRA_TITLE, "Select file")
			putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
		}
		fileChooserLauncher.launch(chooser)
	}

	private fun createImageFile(): File {
		val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
		val storageDir = cacheDir
		return File.createTempFile("IMG_${'$'}timeStamp", ".jpg", storageDir)
	}

	private fun requestNotificationsPermissionIfNeeded() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
		}
	}

	private fun requestStoragePermissionsIfNeeded() {
		val permissions = mutableListOf<String>()
		
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
			permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
		}
		
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			permissions.add(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
		}
		
		if (permissions.isNotEmpty()) {
			storagePermissionLauncher.launch(permissions.toTypedArray())
		}
	}

	private fun buildCommonPermissions(): Array<String> {
		val list = mutableListOf<String>()
		// Camera & audio
		list.add(android.Manifest.permission.CAMERA)
		list.add(android.Manifest.permission.RECORD_AUDIO)
		// Location
		list.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
		list.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
		// Bluetooth
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			list.add(android.Manifest.permission.BLUETOOTH_CONNECT)
			list.add(android.Manifest.permission.BLUETOOTH_SCAN)
		}
		// Storage / media (scoped)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			list.add(android.Manifest.permission.READ_MEDIA_IMAGES)
			list.add(android.Manifest.permission.READ_MEDIA_VIDEO)
			list.add(android.Manifest.permission.READ_MEDIA_AUDIO)
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			list.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
			if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
				list.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
			}
		}
		// Notifications (Android 13+)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			list.add(android.Manifest.permission.POST_NOTIFICATIONS)
		}
		// Nearby Wi-Fi (Android 13+)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			list.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
		}
		return list.distinct().toTypedArray()
	}

	private fun parsePermissionsJson(permsJson: String?): List<String> {
		return try {
			if (permsJson.isNullOrBlank()) return buildCommonPermissions().toList()
			val arr = org.json.JSONArray(permsJson)
			val out = mutableListOf<String>()
			for (i in 0 until arr.length()) { val p = arr.optString(i); if (p.isNotBlank()) out.add(p) }
			if (out.isEmpty()) buildCommonPermissions().toList() else out
		} catch (_: Exception) { buildCommonPermissions().toList() }
	}

	private fun requestGenericPermissions(perms: Array<String>, callback: (Map<String, Boolean>) -> Unit) {
		pendingPermissionsCallback = callback
		genericPermissionsLauncher.launch(perms)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		webView.saveState(outState)
	}

	override fun onBackPressed() {
		if (::webView.isInitialized && webView.canGoBack()) {
			webView.goBack()
		} else {
			if (isKioskEnabled()) {
				return
			}
			super.onBackPressed()
		}
	}

	override fun onDestroy() {
		usbPrinter?.close()
		escPosPrinter?.close()
		lanPrinter?.close()
		super.onDestroy()
	}
}
