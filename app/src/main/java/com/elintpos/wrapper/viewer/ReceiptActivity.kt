package com.elintpos.wrapper.viewer

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.elintpos.wrapper.R

class ReceiptActivity : AppCompatActivity() {

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_receipt)

		val webView = findViewById<WebView>(R.id.receiptWebView)
		CookieManager.getInstance().setAcceptCookie(true)
		CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

		with(webView.settings) {
			javaScriptEnabled = true
			domStorageEnabled = true
			databaseEnabled = true
			useWideViewPort = true
			loadWithOverviewMode = true
			builtInZoomControls = true
			displayZoomControls = false
			mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
		}

		webView.webViewClient = object : WebViewClient() {}

		val url = intent.getStringExtra(EXTRA_URL)
		if (url != null && url.isNotBlank()) {
			webView.loadUrl(url)
		} else {
			// Fallback: show orderId-based page if provided
			intent.getStringExtra(EXTRA_ORDER_ID)?.let { id ->
				webView.loadUrl("about:blank")
			}
		}
	}

	companion object {
		const val EXTRA_ORDER_ID = "ORDER_ID"
		const val EXTRA_URL = "URL"
	}
}


