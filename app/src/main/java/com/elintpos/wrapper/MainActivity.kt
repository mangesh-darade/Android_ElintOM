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
import com.elintpos.wrapper.printer.vendor.VendorPrinter
import com.elintpos.wrapper.printer.vendor.EpsonPrinter
import com.elintpos.wrapper.printer.vendor.XPrinter
import com.elintpos.wrapper.printer.PrinterConfigManager
import com.elintpos.wrapper.printer.PrinterTester
import com.elintpos.wrapper.sdk.SdkDownloader
import android.content.SharedPreferences
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

	companion object {
		// Replace YOUR_DOMAIN_HERE with your actual domain (no scheme). Example: "pos.example.com"
		private const val BASE_DOMAIN = "devmeenatshi.elintpos.in"
		private const val BASE_URL = "http://$BASE_DOMAIN/"
		private const val USER_AGENT_SUFFIX = " DesktopAndroidWebView/1366x768"
		private const val TAG = "ElintPOS"
		private const val ACTION_USB_PERMISSION = "com.elintpos.wrapper.USB_PERMISSION"
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
    private val vendorPrinter: VendorPrinter by lazy { VendorPrinter(this) }
    private val epsonPrinter: EpsonPrinter by lazy { EpsonPrinter(this) }
    private val xPrinter: XPrinter by lazy { XPrinter(this) }

	private var pendingUsbDeviceName: String? = null
	private var pendingUsbAfterConnect: (() -> Unit)? = null

	private val usbPermissionReceiver = object : android.content.BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			try {
				if (intent?.action != ACTION_USB_PERMISSION) return
				val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
				val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
				val pendingName = pendingUsbDeviceName
				pendingUsbDeviceName = null
				if (device != null && granted) {
					try {
						val usbManager = getSystemService(USB_SERVICE) as UsbManager
						val target = if (pendingName != null) {
							usbManager.deviceList.values.firstOrNull { it.deviceName == pendingName }
						} else device
						if (target != null) {
							usbPrinter?.close()
							usbPrinter = UsbEscPosPrinter(this@MainActivity)
							usbPrinter!!.connect(target)
							isUsbConnected = true
							Toast.makeText(this@MainActivity, "USB permission granted. Printer connected", Toast.LENGTH_SHORT).show()
							try { pendingUsbAfterConnect?.invoke() } catch (_: Exception) {} finally { pendingUsbAfterConnect = null }
						}
					} catch (e: Exception) {
						isUsbConnected = false
						Toast.makeText(this@MainActivity, "USB connect failed: ${e.message}", Toast.LENGTH_SHORT).show()
					}
				} else {
					Toast.makeText(this@MainActivity, "USB permission denied", Toast.LENGTH_SHORT).show()
				}
			} catch (_: Exception) {}
		}
	}

	private val prefs: SharedPreferences by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
	private val printerConfigManager: PrinterConfigManager by lazy { PrinterConfigManager(this) }
	private val printerTester: PrinterTester by lazy { PrinterTester(this) }
	private val sdkDownloader: SdkDownloader by lazy { SdkDownloader(this) }

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
		// Register USB permission receiver
		registerReceiver(usbPermissionReceiver, android.content.IntentFilter(ACTION_USB_PERMISSION))

		// Create main layout with WebView and settings button
		val mainLayout = android.widget.RelativeLayout(this)
		webView = WebView(this)
		mainLayout.addView(webView)
		
		// Add floating settings button
		val settingsButton = createSettingsButton()
		mainLayout.addView(settingsButton)
		
		setContentView(mainLayout)

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
			fun vendorAvailable(): Boolean {
				return try { vendorPrinter.isAvailable() } catch (_: Exception) { false }
			}
			@android.webkit.JavascriptInterface
			fun epsonAvailable(): Boolean {
				return try { epsonPrinter.isAvailable() } catch (_: Exception) { false }
			}

			@android.webkit.JavascriptInterface
			fun xprinterAvailable(): Boolean {
				return try { xPrinter.isAvailable() } catch (_: Exception) { false }
			}

			@android.webkit.JavascriptInterface
			fun epsonPrintText(text: String): String {
				return try {
					if (!epsonPrinter.isAvailable()) return "{\"ok\":false,\"msg\":\"Epson SDK not available\"}"
					val ok = epsonPrinter.printText(text)
					if (ok) "{\"ok\":true}" else "{\"ok\":false,\"msg\":\"Epson print failed\"}"
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun xprinterPrintText(text: String): String {
				return try {
					if (!xPrinter.isAvailable()) return "{\"ok\":false,\"msg\":\"XPrinter SDK not available\"}"
					val ok = xPrinter.printText(text)
					if (ok) "{\"ok\":true}" else "{\"ok\":false,\"msg\":\"XPrinter print failed\"}"
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun vendorPrintText(text: String): String {
				return try {
					if (!vendorPrinter.isAvailable()) return "{\"ok\":false,\"msg\":\"Vendor SDK not available\"}"
					val ok = vendorPrinter.printText(text)
					if (ok) "{\"ok\":true}" else "{\"ok\":false,\"msg\":\"Vendor print failed\"}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}
			@android.webkit.JavascriptInterface
			fun choosePrinterAndPrint(text: String): String {
				return try {
					runOnUiThread { showPrinterChooserAndPrint(text) }
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}
			@android.webkit.JavascriptInterface
			fun listCrashLogs(): String {
				return try {
					val dir = java.io.File(filesDir, "crash")
					if (!dir.exists()) return "{\"ok\":true,\"files\":[]}"
					val arr = org.json.JSONArray()
					dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
						val o = org.json.JSONObject()
						o.put("name", f.name)
						o.put("path", f.absolutePath)
						o.put("size", f.length())
						o.put("lastModified", f.lastModified())
						arr.put(o)
					}
					"{\"ok\":true,\"files\":${'$'}arr}"
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun readCrashLog(path: String): String {
				return try {
					val file = java.io.File(path)
					if (!file.exists()) return "{\"ok\":false,\"msg\":\"Not found\"}"
					val txt = file.readText()
					val o = org.json.JSONObject()
					o.put("ok", true)
					o.put("name", file.name)
					o.put("path", file.absolutePath)
					o.put("content", txt)
					o.toString()
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}

			@android.webkit.JavascriptInterface
			fun shareCrashLog(path: String): String {
				return try {
					val file = java.io.File(path)
					if (!file.exists()) return "{\"ok\":false,\"msg\":\"Not found\"}"
					val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${'$'}packageName.fileprovider", file)
					val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
						type = "text/plain"
						putExtra(android.content.Intent.EXTRA_SUBJECT, "Crash log: ${'$'}{file.name}")
						putExtra(android.content.Intent.EXTRA_STREAM, uri)
						addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
					}
					startActivity(android.content.Intent.createChooser(intent, "Share crash log"))
					"{\"ok\":true}"
				} catch (e: Exception) { "{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}" }
			}
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
			fun requestUsbPermission(deviceName: String): String {
				return try {
					val usbManager = getSystemService(USB_SERVICE) as UsbManager
					val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
						?: return "{\"ok\":false,\"msg\":\"Not found\"}"
					if (usbManager.hasPermission(device)) {
						"{\"ok\":true,\"msg\":\"Already granted\"}"
					} else {
						pendingUsbDeviceName = deviceName
						val pi = android.app.PendingIntent.getBroadcast(
							this@MainActivity,
							0,
							Intent(ACTION_USB_PERMISSION),
							if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0
						)
						usbManager.requestPermission(device, pi)
						"{\"ok\":true,\"msg\":\"Requested\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun connectUsbPrinter(deviceName: String): String {
				return try {
					val usbManager = getSystemService(USB_SERVICE) as UsbManager
					val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
						?: return "{\"ok\":false,\"msg\":\"Not found\"}"
					if (!usbManager.hasPermission(device)) {
						pendingUsbDeviceName = deviceName
						val pi = android.app.PendingIntent.getBroadcast(
							this@MainActivity,
							0,
							Intent(ACTION_USB_PERMISSION),
							if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0
						)
						usbManager.requestPermission(device, pi)
						return "{\"ok\":false,\"msg\":\"USB permission requested\"}"
					}
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
				return try {
					val obj = JSONObject()
					obj.put("bt", isBtConnected)
					obj.put("usb", isUsbConnected)
					obj.put("lan", isLanConnected)
					obj.put("any", isBtConnected || isUsbConnected || isLanConnected)
					
					// Add detailed USB device information
					val usbManager = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
					val deviceList = usbManager.deviceList
					val usbDevices = deviceList.values.map { device ->
						val deviceInfo = JSONObject()
						deviceInfo.put("deviceName", device.deviceName ?: "Unknown")
						deviceInfo.put("vendorId", device.vendorId)
						deviceInfo.put("productId", device.productId)
						deviceInfo.put("deviceClass", device.deviceClass)
						deviceInfo.put("deviceSubclass", device.deviceSubclass)
						deviceInfo.put("deviceProtocol", device.deviceProtocol)
						deviceInfo
					}
					obj.put("usbDevices", JSONArray(usbDevices))
					
					// Add connection details
					val connectionDetails = JSONObject()
					connectionDetails.put("usbPrinterInstance", usbPrinter != null)
					connectionDetails.put("btPrinterInstance", escPosPrinter != null)
					connectionDetails.put("lanPrinterInstance", lanPrinter != null)
					obj.put("connectionDetails", connectionDetails)
					
					obj.toString()
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Error getting printer status: ${e.message}\"}"
				}
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

			@android.webkit.JavascriptInterface
			fun showPrinterSettingsPopup(): String {
				return try {
					runOnUiThread {
						showPrinterSettingsPopup()
					}
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun showQuickSettingsPopup(): String {
				return try {
					runOnUiThread {
						showQuickSettingsPopup()
					}
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun openPrinterSettingsUI(): String {
				return try {
					runOnUiThread {
						openPrinterSettingsUI()
					}
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun openSettingsDemo(): String {
				return try {
					webView.loadUrl("file:///android_asset/settings_demo.html")
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}
			
			@android.webkit.JavascriptInterface
			fun getPrinterConnectionStatus(): String {
				return try {
					val status = mutableMapOf<String, Any>()
					status["usb"] = isUsbConnected
					status["bluetooth"] = isBtConnected
					status["lan"] = isLanConnected
					status["usbPrinter"] = usbPrinter != null
					status["btPrinter"] = escPosPrinter != null
					status["lanPrinter"] = lanPrinter != null
					
					// Check for available USB devices
					val usbManager = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
					val deviceList = usbManager.deviceList
					val availableUsbDevices = deviceList.values.map { device ->
						mapOf(
							"deviceName" to device.deviceName,
							"vendorId" to device.vendorId,
							"productId" to device.productId
						)
					}
					status["availableUsbDevices"] = availableUsbDevices
					
					org.json.JSONObject(status as Map<Any?, Any?>).toString()
				} catch (e: Exception) {
					"{\"error\":\"${e.message}\"}"
				}
			}
			
			@android.webkit.JavascriptInterface
			fun connectUsbDeviceByPath(devicePath: String): String {
				return try {
					val usbManager = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
					val deviceList = usbManager.deviceList
					
					// Find device by path
					val usbDevice = deviceList.values.firstOrNull { device ->
						device.deviceName == devicePath || device.deviceName?.contains(devicePath) == true
					}
					
					if (usbDevice != null) {
						try {
							usbPrinter?.close()
							usbPrinter = UsbEscPosPrinter(this@MainActivity)
							usbPrinter!!.connect(usbDevice)
							isUsbConnected = true
							"{\"ok\":true,\"msg\":\"USB device connected: ${usbDevice.deviceName} (VID: 0x${usbDevice.vendorId.toString(16).uppercase()}, PID: 0x${usbDevice.productId.toString(16).uppercase()})\"}"
						} catch (connectError: Exception) {
							"{\"ok\":false,\"msg\":\"Failed to connect to device: ${connectError.message}. Device: ${usbDevice.deviceName}\"}"
						}
					} else {
						"{\"ok\":false,\"msg\":\"Device not found: $devicePath. Available devices: ${deviceList.keys.joinToString()}\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Error connecting to device: ${e.message}\"}"
				}
			}
			
			@android.webkit.JavascriptInterface
			fun showPrinterSelector(): String {
				return try {
					runOnUiThread {
						// Load the printer selector overlay
						webView.loadUrl("file:///android_asset/printer_selector.html")
					}
					"{\"ok\":true,\"msg\":\"Printer selector opened\"}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Failed to open printer selector: ${e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun showSdkInstaller(): String {
				return try {
					runOnUiThread {
						// Load the SDK installer page
						webView.loadUrl("file:///android_asset/sdk_installer.html")
					}
					"{\"ok\":true,\"msg\":\"SDK installer opened\"}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Failed to open SDK installer: ${e.message}\"}"
				}
			}
			
			@android.webkit.JavascriptInterface
			fun closePrinterSelector(): String {
				return try {
					runOnUiThread {
						// Go back to the previous page or main page
						if (webView.canGoBack()) {
							webView.goBack()
						} else {
							webView.loadUrl("file:///android_asset/index.html")
						}
					}
					"{\"ok\":true,\"msg\":\"Printer selector closed\"}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Failed to close printer selector: ${e.message}\"}"
				}
			}
			
			@android.webkit.JavascriptInterface
			fun printWebContent(content: String, printerType: String = "auto"): String {
				return try {
					val printConfig = PrintConfig(
						leftMargin = 0,
						rightMargin = 0,
						lineSpacing = 30,
						widthMul = 0,
						heightMul = 0,
						pageWidthDots = 384,
						linesPerPage = 0
					)
					
					when (printerType.lowercase()) {
						"usb" -> {
							if (isUsbConnected && usbPrinter != null) {
								usbPrinter!!.printText(
									content,
									leftMarginDots = printConfig.leftMargin,
									rightMarginDots = printConfig.rightMargin,
									lineSpacing = printConfig.lineSpacing,
									widthMultiplier = printConfig.widthMul,
									heightMultiplier = printConfig.heightMul,
									pageWidthDots = printConfig.pageWidthDots,
									linesPerPage = printConfig.linesPerPage.takeIf { it > 0 }
								)
								"{\"ok\":true,\"msg\":\"Content sent to USB printer\"}"
							} else {
								"{\"ok\":false,\"msg\":\"USB printer not connected\"}"
							}
						}
						"bluetooth", "bt" -> {
							if (isBtConnected && escPosPrinter != null) {
								escPosPrinter!!.printText(
									content,
									leftMarginDots = printConfig.leftMargin,
									rightMarginDots = printConfig.rightMargin,
									lineSpacing = printConfig.lineSpacing,
									widthMultiplier = printConfig.widthMul,
									heightMultiplier = printConfig.heightMul,
									pageWidthDots = printConfig.pageWidthDots,
									linesPerPage = printConfig.linesPerPage.takeIf { it > 0 }
								)
								"{\"ok\":true,\"msg\":\"Content sent to Bluetooth printer\"}"
							} else {
								"{\"ok\":false,\"msg\":\"Bluetooth printer not connected\"}"
							}
						}
						"lan", "network" -> {
							if (isLanConnected && lanPrinter != null) {
								lanPrinter!!.printText(
									content,
									leftMarginDots = printConfig.leftMargin,
									rightMarginDots = printConfig.rightMargin,
									lineSpacing = printConfig.lineSpacing,
									widthMultiplier = printConfig.widthMul,
									heightMultiplier = printConfig.heightMul,
									pageWidthDots = printConfig.pageWidthDots,
									linesPerPage = printConfig.linesPerPage.takeIf { it > 0 }
								)
								"{\"ok\":true,\"msg\":\"Content sent to LAN printer\"}"
							} else {
								"{\"ok\":false,\"msg\":\"LAN printer not connected\"}"
							}
						}
						"auto" -> {
							// Try to print using any available printer
							when {
								isUsbConnected && usbPrinter != null -> {
									usbPrinter!!.printText(
										content,
										leftMarginDots = printConfig.leftMargin,
										rightMarginDots = printConfig.rightMargin,
										lineSpacing = printConfig.lineSpacing,
										widthMultiplier = printConfig.widthMul,
										heightMultiplier = printConfig.heightMul,
										pageWidthDots = printConfig.pageWidthDots,
										linesPerPage = printConfig.linesPerPage.takeIf { it > 0 }
									)
									"{\"ok\":true,\"msg\":\"Content sent to USB printer\"}"
								}
								isBtConnected && escPosPrinter != null -> {
									escPosPrinter!!.printText(
										content,
										leftMarginDots = printConfig.leftMargin,
										rightMarginDots = printConfig.rightMargin,
										lineSpacing = printConfig.lineSpacing,
										widthMultiplier = printConfig.widthMul,
										heightMultiplier = printConfig.heightMul,
										pageWidthDots = printConfig.pageWidthDots,
										linesPerPage = printConfig.linesPerPage.takeIf { it > 0 }
									)
									"{\"ok\":true,\"msg\":\"Content sent to Bluetooth printer\"}"
								}
								isLanConnected && lanPrinter != null -> {
									lanPrinter!!.printText(
										content,
										leftMarginDots = printConfig.leftMargin,
										rightMarginDots = printConfig.rightMargin,
										lineSpacing = printConfig.lineSpacing,
										widthMultiplier = printConfig.widthMul,
										heightMultiplier = printConfig.heightMul,
										pageWidthDots = printConfig.pageWidthDots,
										linesPerPage = printConfig.linesPerPage.takeIf { it > 0 }
									)
									"{\"ok\":true,\"msg\":\"Content sent to LAN printer\"}"
								}
								else -> "{\"ok\":false,\"msg\":\"No printer connected\"}"
							}
						}
						else -> "{\"ok\":false,\"msg\":\"Unknown printer type: $printerType\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Print error: ${e.message}\"}"
				}
			}



			@android.webkit.JavascriptInterface
			fun showNativePrintDialog(): String {
				return try {
					runOnUiThread {
						val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
						val printAdapter = webView.createPrintDocumentAdapter("Web Content")
						val printJob = printManager.print(
							"Web Print Job",
							printAdapter,
							PrintAttributes.Builder()
								.setMediaSize(PrintAttributes.MediaSize.ISO_A4)
								.setResolution(PrintAttributes.Resolution("printer", "printer", 600, 600))
								.setColorMode(PrintAttributes.COLOR_MODE_COLOR)
								.build()
						)
					}
					"{\"ok\":true,\"msg\":\"Native print dialog opened\"}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Failed to open print dialog: ${e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun getConnectedPrinterNames(): String {
				return try {
					val printers = mutableListOf<Map<String, Any>>()
					
					// Get USB printer names
					if (isUsbConnected && usbPrinter != null) {
						val usbManager = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
						val deviceList = usbManager.deviceList
						deviceList.values.forEach { device ->
							val printerMap = mutableMapOf<String, Any>()
							printerMap["name"] = device.deviceName ?: "USB Printer"
							printerMap["type"] = "USB"
							printerMap["connected"] = true
							printerMap["vendorId"] = device.vendorId
							printerMap["productId"] = device.productId
							printers.add(printerMap)
						}
					}
					
					// Get Bluetooth printer names
					if (isBtConnected && escPosPrinter != null) {
						val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
						val pairedDevices = bluetoothAdapter?.bondedDevices
						pairedDevices?.forEach { device ->
							if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
								val printerMap = mutableMapOf<String, Any>()
								printerMap["name"] = device.name ?: "Bluetooth Printer"
								printerMap["type"] = "Bluetooth"
								printerMap["connected"] = true
								printerMap["address"] = device.address
								printers.add(printerMap)
							}
						}
					}
					
					// Get LAN printer names
					if (isLanConnected && lanPrinter != null) {
						val printerMap = mutableMapOf<String, Any>()
						printerMap["name"] = "LAN Printer"
						printerMap["type"] = "Network"
						printerMap["connected"] = true
						printerMap["ip"] = "192.168.1.100" // You can get actual IP from your LAN printer
						printers.add(printerMap)
					}
					
					org.json.JSONObject(mapOf("printers" to printers)).toString()
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Error getting printer names: ${e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun testEscPosPrinting(): String {
				return try {
					val testContent = """
						================================
						ESC/POS PRINTING TEST
						================================
						
						This test verifies that ESC/POS printing
						works without vendor SDKs.
						
						Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}
						
						Features tested:
						- USB Printing: ${if (isUsbConnected) "Available" else "Not Connected"}
						- Bluetooth Printing: ${if (isBtConnected) "Available" else "Not Connected"}
						- LAN Printing: ${if (isLanConnected) "Available" else "Not Connected"}
						
						================================
						END OF TEST
						================================
						
						
						
					""".trimIndent()
					
					// Try to print using any available printer
					val result = printWebContent(testContent, "auto")
					val response = JSONObject(result)
					
					if (response.getBoolean("ok")) {
						"{\"ok\":true,\"msg\":\"ESC/POS printing test successful: ${response.getString("msg")}\"}"
					} else {
						"{\"ok\":false,\"msg\":\"ESC/POS printing test failed: ${response.getString("msg")}\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"ESC/POS test error: ${e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun downloadEpsonSdk(): String {
				return try {
					runBlocking {
						val result = sdkDownloader.downloadEpsonSdk()
						when (result) {
							is SdkDownloader.DownloadResult.Success -> 
								"{\"ok\":true,\"msg\":\"${result.message}\"}"
							is SdkDownloader.DownloadResult.Error -> 
								"{\"ok\":false,\"msg\":\"${result.message}\"}"
							is SdkDownloader.DownloadResult.Info -> 
								"{\"ok\":true,\"msg\":\"${result.message}\"}"
						}
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Download error: ${e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun downloadXPrinterSdk(): String {
				return try {
					runBlocking {
						val result = sdkDownloader.downloadXPrinterSdk()
						when (result) {
							is SdkDownloader.DownloadResult.Success -> 
								"{\"ok\":true,\"msg\":\"${result.message}\"}"
							is SdkDownloader.DownloadResult.Error -> 
								"{\"ok\":false,\"msg\":\"${result.message}\"}"
							is SdkDownloader.DownloadResult.Info -> 
								"{\"ok\":true,\"msg\":\"${result.message}\"}"
						}
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Download error: ${e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun checkSdkAvailability(): String {
				return try {
					val availability = sdkDownloader.checkSdkAvailability()
					val json = org.json.JSONObject(availability as Map<Any?, Any?>)
					"{\"ok\":true,\"availability\":$json}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Error checking SDK availability: ${e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun installAllSdks(): String {
				return try {
					runBlocking {
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
						
						"{\"ok\":true,\"msg\":\"${results.joinToString("\\n")}\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Installation error: ${e.message}\"}"
				}
			}
			
			@android.webkit.JavascriptInterface
			fun testProfileSaving(): String {
				return try {
					// Create a test USB profile
					val testProfile = PrinterConfigManager.PrinterConfig(
						type = "usb",
						name = "Test USB Printer",
						enabled = true,
						paperWidth = 384,
						connectionParams = mapOf(
							"devicePath" to "/dev/bus/usb/005/002",
							"vendorId" to "0x04E8",
							"productId" to "0x1234"
						)
					)
					
					val success = printerConfigManager.saveProfile(testProfile)
					if (success) {
						"{\"ok\":true,\"msg\":\"Test profile saved successfully\"}"
					} else {
						"{\"ok\":false,\"msg\":\"Failed to save test profile\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Test profile save error: ${e.message}\"}"
				}
			}
			
			@android.webkit.JavascriptInterface
			fun autoConnectUsbPrinter(): String {
				return try {
					val usbManager = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
					val deviceList = usbManager.deviceList
					
					// First try to find a known printer vendor ID
					var usbDevice = deviceList.values.firstOrNull { device ->
						val vid = device.vendorId
						vid == 0x04E8 || vid == 0x04B8 || vid == 0x04F9 || vid == 0x0FE6 || vid == 0x154F || 
						vid == 0x03F0 || vid == 0x04A9 || vid == 0x0BDA || vid == 0x0ACD || vid == 0x0B05
					}
					
					// If no known printer found, try any USB device (broader approach)
					if (usbDevice == null) {
						usbDevice = deviceList.values.firstOrNull { device ->
							// Look for devices that might be printers based on device class or name
							val deviceName = device.deviceName?.lowercase() ?: ""
							deviceName.contains("printer") || deviceName.contains("pos") || 
							deviceName.contains("thermal") || deviceName.contains("receipt")
						}
					}
					
					// If still no device found, try the first available USB device
					if (usbDevice == null && deviceList.isNotEmpty()) {
						usbDevice = deviceList.values.first()
					}
					
					if (usbDevice != null) {
						try {
							usbPrinter?.close()
							usbPrinter = UsbEscPosPrinter(this@MainActivity)
							usbPrinter!!.connect(usbDevice)
							isUsbConnected = true
							"{\"ok\":true,\"msg\":\"USB printer connected: ${usbDevice.deviceName} (VID: 0x${usbDevice.vendorId.toString(16).uppercase()})\"}"
						} catch (connectError: Exception) {
							"{\"ok\":false,\"msg\":\"Found USB device but failed to connect: ${connectError.message}. Device: ${usbDevice.deviceName} (VID: 0x${usbDevice.vendorId.toString(16).uppercase()})\"}"
						}
					} else {
						"{\"ok\":false,\"msg\":\"No USB devices found. Please check USB connection.\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"Failed to scan USB devices: ${e.message}\"}"
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

			// Printer Configuration Management Methods
			@android.webkit.JavascriptInterface
			fun getAllPrinterProfiles(): String {
				return try {
					val profiles = printerConfigManager.getAllProfiles()
					val profilesJson = org.json.JSONArray()
					profiles.forEach { profile ->
						profilesJson.put(profile.toJson())
					}
					"{\"ok\":true,\"profiles\":${'$'}profilesJson}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun getPrinterProfile(id: String): String {
				return try {
					val profile = printerConfigManager.getProfile(id)
					if (profile != null) {
						"{\"ok\":true,\"profile\":${'$'}{profile.toJson()}}"
					} else {
						"{\"ok\":false,\"msg\":\"Profile not found\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun savePrinterProfile(profileJson: String): String {
				return try {
					android.util.Log.d("MainActivity", "Saving profile: $profileJson")
					
					// Validate JSON first
					if (profileJson.isBlank()) {
						return "{\"ok\":false,\"msg\":\"Profile data is empty\"}"
					}
					
					val profileObj = org.json.JSONObject(profileJson)
					
					// Validate required fields
					if (!profileObj.has("type") || !profileObj.has("name")) {
						return "{\"ok\":false,\"msg\":\"Profile must have type and name fields\"}"
					}
					
					val profile = PrinterConfigManager.PrinterConfig.fromJson(profileObj)
					
					android.util.Log.d("MainActivity", "Parsed profile: type=${profile.type}, name=${profile.name}, id=${profile.id}")
					
					// Validate profile data
					if (profile.type.isBlank()) {
						return "{\"ok\":false,\"msg\":\"Profile type cannot be empty\"}"
					}
					if (profile.name.isBlank()) {
						return "{\"ok\":false,\"msg\":\"Profile name cannot be empty\"}"
					}
					
					val success = printerConfigManager.saveProfile(profile)
					if (success) {
						android.util.Log.d("MainActivity", "Profile saved successfully")
						"{\"ok\":true,\"profile\":${'$'}{profile.toJson()}}"
					} else {
						android.util.Log.e("MainActivity", "Failed to save profile")
						"{\"ok\":false,\"msg\":\"Failed to save profile to storage\"}"
					}
				} catch (e: org.json.JSONException) {
					android.util.Log.e("MainActivity", "JSON parsing error: ${e.message}", e)
					"{\"ok\":false,\"msg\":\"Invalid profile data format: ${e.message}\"}"
				} catch (e: Exception) {
					android.util.Log.e("MainActivity", "Error saving profile: ${e.message}", e)
					"{\"ok\":false,\"msg\":\"Error saving profile: ${e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun deletePrinterProfile(id: String): String {
				return try {
					val success = printerConfigManager.deleteProfile(id)
					if (success) {
						"{\"ok\":true}"
					} else {
						"{\"ok\":false,\"msg\":\"Failed to delete profile or profile is default\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun duplicatePrinterProfile(id: String, newName: String): String {
				return try {
					val duplicatedProfile = printerConfigManager.duplicateProfile(id, newName)
					if (duplicatedProfile != null) {
						"{\"ok\":true,\"profile\":${'$'}{duplicatedProfile.toJson()}}"
					} else {
						"{\"ok\":false,\"msg\":\"Failed to duplicate profile\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun setPrinterProfileAsDefault(id: String): String {
				return try {
					val success = printerConfigManager.setAsDefault(id)
					if (success) {
						"{\"ok\":true}"
					} else {
						"{\"ok\":false,\"msg\":\"Failed to set profile as default\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun getPrinterProfilesByType(type: String): String {
				return try {
					val profiles = printerConfigManager.getProfilesByType(type)
					val profilesJson = org.json.JSONArray()
					profiles.forEach { profile ->
						profilesJson.put(profile.toJson())
					}
					"{\"ok\":true,\"profiles\":${'$'}profilesJson}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun getDefaultPrinterProfile(type: String): String {
				return try {
					val profile = printerConfigManager.getDefaultProfile(type)
					if (profile != null) {
						"{\"ok\":true,\"profile\":${'$'}{profile.toJson()}}"
					} else {
						"{\"ok\":false,\"msg\":\"No default profile found for type\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun getLastUsedPrinterProfile(): String {
				return try {
					val profile = printerConfigManager.getLastUsedProfile()
					if (profile != null) {
						"{\"ok\":true,\"profile\":${'$'}{profile.toJson()}}"
					} else {
						"{\"ok\":false,\"msg\":\"No last used profile found\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun setLastUsedPrinterProfile(id: String): String {
				return try {
					printerConfigManager.setLastUsedProfile(id)
					"{\"ok\":true}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun getPrinterConfigStatistics(): String {
				return try {
					val stats = printerConfigManager.getStatistics()
					val statsJson = org.json.JSONObject()
					stats.forEach { (key, value) ->
						statsJson.put(key, value)
					}
					"{\"ok\":true,\"statistics\":${'$'}statsJson}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun exportPrinterProfiles(): String {
				return try {
					val profilesJson = printerConfigManager.exportProfiles()
					"{\"ok\":true,\"profiles\":${'$'}profilesJson}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun importPrinterProfiles(profilesJson: String): String {
				return try {
					val success = printerConfigManager.importProfiles(profilesJson)
					if (success) {
						"{\"ok\":true}"
					} else {
						"{\"ok\":false,\"msg\":\"Failed to import profiles\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun clearAllPrinterProfiles(): String {
				return try {
					val success = printerConfigManager.clearAllProfiles()
					if (success) {
						"{\"ok\":true}"
					} else {
						"{\"ok\":false,\"msg\":\"Failed to clear profiles\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun createPrinterProfileFromConnection(
				type: String,
				name: String,
				connectionParamsJson: String,
				paperWidth: Int = 576
			): String {
				return try {
					val connectionParams = org.json.JSONObject(connectionParamsJson)
					val paramsMap = mutableMapOf<String, String>()
					connectionParams.keys().forEach { key ->
						paramsMap[key] = connectionParams.getString(key)
					}
					val profile = printerConfigManager.createProfileFromConnection(type, name, paramsMap, paperWidth)
					val success = printerConfigManager.saveProfile(profile)
					if (success) {
						"{\"ok\":true,\"profile\":${'$'}{profile.toJson()}}"
					} else {
						"{\"ok\":false,\"msg\":\"Failed to create profile\"}"
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun testPrinterProfile(profileId: String, testText: String = "Test Print"): String {
				return try {
					val profile = printerConfigManager.getProfile(profileId)
					if (profile == null) {
						return "{\"ok\":false,\"msg\":\"Profile not found\"}"
					}
					
					// Apply profile configuration to current print settings
					defaultPrintConfig = PrintConfig(
						leftMargin = profile.leftMargin,
						rightMargin = profile.rightMargin,
						lineSpacing = profile.lineSpacing,
						widthMul = profile.widthMultiplier,
						heightMul = profile.heightMultiplier,
						pageWidthDots = profile.paperWidth,
						linesPerPage = 0
					)
					
					// Try to connect and print based on profile type
					when (profile.type) {
						PrinterConfigManager.TYPE_BLUETOOTH -> {
							val mac = profile.connectionParams["mac"]
							if (mac != null) {
								val connectResult = connectPrinter(mac)
								if (connectResult.contains("\"ok\":true")) {
									btPrint(testText)
								} else {
									connectResult
								}
							} else {
								"{\"ok\":false,\"msg\":\"No MAC address configured\"}"
							}
						}
						PrinterConfigManager.TYPE_USB -> {
							val deviceName = profile.connectionParams["deviceName"]
							if (deviceName != null) {
								val connectResult = connectUsbPrinter(deviceName)
								if (connectResult.contains("\"ok\":true")) {
									usbPrint(testText)
								} else {
									connectResult
								}
							} else {
								"{\"ok\":false,\"msg\":\"No device name configured\"}"
							}
						}
						PrinterConfigManager.TYPE_LAN -> {
							val ip = profile.connectionParams["ip"]
							val port = profile.connectionParams["port"]?.toIntOrNull() ?: 9100
							if (ip != null) {
								val connectResult = connectLanPrinter(ip, port)
								if (connectResult.contains("\"ok\":true")) {
									lanPrint(testText)
								} else {
									connectResult
								}
							} else {
								"{\"ok\":false,\"msg\":\"No IP address configured\"}"
							}
						}
						PrinterConfigManager.TYPE_EPSON -> {
							epsonPrintText(testText)
						}
						PrinterConfigManager.TYPE_XPRINTER -> {
							xprinterPrintText(testText)
						}
						PrinterConfigManager.TYPE_VENDOR -> {
							vendorPrintText(testText)
						}
						else -> {
							"{\"ok\":false,\"msg\":\"Unknown printer type\"}"
						}
					}
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			// Printer Testing and Validation Methods
			@android.webkit.JavascriptInterface
			fun testPrinterProfileComprehensive(profileId: String, testText: String = "Comprehensive Test"): String {
				return try {
					val profile = printerConfigManager.getProfile(profileId)
					if (profile == null) {
						return "{\"ok\":false,\"msg\":\"Profile not found\"}"
					}
					
					val testResult = printerTester.testPrinterProfile(profile)
					"{\"ok\":true,\"result\":${'$'}{testResult.toJson()}}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun runPrinterDiagnostics(): String {
				return try {
					val diagnostics = printerTester.runDiagnostics()
					val diagnosticsJson = org.json.JSONObject()
					diagnostics.forEach { (key, value) ->
						when (value) {
							is String -> diagnosticsJson.put(key, value)
							is Int -> diagnosticsJson.put(key, value)
							is Boolean -> diagnosticsJson.put(key, value)
							is Double -> diagnosticsJson.put(key, value)
							else -> diagnosticsJson.put(key, value.toString())
						}
					}
					"{\"ok\":true,\"diagnostics\":${'$'}diagnosticsJson}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun validatePrinterProfile(profileId: String): String {
				return try {
					val profile = printerConfigManager.getProfile(profileId)
					if (profile == null) {
						return "{\"ok\":false,\"msg\":\"Profile not found\"}"
					}
					
					val validationResults = mutableListOf<String>()
					var isValid = true
					
					// Validate required fields
					if (profile.name.isBlank()) {
						validationResults.add("Profile name is required")
						isValid = false
					}
					
					if (profile.type.isBlank()) {
						validationResults.add("Printer type is required")
						isValid = false
					}
					
					// Validate connection parameters based on type
					when (profile.type) {
						PrinterConfigManager.TYPE_BLUETOOTH -> {
							if (profile.connectionParams["mac"].isNullOrBlank()) {
								validationResults.add("MAC address is required for Bluetooth printers")
								isValid = false
							}
						}
						PrinterConfigManager.TYPE_USB -> {
							if (profile.connectionParams["deviceName"].isNullOrBlank()) {
								validationResults.add("Device name is required for USB printers")
								isValid = false
							}
						}
						PrinterConfigManager.TYPE_LAN -> {
							if (profile.connectionParams["ip"].isNullOrBlank()) {
								validationResults.add("IP address is required for LAN printers")
								isValid = false
							}
							val port = profile.connectionParams["port"]?.toIntOrNull()
							if (port == null || port < 1 || port > 65535) {
								validationResults.add("Valid port number (1-65535) is required for LAN printers")
								isValid = false
							}
						}
					}
					
					// Validate numeric ranges
					if (profile.paperWidth < 200 || profile.paperWidth > 2000) {
						validationResults.add("Paper width should be between 200-2000 dots")
						isValid = false
					}
					
					if (profile.leftMargin < 0 || profile.leftMargin > 200) {
						validationResults.add("Left margin should be between 0-200 dots")
						isValid = false
					}
					
					if (profile.rightMargin < 0 || profile.rightMargin > 200) {
						validationResults.add("Right margin should be between 0-200 dots")
						isValid = false
					}
					
					if (profile.lineSpacing < 0 || profile.lineSpacing > 100) {
						validationResults.add("Line spacing should be between 0-100")
						isValid = false
					}
					
					if (profile.widthMultiplier < 0 || profile.widthMultiplier > 7) {
						validationResults.add("Width multiplier should be between 0-7")
						isValid = false
					}
					
					if (profile.heightMultiplier < 0 || profile.heightMultiplier > 7) {
						validationResults.add("Height multiplier should be between 0-7")
						isValid = false
					}
					
					if (profile.timeout < 1000 || profile.timeout > 30000) {
						validationResults.add("Timeout should be between 1000-30000 milliseconds")
						isValid = false
					}
					
					val result = org.json.JSONObject()
					result.put("valid", isValid)
					result.put("errors", org.json.JSONArray(validationResults))
					
					"{\"ok\":true,\"validation\":${'$'}result}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun testAllPrinterProfiles(): String {
				return try {
					val profiles = printerConfigManager.getAllProfiles()
					val results = org.json.JSONArray()
					
					profiles.forEach { profile ->
						val testResult = printerTester.testPrinterProfile(profile)
						val resultObj = org.json.JSONObject()
						resultObj.put("profileId", profile.id)
						resultObj.put("profileName", profile.name)
						resultObj.put("profileType", profile.type)
						resultObj.put("testResult", testResult.toJson())
						results.put(resultObj)
					}
					
					"{\"ok\":true,\"results\":${'$'}results}"
				} catch (e: Exception) {
					"{\"ok\":false,\"msg\":\"${'$'}{e.message}\"}"
				}
			}

			@android.webkit.JavascriptInterface
			fun getPrinterTestReport(profileId: String): String {
				return try {
					val profile = printerConfigManager.getProfile(profileId)
					if (profile == null) {
						return "{\"ok\":false,\"msg\":\"Profile not found\"}"
					}
					
					val testResult = printerTester.testPrinterProfile(profile)
					val diagnostics = printerTester.runDiagnostics()
					
					val report = org.json.JSONObject()
					report.put("profile", profile.toJson())
					report.put("testResult", testResult.toJson())
					report.put("diagnostics", org.json.JSONObject(diagnostics))
					report.put("timestamp", System.currentTimeMillis())
					
					"{\"ok\":true,\"report\":${'$'}report}"
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

	private fun createSettingsButton(): android.widget.ImageButton {
		val settingsButton = android.widget.ImageButton(this)
		val layoutParams = android.widget.RelativeLayout.LayoutParams(
			android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
			android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
		)
		layoutParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
		layoutParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
		layoutParams.setMargins(0, 50, 20, 0)
		settingsButton.layoutParams = layoutParams
		
		// Set button properties with modern FAB style
		settingsButton.setImageResource(android.R.drawable.ic_menu_preferences)
		settingsButton.background = android.graphics.drawable.GradientDrawable().apply {
			shape = android.graphics.drawable.GradientDrawable.OVAL
			setColor(android.graphics.Color.parseColor("#FF6200EA")) // Purple color
			setStroke(4, android.graphics.Color.parseColor("#FFFFFFFF")) // White border
		}
		settingsButton.setPadding(24, 24, 24, 24)
		settingsButton.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
		settingsButton.elevation = 12f
		
		// Add ripple effect
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			val rippleDrawable = android.graphics.drawable.RippleDrawable(
				android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#40FFFFFF")),
				settingsButton.background,
				null
			)
			settingsButton.background = rippleDrawable
		}
		
		// Add click listener
		settingsButton.setOnClickListener {
			showPrinterSettingsPopup()
		}
		
		// Add long click listener for additional options
		settingsButton.setOnLongClickListener {
			showAdvancedSettingsMenu()
			true
		}
		
		return settingsButton
	}

	private fun showAdvancedSettingsMenu() {
		val options = arrayOf(
			"Quick Settings",
			"Printer Settings UI",
			"Full Printer Management",
			"Settings Demo Page",
			"Test All Printers",
			"Printer Diagnostics",
			"Export Settings",
			"Import Settings"
		)
		
		val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
			.setTitle("⚙️ Advanced Printer Settings")
			.setItems(options) { _, which ->
				when (which) {
					0 -> showQuickSettingsPopup()
					1 -> openPrinterSettingsUI()
					2 -> openPrinterManagement()
					3 -> openSettingsDemo()
					4 -> testAllPrinters()
					5 -> showPrinterDiagnostics()
					6 -> exportPrinterSettings()
					7 -> importPrinterSettings()
				}
			}
			.setNegativeButton("Cancel", null)
			.create()
		
		dialog.show()
	}

	private fun testAllPrinters() {
		try {
			val profiles = printerConfigManager.getAllProfiles()
			var successCount = 0
			var totalCount = profiles.size
			
			profiles.forEach { profile ->
				try {
					val testResult = printerTester.testPrinterProfile(profile)
					if (testResult.success) {
						successCount++
					}
				} catch (e: Exception) {
					// Individual test failed, continue with others
				}
			}
			
			Toast.makeText(this, "Tested $successCount/$totalCount printers successfully", Toast.LENGTH_LONG).show()
		} catch (e: Exception) {
			Toast.makeText(this, "Test error: ${e.message}", Toast.LENGTH_SHORT).show()
		}
	}

	private fun showPrinterDiagnostics() {
		try {
			val diagnostics = printerTester.runDiagnostics()
			val message = buildString {
				appendLine("Printer Diagnostics:")
				appendLine("Bluetooth: ${if (diagnostics["bluetooth_available"] == true) "Available" else "Not Available"}")
				appendLine("USB Devices: ${diagnostics["usb_devices_count"]}")
				appendLine("Network: ${if (diagnostics["network_connected"] == true) "Connected" else "Disconnected"}")
				appendLine("ESC/POS Printing: ${if (diagnostics["escpos_available"] == true) "Available" else "Not Available"}")
				appendLine("USB Printing: ${if (diagnostics["usb_printing_available"] == true) "Available" else "Not Available"}")
				appendLine("Bluetooth Printing: ${if (diagnostics["bluetooth_printing_available"] == true) "Available" else "Not Available"}")
				appendLine("LAN Printing: ${if (diagnostics["lan_printing_available"] == true) "Available" else "Not Available"}")
				appendLine("Epson SDK: ${if (diagnostics["epson_sdk_available"] == true) "Available" else "Not Available (ESC/POS works)"}")
				appendLine("XPrinter SDK: ${if (diagnostics["xprinter_sdk_available"] == true) "Available" else "Not Available (ESC/POS works)"}")
			}
			
			val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
				.setTitle("🔍 Printer Diagnostics")
				.setMessage(message)
				.setPositiveButton("OK", null)
				.create()
			dialog.show()
		} catch (e: Exception) {
			Toast.makeText(this, "Diagnostics error: ${e.message}", Toast.LENGTH_SHORT).show()
		}
	}

	private fun exportPrinterSettings() {
		try {
			val profiles = printerConfigManager.exportProfiles()
			val profileCount = printerConfigManager.getAllProfiles().size
			// In a real implementation, you would save this to a file or share it
			Toast.makeText(this, "Settings exported successfully! ($profileCount profiles)", Toast.LENGTH_SHORT).show()
		} catch (e: Exception) {
			Toast.makeText(this, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
		}
	}

	private fun importPrinterSettings() {
		// In a real implementation, you would show a file picker or input dialog
		Toast.makeText(this, "Import functionality would open file picker", Toast.LENGTH_SHORT).show()
	}

	private fun showPrinterSettingsPopup() {
		val options = arrayOf(
			"Printer Settings UI",
			"Quick Settings", 
			"Full Printer Management"
		)
		
		val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
			.setTitle("🖨️ Printer Configuration")
			.setItems(options) { _, which ->
				when (which) {
					0 -> openPrinterSettingsUI()
					1 -> showQuickSettingsPopup()
					2 -> openPrinterManagement()
				}
			}
			.setNegativeButton("Cancel", null)
			.create()
		
		dialog.show()
	}

	private fun showQuickSettingsPopup() {
		val quickSettingsView = createQuickSettingsView()
		val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
			.setTitle("⚙️ Quick Printer Settings")
			.setView(quickSettingsView)
			.setPositiveButton("Apply", null)
			.setNegativeButton("Cancel", null)
			.create()
		
		dialog.show()
		
		// Handle apply button click
		dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
			applyQuickSettings(quickSettingsView, dialog)
		}
	}

	private fun createQuickSettingsView(): android.view.View {
		val layout = android.widget.LinearLayout(this)
		layout.orientation = android.widget.LinearLayout.VERTICAL
		layout.setPadding(50, 30, 50, 30)
		
		// Paper width setting
		val paperWidthLayout = android.widget.LinearLayout(this)
		paperWidthLayout.orientation = android.widget.LinearLayout.HORIZONTAL
		paperWidthLayout.setPadding(0, 10, 0, 10)
		
		val paperWidthLabel = android.widget.TextView(this)
		paperWidthLabel.text = "Paper Width:"
		paperWidthLabel.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		
		val paperWidthSpinner = android.widget.Spinner(this)
		val paperWidthAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, 
			arrayOf("58mm (384 dots)", "80mm (576 dots)", "112mm (832 dots)"))
		paperWidthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
		paperWidthSpinner.adapter = paperWidthAdapter
		paperWidthSpinner.tag = "paperWidth"
		paperWidthSpinner.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		
		paperWidthLayout.addView(paperWidthLabel)
		paperWidthLayout.addView(paperWidthSpinner)
		
		// Line spacing setting
		val lineSpacingLayout = android.widget.LinearLayout(this)
		lineSpacingLayout.orientation = android.widget.LinearLayout.HORIZONTAL
		lineSpacingLayout.setPadding(0, 10, 0, 10)
		
		val lineSpacingLabel = android.widget.TextView(this)
		lineSpacingLabel.text = "Line Spacing:"
		lineSpacingLabel.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		
		val lineSpacingSeekBar = android.widget.SeekBar(this)
		lineSpacingSeekBar.max = 100
		lineSpacingSeekBar.progress = 30
		lineSpacingSeekBar.tag = "lineSpacing"
		lineSpacingSeekBar.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		
		val lineSpacingValue = android.widget.TextView(this)
		lineSpacingValue.text = "30"
		lineSpacingValue.tag = "lineSpacingValue"
		lineSpacingValue.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
		
		lineSpacingSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
				lineSpacingValue.text = progress.toString()
			}
			override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
			override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
		})
		
		lineSpacingLayout.addView(lineSpacingLabel)
		lineSpacingLayout.addView(lineSpacingSeekBar)
		lineSpacingLayout.addView(lineSpacingValue)
		
		// Test print button
		val testPrintButton = android.widget.Button(this)
		testPrintButton.text = "Test Print"
		testPrintButton.setPadding(0, 20, 0, 20)
		testPrintButton.setOnClickListener {
			testQuickPrint(layout)
		}
		
		layout.addView(paperWidthLayout)
		layout.addView(lineSpacingLayout)
		layout.addView(testPrintButton)
		
		return layout
	}

	private fun applyQuickSettings(view: android.view.View, dialog: androidx.appcompat.app.AlertDialog) {
		try {
			val paperWidthSpinner = view.findViewWithTag<android.widget.Spinner>("paperWidth")
			val lineSpacingSeekBar = view.findViewWithTag<android.widget.SeekBar>("lineSpacing")
			
			val paperWidths = intArrayOf(384, 576, 832)
			val paperWidth = paperWidths[paperWidthSpinner.selectedItemPosition]
			val lineSpacing = lineSpacingSeekBar.progress
			
			// Apply settings to default print config
			defaultPrintConfig = PrintConfig(
				leftMargin = 0,
				rightMargin = 0,
				lineSpacing = lineSpacing,
				widthMul = 0,
				heightMul = 0,
				pageWidthDots = paperWidth,
				linesPerPage = 0
			)
			
			Toast.makeText(this, "Settings applied successfully!", Toast.LENGTH_SHORT).show()
			dialog.dismiss()
		} catch (e: Exception) {
			Toast.makeText(this, "Error applying settings: ${e.message}", Toast.LENGTH_SHORT).show()
		}
	}

	private fun testQuickPrint(view: android.view.View) {
		try {
			val paperWidthSpinner = view.findViewWithTag<android.widget.Spinner>("paperWidth")
			val lineSpacingSeekBar = view.findViewWithTag<android.widget.SeekBar>("lineSpacing")
			
			val paperWidths = intArrayOf(384, 576, 832)
			val paperWidth = paperWidths[paperWidthSpinner.selectedItemPosition]
			val lineSpacing = lineSpacingSeekBar.progress
			
			val testText = """
				================================
				QUICK SETTINGS TEST PRINT
				================================
				
				Paper Width: ${paperWidth} dots
				Line Spacing: ${lineSpacing}
				
				This is a test of the quick settings
				configuration. If you can read this
				clearly, the settings are working
				correctly.
				
				Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}
				
				================================
				END OF TEST
				================================
			""".trimIndent()
			
			// Apply settings and print
			defaultPrintConfig = PrintConfig(
				leftMargin = 0,
				rightMargin = 0,
				lineSpacing = lineSpacing,
				widthMul = 0,
				heightMul = 0,
				pageWidthDots = paperWidth,
				linesPerPage = 0
			)
			
			// Try to print using the existing print methods
			val result = try {
				// Check for connected printers in order of preference
				when {
					// Try USB printer first (most common for POS)
					isUsbConnected && usbPrinter != null -> {
						usbPrinter!!.printText(
							testText,
							leftMarginDots = defaultPrintConfig.leftMargin,
							rightMarginDots = defaultPrintConfig.rightMargin,
							lineSpacing = defaultPrintConfig.lineSpacing,
							widthMultiplier = defaultPrintConfig.widthMul,
							heightMultiplier = defaultPrintConfig.heightMul,
							pageWidthDots = defaultPrintConfig.pageWidthDots,
							linesPerPage = defaultPrintConfig.linesPerPage.takeIf { it > 0 }
						)
						"{\"ok\":true}"
					}
					// Try Bluetooth printer
					isBtConnected && escPosPrinter != null -> {
						escPosPrinter!!.printText(
							testText,
							leftMarginDots = defaultPrintConfig.leftMargin,
							rightMarginDots = defaultPrintConfig.rightMargin,
							lineSpacing = defaultPrintConfig.lineSpacing,
							widthMultiplier = defaultPrintConfig.widthMul,
							heightMultiplier = defaultPrintConfig.heightMul,
							pageWidthDots = defaultPrintConfig.pageWidthDots,
							linesPerPage = defaultPrintConfig.linesPerPage.takeIf { it > 0 }
						)
						"{\"ok\":true}"
					}
					// Try LAN printer
					isLanConnected && lanPrinter != null -> {
						lanPrinter!!.printText(
							testText,
							leftMarginDots = defaultPrintConfig.leftMargin,
							rightMarginDots = defaultPrintConfig.rightMargin,
							lineSpacing = defaultPrintConfig.lineSpacing,
							widthMultiplier = defaultPrintConfig.widthMul,
							heightMultiplier = defaultPrintConfig.heightMul,
							pageWidthDots = defaultPrintConfig.pageWidthDots,
							linesPerPage = defaultPrintConfig.linesPerPage.takeIf { it > 0 }
						)
						"{\"ok\":true}"
					}
					// Try to auto-detect and connect USB printer
					else -> {
						try {
							// Try to find and connect USB printer automatically
							val usbManager = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
							val deviceList = usbManager.deviceList
							val usbDevice = deviceList.values.firstOrNull { device ->
								// Look for common printer vendor IDs
								val vid = device.vendorId
								vid == 0x04E8 || vid == 0x04B8 || vid == 0x04F9 || vid == 0x0FE6 || vid == 0x154F
							}
							
							if (usbDevice != null) {
								usbPrinter?.close()
								usbPrinter = UsbEscPosPrinter(this)
								usbPrinter!!.connect(usbDevice)
								isUsbConnected = true
								
								usbPrinter!!.printText(
									testText,
									leftMarginDots = defaultPrintConfig.leftMargin,
									rightMarginDots = defaultPrintConfig.rightMargin,
									lineSpacing = defaultPrintConfig.lineSpacing,
									widthMultiplier = defaultPrintConfig.widthMul,
									heightMultiplier = defaultPrintConfig.heightMul,
									pageWidthDots = defaultPrintConfig.pageWidthDots,
									linesPerPage = defaultPrintConfig.linesPerPage.takeIf { it > 0 }
								)
								"{\"ok\":true}"
							} else {
								"{\"ok\":false,\"msg\":\"No printer connected. Please connect USB, Bluetooth, or LAN printer first.\"}"
							}
						} catch (e: Exception) {
							"{\"ok\":false,\"msg\":\"No printer connected. Please connect USB, Bluetooth, or LAN printer first. Error: ${e.message}\"}"
						}
					}
				}
			} catch (e: Exception) {
				"{\"ok\":false,\"msg\":\"Print error: ${e.message}\"}"
			}
			
			if (result.contains("\"ok\":true")) {
				Toast.makeText(this, "Test print sent successfully!", Toast.LENGTH_SHORT).show()
			} else {
				val errorMsg = if (result.contains("\"msg\":")) {
					val start = result.indexOf("\"msg\":\"") + 7
					val end = result.indexOf("\"", start)
					if (end > start) result.substring(start, end) else "Unknown error"
				} else {
					"Check printer connection and try again"
				}
				Toast.makeText(this, "Print failed: $errorMsg", Toast.LENGTH_LONG).show()
			}
		} catch (e: Exception) {
			Toast.makeText(this, "Test print error: ${e.message}", Toast.LENGTH_SHORT).show()
		}
	}

	private fun openPrinterManagement() {
		webView.loadUrl("file:///android_asset/printer_management.html")
	}

	private fun openSettingsDemo() {
		webView.loadUrl("file:///android_asset/settings_demo.html")
	}

	private fun openPrinterSettingsUI() {
		webView.loadUrl("file:///android_asset/printer_settings_ui.html")
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
					// Block accidental logout redirects that can occur around print flows
					if (path.contains("/auth/logout") || path.endsWith("/logout")) {
						Toast.makeText(this@MainActivity, "Blocked logout redirect", Toast.LENGTH_SHORT).show()
						return true
					}
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
                            function __elintposSmartPrint(txt, prefer){
								try{
									if (typeof ElintPOSNative !== 'undefined' && ElintPOSNative.vendorAvailable && ElintPOSNative.vendorAvailable()){
										try{ var r = ElintPOSNative.vendorPrintText(String(txt||'')); if(r && String(r).indexOf('"ok":true')>=0) return true; }catch(_){ }
									}
									if (typeof ElintPOSNative !== 'undefined' && ElintPOSNative.printFromWeb){
										ElintPOSNative.printFromWeb(String(txt||document.body.innerText||''), String(prefer||'auto'));
										return true;
									}
								}catch(_){ }
                                // Do not fallback to system print automatically
								return false;
							}
							window.ElintPOS = {
								print: function(txt, prefer){ return __elintposSmartPrint(String(txt||document.body.innerText||''), String(prefer||'auto')); },
                                printSelector: function(selector){
									try{
										var el = document.querySelector(selector);
                                        if(!el){ try{ if(ElintPOSNative && ElintPOSNative.showToast){ ElintPOSNative.showToast('No printable content'); } }catch(_){ } return; }
										var original = document.body.innerHTML;
										document.body.setAttribute('data-elintpos','printing');
										document.body.innerHTML = el.outerHTML;
                                        try{ if(!__elintposSmartPrint(String(el.innerText||''), 'auto')){ try{ if(ElintPOSNative && ElintPOSNative.showToast){ ElintPOSNative.showToast('No printer connected'); } }catch(_){ } } }catch(e){ try{ if(ElintPOSNative && ElintPOSNative.showToast){ ElintPOSNative.showToast('Print failed'); } }catch(_){ } }
										setTimeout(function(){ try{ document.body.innerHTML = original; }catch(_){} }, 1500);
                                    }catch(e){ try{ if(ElintPOSNative && ElintPOSNative.showToast){ ElintPOSNative.showToast('Print failed'); } }catch(_){ } }
								},
								status: function(){ return ElintPOSNative.getPrinterStatus(); }
							};
							var origPrint = window.print;
                            window.print = function(){ try{ if(!__elintposSmartPrint(String(document.body.innerText||''), 'auto')){ try{ if(ElintPOSNative && ElintPOSNative.showToast){ ElintPOSNative.showToast('No printer connected'); } }catch(_){ } } }catch(e){ if(origPrint) origPrint(); } };
							// Force target=_blank links and window.open to stay in same WebView
							try{ window.open = function(u){ try{ location.href = u; }catch(e){} return null; }; }catch(_){ }
							try{
								var bl = document.querySelectorAll('a[target="_blank"]');
								for(var i=0;i<bl.length;i++){ bl[i].setAttribute('target','_self'); }
							}catch(_){ }
							// Bind common POS action buttons to Android system print
							var selectors = ['.cmdprint', '.cmdprint1', '.splitcheck', '.btn-print', '#btnPrint'];
							selectors.forEach(function(sel){
								var els = document.querySelectorAll(sel);
								for(var i=0;i<els.length;i++){
									els[i].addEventListener('click', function(ev){
										try{ ElintPOS.printSelector('#paymentModal .modal-content'); }catch(e){ try{ ElintPOSNative.systemPrint('POS Print'); }catch(_){} }
									}, true);
								}
							});

							// Also catch generic buttons with visible text like "Print", "Submit & Print"
							function attachByText(root){
								try{
									var candidates = root.querySelectorAll('button, input[type="button"], input[type="submit"], a.btn, .btn');
									for(var i=0;i<candidates.length;i++){
										var el = candidates[i];
										if(el.__elintposBound) continue;
										var txt = (el.innerText||el.value||'').trim().toLowerCase();
										if(!txt) continue;
										if(txt === 'print' || txt.indexOf('submit & print') >= 0 || txt.indexOf('submit and print') >= 0 || txt.indexOf('print bill') >= 0 || txt.indexOf('print receipt') >= 0){
											el.__elintposBound = true;
											el.addEventListener('click', function(){ try{ ElintPOSNative.systemPrint('POS Print'); }catch(_){ try{ window.print(); }catch(__){} } }, true);
										}
									}
								}catch(_){ }
							}
							attachByText(document);
							// Observe future DOM changes (pages that inject buttons after submit)
							try{
								var mo = new MutationObserver(function(muts){ for(var j=0;j<muts.length;j++){ if(muts[j].addedNodes){ for(var k=0;k<muts[j].addedNodes.length;k++){ var n=muts[j].addedNodes[k]; if(n && n.querySelectorAll) attachByText(n); } } } });
								mo.observe(document.documentElement, {childList:true, subtree:true});
							}catch(_){ }
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

	private fun showPrinterChooserAndPrint(text: String) {
		try {
			val items = mutableListOf<String>()
			val actions = mutableListOf<() -> Unit>()

			// Bluetooth options
			BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.forEach { d ->
				items.add("BT: ${'$'}{d.name ?: d.address}")
				actions.add {
					try {
						escPosPrinter?.close()
						escPosPrinter = BluetoothEscPosPrinter(this)
						escPosPrinter!!.connect(d)
						isBtConnected = true
						escPosPrinter!!.printText(text)
					} catch (e: Exception) {
						isBtConnected = false
						Toast.makeText(this, "BT print failed: ${'$'}{e.message}", Toast.LENGTH_SHORT).show()
					}
				}
			}

			// USB options
			val usbMgr = getSystemService(USB_SERVICE) as UsbManager
			val usbList = UsbEscPosPrinter(this).listPrinters()
			usbList.forEach { dev ->
				items.add("USB: ${'$'}{dev.deviceName} (v=${'$'}{dev.vendorId}, p=${'$'}{dev.productId})")
				actions.add {
					try {
						if (!usbMgr.hasPermission(dev)) {
							pendingUsbDeviceName = dev.deviceName
							pendingUsbAfterConnect = {
								try { usbPrinter?.printText(text) } catch (_: Exception) {}
							}
							val pi = android.app.PendingIntent.getBroadcast(
								this,
								0,
								Intent(ACTION_USB_PERMISSION),
								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0
							)
							usbMgr.requestPermission(dev, pi)
							Toast.makeText(this, "Requesting USB permission...", Toast.LENGTH_SHORT).show()
							return@add
						}
						usbPrinter?.close()
						usbPrinter = UsbEscPosPrinter(this)
						usbPrinter!!.connect(dev)
						isUsbConnected = true
						usbPrinter!!.printText(text)
					} catch (e: Exception) {
						isUsbConnected = false
						Toast.makeText(this, "USB print failed: ${'$'}{e.message}", Toast.LENGTH_SHORT).show()
					}
				}
			}

			// Vendor option (if SDK present)
			if (vendorPrinter.isAvailable()) {
				items.add("Vendor SDK: Default USB")
				actions.add {
					try {
						val ok = vendorPrinter.printText(text)
						if (!ok) Toast.makeText(this, "Vendor print failed", Toast.LENGTH_SHORT).show()
					} catch (e: Exception) {
						Toast.makeText(this, "Vendor print error: ${'$'}{e.message}", Toast.LENGTH_SHORT).show()
					}
				}
			}

			// Epson option (if SDK present)
			if (epsonPrinter.isAvailable()) {
				items.add("Epson SDK")
				actions.add {
					try {
						val ok = epsonPrinter.printText(text)
						if (!ok) Toast.makeText(this, "Epson print failed", Toast.LENGTH_SHORT).show()
					} catch (e: Exception) {
						Toast.makeText(this, "Epson print error: ${'$'}{e.message}", Toast.LENGTH_SHORT).show()
					}
				}
			}

			// XPrinter option (if SDK present)
			if (xPrinter.isAvailable()) {
				items.add("XPrinter SDK")
				actions.add {
					try {
						val ok = xPrinter.printText(text)
						if (!ok) Toast.makeText(this, "XPrinter print failed", Toast.LENGTH_SHORT).show()
					} catch (e: Exception) {
						Toast.makeText(this, "XPrinter print error: ${'$'}{e.message}", Toast.LENGTH_SHORT).show()
					}
				}
			}

			if (items.isEmpty()) {
				Toast.makeText(this, "No printers found", Toast.LENGTH_SHORT).show()
				return
			}

			val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
				.setTitle("Select printer")
				.setItems(items.toTypedArray()) { _, which ->
					try { actions[which].invoke() } catch (_: Exception) {}
				}
				.setNegativeButton("Cancel", null)
				.create()
			dlg.show()
		} catch (e: Exception) {
			Toast.makeText(this, "Printer dialog error: ${'$'}{e.message}", Toast.LENGTH_SHORT).show()
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
		printerTester.cleanup()
		super.onDestroy()
	}
}
