package org.civilinitiatives.launchpad

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * The Launchpad itself, in the app's own web view - the same pages and the
 * same design as the website, without a browser around them.
 *
 * Signing in with Google or Microsoft: Google refuses to sign in inside an
 * app's web view, so that one step happens in Chrome (a "custom tab"):
 * the site's ?applogin page signs in there and hands the session back to
 * this activity through an intent only this app can receive. We then open
 * ?appsession=1#at=..&rt=.. here and the site saves the sign-in as usual.
 */
class MainActivity : Activity() {

    companion object {
        const val HOME = "https://civilinitiatives.org/"
        private const val PICK_FILE = 41
        private val OUR_HOSTS = listOf("civilinitiatives.org", "hehs-live.duckdns.org")
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var progress: ProgressBar
    private var offline: View? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var signingIn = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
        }
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { gravity = Gravity.TOP })
        setContentView(root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString LaunchpadApp/2.0 (Android)"
        }
        web.addJavascriptInterface(Bridge(), "LaunchpadApp")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        val start = if (nightMode()) Color.rgb(17, 20, 27) else Color.rgb(246, 245, 241)
        web.setBackgroundColor(start)
        setBars(start)

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else if (!handle(intent)) web.loadUrl(HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** A sign-in coming back from the browser, or a Launchpad link. */
    private fun handle(i: Intent?): Boolean {
        val data = i?.data ?: return false
        if (data.scheme == "hehlaunchpad") {
            val at = i.getStringExtra("at") ?: fragmentParam(data, "at")
            val rt = i.getStringExtra("rt") ?: fragmentParam(data, "rt")
            if (!at.isNullOrEmpty() && !rt.isNullOrEmpty()) {
                web.loadUrl(HOME + "?appsession=1#at=" + Uri.encode(at) + "&rt=" + Uri.encode(rt))
                return true
            }
            return false
        }
        if (data.scheme == "https" && isOurs(data)) { web.loadUrl(data.toString()); return true }
        return false
    }

    private fun fragmentParam(u: Uri, key: String): String? =
        u.fragment?.split("&")?.map { it.split("=", limit = 2) }?.firstOrNull { it[0] == key }?.getOrNull(1)?.let { Uri.decode(it) }

    private fun isOurs(u: Uri): Boolean {
        val h = u.host ?: return false
        return OUR_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private fun openInChrome(url: String) {
        val tab = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(Color.parseColor("#3730A3")).build())
            .setShowTitle(true)
            .build()
        try { tab.launchUrl(this, Uri.parse(url)) }
        catch (e: ActivityNotFoundException) { openOutside(url) }
    }

    private fun openOutside(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url
            val scheme = u.scheme ?: ""
            if (scheme != "https" && scheme != "http") {
                if (scheme == "intent") {
                    try { startActivity(Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)) } catch (_: Exception) { }
                } else openOutside(u.toString())
                return true
            }
            /* Sign-in. Microsoft (and a school's own sign-in pages) work fine
               in here, so that whole trip stays in the app. Google refuses
               app web views, so Google sign-in happens in Chrome and comes back. */
            if (u.host == "hehs-live.duckdns.org" && (u.path ?: "").startsWith("/auth/v1/authorize")) {
                val provider = u.getQueryParameter("provider") ?: "google"
                if (provider == "google") {
                    openInChrome(HOME + "?applogin=google&ret=android")
                    return true
                }
                signingIn = true
                return false
            }
            if (isOurs(u)) {
                if (u.host == "civilinitiatives.org" || u.host?.endsWith(".civilinitiatives.org") == true) signingIn = false
                return false
            }
            if (signingIn) return false   // Microsoft / school sign-in pages
            openOutside(u.toString())
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            CookieManager.getInstance().flush()
            progress.visibility = View.GONE
            /* the phone's bars take the page's own background colour */
            view.evaluateJavascript("getComputedStyle(document.body).backgroundColor") { v -> barsFrom(v) }
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (req.isForMainFrame) showOffline()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) {
            progress.progress = p
            progress.visibility = if (p >= 100) View.GONE else View.VISIBLE
        }

        /* "Upload a photo" in the shop. */
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = cb
            return try {
                startActivityForResult(params.createIntent(), PICK_FILE); true
            } catch (e: Exception) { fileCallback = null; false }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** What the pages can ask the app to do. */
    inner class Bridge {
        @JavascriptInterface fun platform(): String = "android"
        @JavascriptInterface fun version(): String = "2.0"
        @JavascriptInterface fun openGame(name: String) {
            if (name == "rush") runOnUiThread { startActivity(Intent(this@MainActivity, GameActivity::class.java)) }
        }
    }

    private fun nightMode() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun setBars(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
        val light = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 > 160
        @Suppress("DEPRECATION")
        var f = window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        f = if (light) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            @Suppress("DEPRECATION")
            f = if (light) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = f
        root.setBackgroundColor(color)
    }

    private fun barsFrom(js: String?) {
        val m = Regex("""rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?""").find(js ?: return) ?: return
        val (r, g, b) = m.destructured
        val a = m.groupValues[4]
        if (a.isNotEmpty() && a.toFloatOrNull() == 0f) return
        setBars(Color.rgb(r.toInt(), g.toInt(), b.toInt()))
    }

    private fun showOffline() {
        if (offline != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#3730A3"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        box.addView(TextView(this).apply { text = "🚀"; textSize = 56f; gravity = Gravity.CENTER })
        box.addView(TextView(this).apply {
            text = "Can't reach the Launchpad"; textSize = 22f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = "Check your Wi-Fi or data, then try again."; textSize = 15f
            setTextColor(Color.parseColor("#C7D2FE")); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(20))
        })
        box.addView(Button(this).apply {
            text = "Try again"
            setOnClickListener { root.removeView(offline); offline = null; web.reload() }
        })
        offline = box
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }
    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
