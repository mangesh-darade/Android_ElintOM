package com.elintpos.wrapper

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.activity.ComponentActivity

class LoginActivity : ComponentActivity() {

    private lateinit var subdomainEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        subdomainEditText = findViewById(R.id.subdomainEditText)
        loginButton = findViewById(R.id.loginButton)
        webView = findViewById(R.id.webView)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }

        loginButton.setOnClickListener {
            val subdomain = subdomainEditText.text.toString().trim()
            if (subdomain.isNotEmpty()) {
                val url = "https://$subdomain.elintpos.in/"
                webView.loadUrl(url)
            }
        }
    }
}
