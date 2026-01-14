package com.elintpos.wrapper

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.elintpos.wrapper.escpos.BluetoothEscPosPrinter
import com.elintpos.wrapper.escpos.LanEscPosPrinter
import com.elintpos.wrapper.escpos.ReceiptFormatter
import com.elintpos.wrapper.escpos.UsbEscPosPrinter
import com.elintpos.wrapper.export.CsvExporter
import com.elintpos.wrapper.pdf.PdfDownloader
import com.elintpos.wrapper.printer.PrinterConfigManager
import com.elintpos.wrapper.printer.PrinterTester
import com.elintpos.wrapper.printer.vendor.AutoReplyPrint
import com.elintpos.wrapper.printer.vendor.EpsonPrinter
import com.elintpos.wrapper.printer.vendor.VendorPrinter
import com.elintpos.wrapper.printer.vendor.XPrinter
import com.elintpos.wrapper.sdk.SdkDownloader
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

	companion object {
		private const val USER_AGENT_SUFFIX = " DesktopAndroidWebView/1366x768"
		private const val TAG = "ElintPOS"
		internal const val ACTION_USB_PERMISSION = "com.elintpos.wrapper.USB_PERMISSION"
	}
	
	private fun getBaseUrl(): String {
		return preferencesManager.getBaseUrl()
	}
	
	private fun getBaseDomain(): String {
		return preferencesManager.getBaseDomain()
	}

    private fun buildAbsoluteUrl(input: String): String {
        val url = input.trim()
        if (url.isEmpty()) return url
        
        val baseUrl = getBaseUrl()

        if (url.contains("://")) return url

        if (url.startsWith("//")) {
            val scheme = try {
                Uri.parse(webView.url ?: baseUrl).scheme ?: "http"
            } catch (_: Exception) {
                "http"
            }
            return "$scheme:$url"
        }

        val base = try {
            val current = webView.url ?: baseUrl
            val baseUri = URI(current)
            if (baseUri.scheme == null || baseUri.host == null) URI(baseUrl) else baseUri
        } catch (_: Exception) {
            URI(baseUrl)
        }

        return try {
            base.resolve(url).toString()
        } catch (_: Exception) {
            (baseUrl.trimEnd('/') + "/" + url.trimStart('/'))
        }
    }

	private lateinit var webView: WebView

	private var filePathCallback: ValueCallback<Array<Uri>>? = null
	private var cameraImageUri: Uri? = null

	private val retried403Urls = mutableSetOf<String>()

	private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
	private lateinit var genericPermissionsLauncher: ActivityResultLauncher<Array<String>>
	private var pendingPermissionsCallback: ((Map<String, Boolean>) -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
			Toast.makeText(
				this,
				if (granted) "Notifications permission granted" else "Notifications permission denied",
				Toast.LENGTH_SHORT
			).show()
		}

	internal var escPosPrinter: BluetoothEscPosPrinter? = null
	internal var usbPrinter: UsbEscPosPrinter? = null
	internal var lanPrinter: LanEscPosPrinter? = null
	internal var isBtConnected: Boolean = false
	internal var isUsbConnected: Boolean = false
	internal var isLanConnected: Boolean = false
	internal var receiptFormatter: ReceiptFormatter = ReceiptFormatter()
	internal var pdfDownloader: PdfDownloader = PdfDownloader(this)
	internal var csvExporter: CsvExporter = CsvExporter(this)
    private val vendorPrinter: VendorPrinter by lazy { VendorPrinter(this) }
    private val epsonPrinter: EpsonPrinter by lazy { EpsonPrinter(this) }
    private val xPrinter: XPrinter by lazy { XPrinter(this) }

	internal var pendingUsbDeviceName: String? = null
	internal var pendingUsbAfterConnect: (() -> Unit)? = null

	private val preferencesManager: com.elintpos.wrapper.utils.PreferencesManager by lazy { 
		com.elintpos.wrapper.utils.PreferencesManager(this) 
	}
	private val printerConfigManager: PrinterConfigManager by lazy { PrinterConfigManager(this) }
	private val printerTester: PrinterTester by lazy { PrinterTester(this) }
	private val sdkDownloader: SdkDownloader by lazy { SdkDownloader(this) }
	private val unifiedPrinterHandler: UnifiedPrinterHandler by lazy { UnifiedPrinterHandler(this) }
	private val autoReplyPrint: AutoReplyPrint by lazy { AutoReplyPrint(this) }
	
	private lateinit var jsBridge: com.elintpos.wrapper.bridge.JavaScriptBridge

	private fun isKioskEnabled(): Boolean = preferencesManager.kioskEnabled

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		
		try {
			setupFullscreen()

			val mainLayout = android.widget.RelativeLayout(this)
			webView = WebView(this)
			webView.layoutParams = android.widget.RelativeLayout.LayoutParams(
				android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
				android.widget.RelativeLayout.LayoutParams.MATCH_PARENT
			)
			mainLayout.addView(webView)
			
            // Hide native floating settings button in top-right corner.
            // Printer settings can still be opened from the web UI / other flows.
            // If you ever want it back, call createSettingsButton() and add it here.
            // val settingsButton = createSettingsButton()
			// mainLayout.addView(settingsButton)
			
			setContentView(mainLayout)

			genericPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
				try {
					pendingPermissionsCallback?.invoke(result)
				} finally {
					pendingPermissionsCallback = null
				}
			}

			setupFileChooserLauncher()
			configureWebView(webView)
			setupDownloadHandling(webView)

			jsBridge = com.elintpos.wrapper.bridge.JavaScriptBridge(
				activity = this,
				webViewProvider = { 
					if (::webView.isInitialized) webView else throw IllegalStateException("WebView not initialized")
				},
				unifiedPrinterHandler = unifiedPrinterHandler,
				printerConfigManager = printerConfigManager,
				preferencesManager = preferencesManager,
				printerTester = printerTester,
				sdkDownloader = sdkDownloader,
				autoReplyPrint = autoReplyPrint,
				epsonPrinter = epsonPrinter,
				xPrinter = xPrinter,
				vendorPrinter = vendorPrinter
			)

			webView.addJavascriptInterface(jsBridge, "ElintPOSNative")

			if (savedInstanceState != null) {
				try {
					webView.restoreState(savedInstanceState)
				} catch (e: Exception) {
					Log.e(TAG, "Error restoring WebView state", e)
					// Fallback to loading base URL
					loadInitialUrl()
				}
			} else {
				loadInitialUrl()
			}

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				requestNotificationsPermissionIfNeeded()
			}
			
			// Check and enable kiosk mode if preference is set
			checkAndEnableKioskMode()
		} catch (e: Exception) {
			Log.e(TAG, "Critical error in onCreate", e)
			Toast.makeText(this, "App initialization error. Please restart the app.", Toast.LENGTH_LONG).show()
			// Don't finish() here - let the crash handler restart the app
			throw e
		}
	}
	
	/**
	 * Check kiosk mode preference and enable lock task mode if enabled
	 * 
	 * This ensures that when the app starts, if kiosk mode was enabled
	 * in PrinterSetupActivity, it remains enabled in MainActivity.
	 */
	private fun checkAndEnableKioskMode() {
		if (isKioskEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			try {
				startLockTask()
				Log.d(TAG, "Kiosk mode enabled on startup - lock task started")
			} catch (e: Exception) {
				Log.e(TAG, "Failed to enable kiosk mode on startup", e)
				// Don't show toast here as it might be too early in the lifecycle
			}
		}
	}
	
	private fun loadInitialUrl() {
		try {
			val url = getBaseUrl()
			Log.d(TAG, "Loading initial URL: $url")
			if (::webView.isInitialized) {
				webView.loadUrl(url)
			} else {
				Log.e(TAG, "WebView not initialized when trying to load URL")
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error loading initial URL", e)
			Toast.makeText(this, "Error loading page. Please check your internet connection.", Toast.LENGTH_LONG).show()
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
		settingsButton.visibility = android.view.View.VISIBLE // Make visible
		val layoutParams = android.widget.RelativeLayout.LayoutParams(
			android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
			android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
		)
		layoutParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
		layoutParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
		layoutParams.setMargins(0, 50, 20, 0)
		settingsButton.layoutParams = layoutParams
		
		settingsButton.setImageResource(android.R.drawable.ic_menu_preferences)
		settingsButton.background = android.graphics.drawable.GradientDrawable().apply {
			shape = android.graphics.drawable.GradientDrawable.OVAL
			setColor(android.graphics.Color.parseColor("#FF6200EA")) // Purple color
			setStroke(4, android.graphics.Color.parseColor("#FFFFFFFF")) // White border
		}
		settingsButton.setPadding(24, 24, 24, 24)
		settingsButton.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
		
		settingsButton.setOnClickListener {
			showPrinterSettingsPopup()
		}
		
		return settingsButton
	}

    internal fun showPrinterSettingsPopup() {
        val profile = printerConfigManager.getLastUsedProfile()
            ?: printerConfigManager.getAllProfiles().firstOrNull { it.enabled }

        if (profile == null) {
            openPrinterManagement()
            return
        }

        val isConnected = isBtConnected || isUsbConnected || isLanConnected
        val paperSize = when (profile.paperWidth) {
            PrinterConfigManager.PAPER_58MM -> "58mm"
            PrinterConfigManager.PAPER_80MM -> "80mm"
            PrinterConfigManager.PAPER_90MM -> "90mm"
            PrinterConfigManager.PAPER_112MM -> "112mm"
            else -> "${profile.paperWidth} dots"
        }

			val message = buildString {
            appendLine("🖨️ ${profile.name}")
            appendLine()
            appendLine("Status: ${if (isConnected) "✅ Connected" else "❌ Not Connected"}")
            appendLine("Paper Size: $paperSize")
			}
			
			val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Printer Settings")
				.setMessage(message)
            .setPositiveButton("Test Print") { _, _ ->
                testQuickPrintFromSettings(profile)
            }
            .setNeutralButton("Advanced Settings") { _, _ ->
                openPrinterManagement()
            }
            .setNegativeButton("Close", null)
			.create()
		
		dialog.show()
	}

    private fun testQuickPrintFromSettings(profile: PrinterConfigManager.PrinterConfig) {
			val testText = """
				================================
			PRINTER TEST
				================================
				
			Printer: ${profile.name}
			Paper: ${profile.paperWidth} dots
			
			If you can read this,
			your printer is working correctly!
			
			Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
				
				================================
				END OF TEST
				================================
			""".trimIndent()
			
        CoroutineScope(Dispatchers.IO).launch {
            val result = unifiedPrinterHandler.print(testText, profile.type)
            runOnUiThread {
                if (result.success) {
                    Toast.makeText(this@MainActivity, "Test print sent successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    showPrintFailedDialog(result.message, testText, profile.type)
                }
            }
        }
    }

    private fun showPrintFailedDialog(errorMessage: String, textToPrint: String, printerType: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("❌ Print Failed")
            .setMessage("Print failed: $errorMessage\n\nPlease check:\n• Printer is powered on\n• Printer is connected\n• Paper is loaded")
            .setPositiveButton("Retry") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    val result = unifiedPrinterHandler.print(textToPrint, printerType)
                    runOnUiThread {
                        if (result.success) {
                            Toast.makeText(this@MainActivity, "Print successful!", Toast.LENGTH_SHORT).show()
                        } else {
                            showPrintFailedDialog(result.message, textToPrint, printerType)
                        }
                    }
                }
            }
            .setNeutralButton("Printer Settings") { _, _ ->
                openPrinterManagement()
            }
            .setNegativeButton("Cancel", null)
            .show()
	}

	internal fun openPrinterManagement() {
		try {
			if (::webView.isInitialized) {
				webView.loadUrl("file:///android_asset/printer_management.html")
			} else {
				Log.e(TAG, "WebView not initialized when trying to open printer management")
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error opening printer management", e)
			Toast.makeText(this, "Error opening printer settings", Toast.LENGTH_SHORT).show()
		}
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun configureWebView(view: WebView) {
		try {
			CookieManager.getInstance().setAcceptCookie(true)
			CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

			with(view.settings) {
				javaScriptEnabled = true
				domStorageEnabled = true
				databaseEnabled = true
				setSupportMultipleWindows(true)
				javaScriptCanOpenWindowsAutomatically = true
				mediaPlaybackRequiresUserGesture = false
				// Layout and rendering tweaks to better match Chrome's responsive behavior
				useWideViewPort = false
				loadWithOverviewMode = false
				layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
				textZoom = 100
				// Disable zoom controls to avoid unintended scale changes that distort layout
				builtInZoomControls = false
				displayZoomControls = false
				setSupportZoom(false)
				mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
				
				// Enable hardware acceleration for better performance
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
					setRenderPriority(WebSettings.RenderPriority.HIGH)
				}

				userAgentString = userAgentString + USER_AGENT_SUFFIX
			}

			view.isFocusable = true
			view.isFocusableInTouchMode = true
			view.overScrollMode = WebView.OVER_SCROLL_NEVER
		} catch (e: Exception) {
			Log.e(TAG, "Error configuring WebView", e)
			throw e
		}

		view.webViewClient = object : WebViewClient() {
			override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
				super.onPageStarted(view, url, favicon)
				Log.d(TAG, "Page started loading: $url")
			}
			
			override fun onPageFinished(view: WebView?, url: String?) {
				super.onPageFinished(view, url)
                Log.d(TAG, "Page finished loading: $url")
				
				// Inject JavaScript to override window.print() multiple times with delays
				// This ensures it's in place before $(window).load() fires
				val printOverrideScript = """
					(function() {
						// Save original window.print if it exists
						var originalPrint = window.print;
						
						// Override window.print() to call native Android print dialog
						window.print = function() {
							console.log('window.print() called - intercepting for Android native print dialog');
							
							// Check if ElintPOSNative bridge is available
							if (typeof ElintPOSNative !== 'undefined' && typeof ElintPOSNative.showNativePrintDialog === 'function') {
								try {
									var result = ElintPOSNative.showNativePrintDialog();
									var response = JSON.parse(result || '{}');
									if (response.ok) {
										console.log('Native print dialog opened successfully');
										return; // Success - native dialog opened
									} else {
										console.warn('Native print dialog failed:', response.msg);
									}
								} catch (e) {
									console.error('Error calling native print dialog:', e);
								}
							}
							
							// Fallback to original print if native bridge not available or failed
							if (originalPrint && typeof originalPrint === 'function') {
								originalPrint.call(window);
							}
						};
						
						console.log('window.print() override installed');
					})();
				""".trimIndent()
				
				// Inject immediately
				view?.evaluateJavascript(printOverrideScript, null)
				
				// Inject again after a short delay to catch late-loading scripts
				view?.postDelayed({
					view?.evaluateJavascript(printOverrideScript, null)
				}, 50)
				
				// Inject again after longer delay to catch $(window).load() calls
				view?.postDelayed({
					view?.evaluateJavascript(printOverrideScript, null)
				}, 500)
				
				// Also inject when DOM is ready (if document.readyState is available)
				view?.evaluateJavascript("""
					(function() {
						function installPrintOverride() {
							var originalPrint = window.print;
							window.print = function() {
								console.log('window.print() called - intercepting for Android native print dialog');
								if (typeof ElintPOSNative !== 'undefined' && typeof ElintPOSNative.showNativePrintDialog === 'function') {
									try {
										var result = ElintPOSNative.showNativePrintDialog();
										var response = JSON.parse(result || '{}');
										if (response.ok) {
											console.log('Native print dialog opened successfully');
											return;
										}
									} catch (e) {
										console.error('Error calling native print dialog:', e);
									}
								}
								if (originalPrint && typeof originalPrint === 'function') {
									originalPrint.call(window);
								}
							};
							console.log('window.print() override installed (DOM ready)');
						}
						
						if (document.readyState === 'loading') {
							document.addEventListener('DOMContentLoaded', installPrintOverride);
						} else {
							installPrintOverride();
						}
						
						// Also ensure it's installed when window.load fires
						if (typeof jQuery !== 'undefined') {
							jQuery(window).on('load', function() {
								installPrintOverride();
							});
						} else {
							window.addEventListener('load', installPrintOverride);
						}
					})();
				""".trimIndent(), null)
			}
			
			@Suppress("DEPRECATION")
			override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
				Log.e(TAG, "WebView error: Code=$errorCode, Description=$description, URL=$failingUrl")
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
					// For older Android versions
					runOnUiThread {
						Toast.makeText(
							this@MainActivity,
							"Error loading page: ${description ?: "Unknown error"}",
							Toast.LENGTH_LONG
						).show()
					}
				}
			}
			
			override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					val url = request?.url?.toString() ?: "unknown"
					val errorCode = error?.errorCode ?: 0
					val description = error?.description?.toString() ?: "Unknown error"
					Log.e(TAG, "WebView error: Code=$errorCode, Description=$description, URL=$url")
					
					// Check if it's the main frame (page load) error
					if (request?.isForMainFrame == true) {
						runOnUiThread {
							Toast.makeText(
								this@MainActivity,
								"Error loading page: $description",
								Toast.LENGTH_LONG
							).show()
						}
					}
				}
			}

			override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
				if (request == null) return false
				val uri = request.url

				try {
					val path = uri.encodedPath ?: ""
					if (path.contains("/auth/logout") || path.endsWith("/logout")) {
                        Log.d("ElintPOS", "Allowing logout redirect: $uri")
						return false
					}
					if (path.contains("/pos/view/") || path.contains("/sales/view/")) {
                        Log.d("ElintPOS_WebView", "Loading pos/view page: $uri")
						view?.loadUrl(uri.toString())
						return true
					}
                } catch (_: Exception) {
                }

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

				val host = uri.host ?: return false
				return if (host.endsWith(getBaseDomain())) {
					val path = uri.encodedPath ?: ""
					if (path.contains("/reports") || path.contains("/report") || path.contains("profit") || path.contains("loss")) {
						val headers = mutableMapOf<String, String>()
						headers["X-Requested-With"] = "XMLHttpRequest"
                        headers["Referer"] = try {
                            webView.url ?: getBaseUrl()
                        } catch (_: Exception) {
                            getBaseUrl()
                        }
						view?.loadUrl(uri.toString(), headers)
						return true
					}
					false
				} else {
					openExternalIntent(Intent(Intent.ACTION_VIEW, uri))
					true
				}
			}


			override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                try {
                    val url = error?.url ?: "unknown"
                    val primaryError = error?.primaryError ?: 0
                    val baseDomain = getBaseDomain()
                    Log.w(TAG, "SSL Error for $url: Primary error code $primaryError, Base domain: $baseDomain")

                    val host = try {
                        Uri.parse(url).host
                    } catch (_: Exception) {
                        null
                    }

                    Log.d(TAG, "SSL Error - Host: $host, Base domain: $baseDomain")
                    
                    // Allow SSL errors for the base domain and its subdomains
                    if (host != null && (host == baseDomain || host.endsWith(".$baseDomain"))) {
                        Log.w(TAG, "Allowing SSL error for trusted domain: $host")
                        handler?.proceed()
                    } else {
                        Log.e(TAG, "SSL error for untrusted domain: $host - connection cancelled")
                        handler?.cancel()
                        this@MainActivity.runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "SSL certificate error. Connection cancelled for security.\nDomain: $host",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling SSL error", e)
                    handler?.cancel()
                }
			}

			override fun onReceivedHttpError(
				view: WebView?,
				request: WebResourceRequest?,
				errorResponse: android.webkit.WebResourceResponse?
			) {
				try {
					val status = errorResponse?.statusCode ?: return
					if (request?.isForMainFrame == true && status == 403) {
						val badUrl = request.url?.toString() ?: return
						if (!retried403Urls.contains(badUrl)) {
							retried403Urls.add(badUrl)
							val headers = mutableMapOf<String, String>()
							headers["X-Requested-With"] = "XMLHttpRequest"
                            headers["Referer"] = try {
                                webView.url ?: getBaseUrl()
                            } catch (_: Exception) {
                                getBaseUrl()
                            }
							view?.post { view.loadUrl(badUrl, headers) }
							return
						}
						view?.post {
							if (webView.canGoBack()) webView.goBack() else webView.loadUrl(getBaseUrl())
						}
					}
                } catch (_: Exception) {
                }
			}
		}

		view.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                try {
                    val dlg = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Alert")
                        .setMessage(message ?: "")
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok) { d, _ ->
                            try {
                                result?.confirm()
                            } catch (_: Exception) {
                            }
                            d.dismiss()
                        }
                        .create()
                    dlg.show()
                } catch (_: Exception) {
                    try {
                        result?.confirm()
                    } catch (_: Exception) {
                    }
                }
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                try {
                    val dlg = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Confirm")
                        .setMessage(message ?: "")
                        .setPositiveButton(android.R.string.ok) { d, _ ->
                            try {
                                result?.confirm()
                            } catch (_: Exception) {
                            }
                            d.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel) { d, _ ->
                            try {
                                result?.cancel()
                            } catch (_: Exception) {
                            }
                            d.dismiss()
                        }
                        .create()
                    dlg.show()
                } catch (_: Exception) {
                    try {
                        result?.cancel()
                    } catch (_: Exception) {
                    }
                }
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?
            ): Boolean {
                return try {
                    val input = EditText(this@MainActivity)
                    input.setText(defaultValue ?: "")
                    val container = android.widget.LinearLayout(this@MainActivity)
                    container.orientation = android.widget.LinearLayout.VERTICAL
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(32, 16, 32, 0)
                    input.layoutParams = lp
                    container.addView(input)

                    val dlg = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(message ?: "Prompt")
                        .setView(container)
                        .setPositiveButton(android.R.string.ok) { d, _ ->
                            try {
                                result?.confirm(input.text?.toString() ?: "")
                            } catch (_: Exception) {
                            }
                            d.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel) { d, _ ->
                            try {
                                result?.cancel()
                            } catch (_: Exception) {
                            }
                            d.dismiss()
                        }
                        .create()
                    dlg.show()
                    true
                } catch (_: Exception) {
                    try {
                        result?.cancel()
                    } catch (_: Exception) {
                    }
                    true
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
				val transport = resultMsg?.obj as? WebView.WebViewTransport
				transport?.webView = this@MainActivity.webView
				resultMsg?.sendToTarget()
				return true
			}

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
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

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                Log.d(TAG, "Console: ${message?.message()} @ ${message?.sourceId()}:${message?.lineNumber()}")
				return super.onConsoleMessage(message)
			}
		}
	}

	private fun setupDownloadHandling(view: WebView) {
        view.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
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
					cameraImageUri?.let { results = arrayOf(it) }
				} else {
					data.data?.let { results = arrayOf(it) }
				}
			}

			callback.onReceiveValue(results ?: emptyArray())
			filePathCallback = null
			cameraImageUri = null
		}
	}

	internal fun launchFilePicker(params: WebChromeClient.FileChooserParams?) {
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
        return File.createTempFile("IMG_${timeStamp}", ".jpg", storageDir)
	}

	internal fun requestNotificationsPermissionIfNeeded() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
		}
	}

	override fun onResume() {
		super.onResume()
		try {
			if (::webView.isInitialized) {
				webView.onResume()
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error in onResume", e)
		}
	}

	override fun onPause() {
		super.onPause()
		try {
			if (::webView.isInitialized) {
				webView.onPause()
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error in onPause", e)
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		try {
			if (::webView.isInitialized) {
				webView.saveState(outState)
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error saving WebView state", e)
		}
	}

	override fun onBackPressed() {
		try {
			if (::webView.isInitialized && webView.canGoBack()) {
				webView.goBack()
			} else {
				if (isKioskEnabled()) {
					return
				}
				super.onBackPressed()
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error in onBackPressed", e)
			if (!isKioskEnabled()) {
				super.onBackPressed()
			}
		}
	}

	override fun onDestroy() {
		try {
			usbPrinter?.close()
			escPosPrinter?.close()
			lanPrinter?.close()
			unifiedPrinterHandler.closeAll()
			printerTester.cleanup()
		} catch (e: Exception) {
			Log.e(TAG, "Error closing printers", e)
		}
		
		try {
			if (::webView.isInitialized) {
				webView.apply {
					onPause()
					clearHistory()
					clearCache(true)
					loadUrl("about:blank")
					removeAllViews()
					destroyDrawingCache()
					destroy()
				}
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error destroying WebView", e)
		}
		
		super.onDestroy()
	}
}