package com.sari.subtitle

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class BrowserActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var lastLoadedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        webView = findViewById(R.id.browserWebView)
        urlInput = findViewById(R.id.browserUrlInput)
        progress = findViewById(R.id.browserProgress)
        status = findViewById(R.id.browserStatus)

        findViewById<MaterialButton>(R.id.browserOpenButton).setOnClickListener { loadInput() }
        findViewById<MaterialButton>(R.id.browserBackButton).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<MaterialButton>(R.id.browserForwardButton).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<MaterialButton>(R.id.browserRefreshButton).setOnClickListener { webView.reload() }
        findViewById<MaterialButton>(R.id.browserExternalButton).setOnClickListener { openExternal(webView.url) }
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.browserToolbar)
            .setNavigationOnClickListener { finish() }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return if (uri.scheme == "http" || uri.scheme == "https") false else {
                    openExternal(uri.toString()); true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                urlInput.setText(url)
                progress.visibility = View.VISIBLE
                status.text = getString(R.string.browser_loading)
            }

            override fun onPageFinished(view: WebView, url: String) {
                lastLoadedUrl = url
                urlInput.setText(url)
                progress.visibility = View.GONE
                status.text = getString(R.string.browser_ready)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    progress.visibility = View.GONE
                    status.text = getString(R.string.browser_error, error.description)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                status.text = getString(R.string.browser_ssl_error)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        val incoming = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (incoming.isNotBlank()) loadUrl(incoming) else loadUrl("https://www.google.com/")
    }

    private fun loadInput() {
        val raw = urlInput.text.toString().trim()
        if (VideoPlatformResolver.resolve(raw) == null) {
            urlInput.error = getString(R.string.browser_invalid_url)
            return
        }
        loadUrl(raw)
    }

    private fun loadUrl(raw: String) {
        val resolved = VideoPlatformResolver.resolve(raw)
        if (resolved == null) {
            status.text = getString(R.string.browser_invalid_url)
            return
        }
        lastLoadedUrl = null
        webView.loadUrl(resolved.uri.toString())
    }

    private fun openExternal(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.browser_no_external, Toast.LENGTH_LONG).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) { webView.goBack(); true } else super.onKeyDown(keyCode, event)

    override fun onDestroy() {
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "browser_url" }
}
