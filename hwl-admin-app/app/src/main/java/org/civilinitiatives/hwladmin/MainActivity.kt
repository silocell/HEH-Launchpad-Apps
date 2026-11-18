package org.civilinitiatives.hwladmin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * HWL Admin: opens https://admin.chausima.com full-screen. Only HWL Admin and its server open inside the app;
 * any other link opens in the phone's normal browser. Nothing is saved on the phone except the sign-in.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var offline: TextView
    private val home = "https://admin.chausima.com/"
    private val inside = Regex("^(admin\\.chausima\\.com|hehs-live\\.duckdns\\.org)$", RegexOption.IGNORE_CASE)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0d1220")) }
        web = WebView(this)
        frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        offline = TextView(this).apply {
            text = "HWL Admin can't reach the server.\n\nCheck your connection, then tap to try again."
            textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#e8ecf6")); setBackgroundColor(Color.parseColor("#0d1220"))
            visibility = View.GONE; setPadding(48, 48, 48, 48)
            setOnClickListener { visibility = View.GONE; web.reload() }
        }
        frame.addView(offline, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(frame)

        web.setBackgroundColor(Color.parseColor("#0d1220"))
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            userAgentString = userAgentString + " HWLAdminApp/1.0"
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val host = r.url.host ?: ""
                if (r.url.scheme == "https" && inside.containsMatchIn(host)) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) { }
                return true
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame) offline.visibility = View.VISIBLE
            }
        }
        if (saved != null) web.restoreState(saved) else web.loadUrl(home)
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    @Deprecated("Back button")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed() }
}
