package com.elintpos.wrapper.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.elintpos.wrapper.MainActivity
import com.elintpos.wrapper.bridge.JavaScriptBridge
import com.elintpos.wrapper.config.AppConfig
import com.elintpos.wrapper.BuildConfig
import com.elintpos.wrapper.utils.AppLogger
import com.elintpos.wrapper.utils.InputValidator
import com.elintpos.wrapper.utils.PreferencesManager
import java.net.URI

/**
 * WebViewManager - Handles all WebView setup, configuration, and management
 * 
 * This class centralizes all WebView-related logic, making MainActivity cleaner
 * and more maintainable.
 * 
 * Responsibilities:
 * - WebView creation and configuration
 * - WebViewClient implementation
 * - WebChromeClient implementation
 * - URL loading with validation
 * - Cookie management
 * - Download handling
 * - File chooser handling
 */
class WebViewManager(
    private val activity: MainActivity,
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val jsBridge: JavaScriptBridge
) {
    
    companion object {
        private const val TAG = "WebViewManager"
        private const val JS_INTERFACE_NAME = "ElintPOSNative"
    }
    
    private var webView: WebView? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    
    /**
     * Creates and configures a new WebView instance
     */
    fun createWebView(): WebView {
        val view = WebView(context)
        configureWebView(view)
        setupWebViewClient(view)
        setupWebChromeClient(view)
        setupDownloadHandling(view)
        setupJavaScriptInterface(view)
        
        webView = view
        return view
    }
    
    /**
     * Configures WebView settings
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        val settings = view.settings
        
        // Enable JavaScript
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        
        // Layout and rendering
        // Use a normal layout algorithm and disable wide viewport/overview to better match
        // how Chrome renders responsive sites with a proper viewport meta tag.
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        // Ensure text size is not auto-inflated by WebView
        settings.textZoom = 100
        
        // Zoom controls (disable to avoid unintended scaling that can break layout)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        
        // Mixed content (HTTP/HTTPS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        
        // User agent
        val defaultUserAgent = settings.userAgentString
        settings.userAgentString = "$defaultUserAgent ${AppConfig.WebView.USER_AGENT_SUFFIX}"
        
        // Cache
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // setAppCacheEnabled is deprecated in API 33+, cache mode is sufficient
        
        // File access
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        
        // Media playback
        settings.mediaPlaybackRequiresUserGesture = false
        
        // Additional settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }
    }
    
    /**
     * Sets up WebViewClient for page navigation and error handling
     */
    private fun setupWebViewClient(view: WebView) {
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // Validate URL before loading
                val urlValidation = InputValidator.validateUrl(url)
                if (urlValidation.isError()) {
                    AppLogger.w("Invalid URL blocked: ${urlValidation.getErrorMessage()}", TAG)
                    return true // Block invalid URLs
                }
                
                // Allow same domain URLs
                val baseDomain = preferencesManager.getBaseDomain()
                val urlDomain = try {
                    URI(url).host
                } catch (e: Exception) {
                    null
                }
                
                // If URL is from same domain or trusted, load in WebView
                if (urlDomain == null || urlDomain.contains(baseDomain) || isTrustedDomain(urlDomain)) {
                    return false // Let WebView handle it
                }
                
                // External URLs - open in browser
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    activity.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    AppLogger.e("Error opening external URL", e, TAG)
                    return true
                }
            }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                AppLogger.d("Page started loading: $url", TAG)
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                AppLogger.d("Page finished loading: $url", TAG)
                
                // Inject JavaScript interface if needed
                injectJavaScriptInterface(view)
            }
            
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                AppLogger.e("WebView error: ${error?.description} for ${request?.url}", TAG)
            }
            
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                AppLogger.w("HTTP error: ${errorResponse?.statusCode} for ${request?.url}", TAG)
            }
            
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                // In production, you might want to be more strict
                // For now, allow SSL errors (useful for development)
                AppLogger.w("SSL error: ${error?.toString()}", TAG)
                handler?.proceed()
            }
        }
    }
    
    /**
     * Sets up WebChromeClient for advanced features
     */
    private fun setupWebChromeClient(view: WebView) {
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // Progress can be used to show loading indicator
                if (newProgress == 100) {
                    AppLogger.d("Page loaded completely", TAG)
                }
            }
            
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                AppLogger.d("Console: ${message?.message()} @ ${message?.sourceId()}:${message?.lineNumber()}", TAG)
                return super.onConsoleMessage(message)
            }
            
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                return try {
                    android.app.AlertDialog.Builder(context)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                        .setCancelable(false)
                        .create()
                        .show()
                    true
                } catch (e: Exception) {
                    AppLogger.e("Error showing JS alert", e, TAG)
                    result?.cancel()
                    true
                }
            }
            
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                return try {
                    android.app.AlertDialog.Builder(context)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                        .setCancelable(false)
                        .create()
                        .show()
                    true
                } catch (e: Exception) {
                    AppLogger.e("Error showing JS confirm", e, TAG)
                    result?.cancel()
                    true
                }
            }
            
            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                return try {
                    val input = android.widget.EditText(context).apply {
                        setText(defaultValue)
                    }
                    val container = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(50, 20, 50, 20)
                        addView(input)
                    }
                    
                    android.app.AlertDialog.Builder(context)
                        .setTitle(message ?: "Prompt")
                        .setView(container)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            result?.confirm(input.text?.toString() ?: "")
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                            result?.cancel()
                        }
                        .create()
                        .show()
                    true
                } catch (e: Exception) {
                    AppLogger.e("Error showing JS prompt", e, TAG)
                    result?.cancel()
                    true
                }
            }
            
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // Open new window requests in the same WebView
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = webView
                resultMsg?.sendToTarget()
                return true
            }
            
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
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
                this@WebViewManager.filePathCallback = filePathCallback
                // MainActivity will handle file picker via WebChromeClient
                // This is just a pass-through - actual implementation is in MainActivity
                activity.launchFilePicker(fileChooserParams)
                return true
            }
        }
    }
    
    /**
     * Sets up download handling
     */
    private fun setupDownloadHandling(view: WebView) {
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            try {
                val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)
                    if (!contentDisposition.isNullOrEmpty()) {
                        addRequestHeader("Content-Disposition", contentDisposition)
                    }
                    setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.enqueue(request)
                activity.runOnUiThread {
                    Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e("Error handling download", e, TAG)
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    activity.startActivity(intent)
                } catch (ex: Exception) {
                    activity.runOnUiThread {
                        Toast.makeText(context, "No app to handle download", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    /**
     * Sets up JavaScript interface
     */
    private fun setupJavaScriptInterface(view: WebView) {
        view.addJavascriptInterface(jsBridge, JS_INTERFACE_NAME)
    }
    
    /**
     * Injects JavaScript interface if needed (for page reloads)
     */
    private fun injectJavaScriptInterface(view: WebView?) {
        view?.evaluateJavascript("""
            if (typeof window.ElintPOSNative === 'undefined') {
                console.log('ElintPOSNative interface not found, page may need reload');
            }
        """, null)
    }
    
    /**
     * Loads a URL in the WebView with validation
     */
    fun loadUrl(url: String) {
        val urlValidation = InputValidator.validateUrl(url)
        if (urlValidation.isError()) {
            AppLogger.e("Cannot load invalid URL: ${urlValidation.getErrorMessage()}", TAG)
            activity.runOnUiThread {
                Toast.makeText(context, "Invalid URL: ${urlValidation.getErrorMessage()}", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        val validatedUrl = urlValidation.getValueOrNull() ?: return
        webView?.loadUrl(validatedUrl)
    }
    
    /**
     * Loads the base URL from preferences
     */
    fun loadBaseUrl() {
        val url = preferencesManager.getBaseUrl()
        loadUrl(url)
    }
    
    /**
     * Evaluates JavaScript in the WebView
     */
    fun evaluateJavaScript(script: String, callback: ValueCallback<String>? = null) {
        webView?.evaluateJavascript(script, callback)
    }
    
    /**
     * Gets the current WebView instance
     */
    fun getWebView(): WebView? = webView
    
    /**
     * Gets the current URL
     */
    fun getCurrentUrl(): String? = webView?.url
    
    /**
     * Checks if WebView can go back
     */
    fun canGoBack(): Boolean = webView?.canGoBack() ?: false
    
    /**
     * Navigates back in WebView history
     */
    fun goBack() {
        webView?.goBack()
    }
    
    /**
     * Reloads the current page
     */
    fun reload() {
        webView?.reload()
    }
    
    /**
     * Clears WebView cache
     */
    fun clearCache(includeDiskFiles: Boolean) {
        webView?.clearCache(includeDiskFiles)
    }
    
    /**
     * Clears WebView history
     */
    fun clearHistory() {
        webView?.clearHistory()
    }
    
    /**
     * Saves WebView state to Bundle
     */
    fun saveState(outState: android.os.Bundle) {
        webView?.saveState(outState)
    }
    
    /**
     * Restores WebView state from Bundle
     */
    fun restoreState(inState: android.os.Bundle) {
        webView?.restoreState(inState)
    }
    
    /**
     * Properly destroys the WebView
     */
    fun destroy() {
        webView?.apply {
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
            onPause()
            removeAllViews()
            destroyDrawingCache()
            destroy()
        }
        webView = null
    }
    
    /**
     * Handles file chooser result
     */
    fun handleFileChooserResult(uris: Array<Uri>?) {
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }
    
    /**
     * Cancels file chooser
     */
    fun cancelFileChooser() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
    }
    
    /**
     * Checks if a domain is trusted
     */
    private fun isTrustedDomain(domain: String): Boolean {
        val trustedDomains = listOf(
            "elintpos.in",
            "google.com",
            "github.com"
            // Add more trusted domains as needed
        )
        return trustedDomains.any { domain.contains(it, ignoreCase = true) }
    }
}

