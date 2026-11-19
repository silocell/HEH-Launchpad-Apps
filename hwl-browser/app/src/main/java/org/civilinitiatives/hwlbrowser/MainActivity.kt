package org.civilinitiatives.hwlbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * HWL Browser: a school-safe web browser.
 * Every website you open (typed, searched, or a link inside a page) is checked first by the
 * Launchpad's AI safety check, unless it's a trusted learning site. Search uses HWL Search
 * (our own YaCy index), CTube videos and Wikipedia. Google Safe Browsing is on too.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var addr: EditText
    private lateinit var bar: ProgressBar
    private lateinit var cover: TextView

    private val home = "file:///android_asset/home.html"   // the built-in search page
    private val newTab = "https://chausima.com/"           // start page, Home button and new tabs
    private val check = "https://hehs-live.duckdns.org/functions/v1/linkcheck"
    private val trusted = Regex(
        "(^|\\.)(wikipedia\\.org|wikimedia\\.org|bbc\\.co\\.uk|civilinitiatives\\.org|chausima\\.com|kiddle\\.co|nasa\\.gov|" +
            "nhm\\.ac\\.uk|britannica\\.com|natgeokids\\.com|mathsisfun\\.com|gov\\.uk|scratch\\.mit\\.edu|khanacademy\\.org|" +
            "developer\\.mozilla\\.org|w3schools\\.com|duckdns\\.org|" +
            "youtube\\.com|youtu\\.be|youtube-nocookie\\.com|google\\.com|google\\.co\\.uk|office\\.com|microsoft365\\.com|office365\\.com|live\\.com|microsoftonline\\.com|sharepoint\\.com|onedrive\\.com|microsoft\\.com|cloud\\.microsoft|github\\.com|" +
            "sparx-learning\\.com|sparxmaths\\.uk|sparxreader\\.com|sparxmaths\\.com|educake\\.co\\.uk|senecalearning\\.com|arbor\\.sc|arbor-education\\.com|wiktionary\\.org)$", RegexOption.IGNORE_CASE
    )
    private val tube = Regex("(^|\\.)(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)$", RegexOption.IGNORE_CASE)
    private val googleSearch = Regex("(^|\\.)google\\.(com|co\\.uk)$", RegexOption.IGNORE_CASE)
    /** A single YouTube video: checked by the AI (judged like a "15" rating) even though YouTube itself is trusted. */
    private fun isVideo(u: Uri): Boolean {
        val h = (u.host ?: "").lowercase(); val p = u.path ?: ""
        return tube.containsMatchIn(h) && (h.endsWith("youtu.be") || Regex("^/(watch|shorts/|embed/|live/)").containsMatchIn(p))
    }
    private val okUrls = HashSet<String>()          // pages already passed this session
    private val badHosts = HashMap<String, String>() // websites already blocked, and why

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        // ---- top bar ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6)); setBackgroundColor(Color.WHITE)
        }
        fun btn(label: String, desc: String, act: () -> Unit) = TextView(this).apply {
            text = label; textSize = 20f; gravity = Gravity.CENTER; contentDescription = desc
            setTextColor(Color.parseColor("#1d2433"))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            isClickable = true; isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { act() }
        }
        top.addView(btn("←", "Back") { if (web.canGoBack()) web.goBack() })
        top.addView(btn("⌂", "Home") { web.loadUrl(newTab) })
        addr = EditText(this).apply {
            hint = "Search or type a web address"; textSize = 15f; isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true); setPadding(dp(16), 0, dp(16), 0)
            background = pill(Color.parseColor("#eef1f7"), 22); setTextColor(Color.parseColor("#1d2433"))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                    go(text.toString()); true
                } else false
            }
        }
        top.addView(addr)
        top.addView(btn("↻", "Reload") { web.reload() })
        root.addView(top)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)); visibility = View.GONE
        }
        root.addView(bar)

        // ---- page + "checking" cover ----
        val frame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        web = WebView(this)
        frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        cover = TextView(this).apply {
            text = "🔎\n\nChecking this page is OK for school…"; textSize = 17f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1d2433")); setBackgroundColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE; isClickable = true
        }
        frame.addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(frame)
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = true                                     // only for the built-in home page
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)                           // pop-ups open in the same page, and get checked
            userAgentString = userAgentString + " HWLBrowser/1.0"
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { bar.progress = p; bar.visibility = if (p in 1..99) View.VISIBLE else View.GONE }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (!r.isForMainFrame) return false
                return !allowedNow(r.url.toString())
            }
            override fun onPageStarted(v: WebView, url: String, f: android.graphics.Bitmap?) { showAddress(url) }
            // YouTube changes video without loading a new page: check those too
            override fun doUpdateVisitedHistory(v: WebView, url: String, reload: Boolean) {
                if (isVideo(Uri.parse(url)) && url !in okUrls && cover.visibility != View.VISIBLE) {
                    v.loadUrl("about:blank"); checkThenOpen(url, (Uri.parse(url).host ?: "").lowercase())
                }
            }
        }
        if (saved != null) web.restoreState(saved) else if (!openLink(intent?.data)) web.loadUrl(newTab)
    }

    override fun onNewIntent(i: android.content.Intent) { super.onNewIntent(i); openLink(i.data) }

    /** net.chausima.com/?q=words searches, ?u=address opens a website (after the usual check), and
     *  hwlbrowser://open?q=... or ?u=... do the same. */
    private fun openLink(d: Uri?): Boolean {
        if (d == null) return false
        val ok = (d.scheme == "hwlbrowser") || (d.scheme == "https" && d.host == "net.chausima.com")
        if (!ok) return false
        val q = d.getQueryParameter("q")?.trim().orEmpty()
        val u = d.getQueryParameter("u")?.trim().orEmpty()
        when {
            u.isNotEmpty() -> open(if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)) u else "https://$u")
            q.isNotEmpty() -> web.loadUrl(home + "#q=" + URLEncoder.encode(q, "UTF-8").replace("+", "%20"))
            else -> web.loadUrl(newTab)
        }
        return true
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }

    @Deprecated("Back button")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed() }

    private fun showAddress(url: String) {
        if (addr.hasFocus()) return
        if (url.startsWith(home)) {
            val q = Uri.parse(url).fragment?.removePrefix("q=")?.let { Uri.decode(it) } ?: ""
            addr.setText(q)
        } else addr.setText(url)
    }

    /** What you typed: a web address opens it, anything else searches. */
    private fun go(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(addr.windowToken, 0)
        addr.clearFocus()
        val looksLikeAddress = !t.contains(' ') && t.contains('.')
        if (looksLikeAddress) {
            val u = if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(t)) t else "https://$t"
            open(u)
        } else web.loadUrl(home + "#q=" + URLEncoder.encode(t, "UTF-8").replace("+", "%20"))
    }

    /** Open an address the app chose (typed, or after a check). */
    private fun open(url: String) { if (allowedNow(url)) web.loadUrl(url) }

    /** true = go ahead now; false = blocked, or being checked (it opens by itself if it passes). */
    private fun allowedNow(url: String): Boolean {
        val u = Uri.parse(url)
        val scheme = (u.scheme ?: "").lowercase()
        if (url.startsWith(home) || url.startsWith("file:///android_asset/") || scheme == "about" || scheme == "data") return true
        if (scheme != "https" && scheme != "http") { blocked(url, "HWL Browser only opens web pages."); return false }
        val host = (u.host ?: "").lowercase()
        if (url in okUrls) return true
        if (googleSearch.containsMatchIn(host) && u.path == "/search" && u.getQueryParameter("safe") != "active") {
            web.loadUrl(url + (if (url.contains('?')) "&" else "?") + "safe=active"); return false   // Google SafeSearch on
        }
        if (isVideo(u)) { checkThenOpen(url, host); return false }
        if (trusted.containsMatchIn(host)) return true
        badHosts[host]?.let { blocked(url, it); return false }
        checkThenOpen(url, host)
        return false
    }

    private fun checkThenOpen(url: String, host: String) {
        cover.visibility = View.VISIBLE; addr.setText(url)
        thread {
            var ok = false
            var why = "The safety check isn't answering right now, so this page stays closed. Try again in a minute."
            try {
                val c = (URL(check).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 10000; readTimeout = 20000; doOutput = true
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("apikey", BuildConfig.ANON_KEY)
                    setRequestProperty("Authorization", "Bearer " + BuildConfig.ANON_KEY)
                    setRequestProperty("x-hwl-app", "hwl-browser/1.0")
                }
                c.outputStream.use { it.write(JSONObject().put("url", url).put("age", 15).toString().toByteArray()) }
                val body = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                val j = JSONObject(body)
                ok = j.optBoolean("ok", false)
                if (!ok) why = j.optString("reason", why)
            } catch (_: Exception) { /* stays closed */ }
            runOnUiThread {
                cover.visibility = View.GONE
                if (ok) { okUrls.add(url); web.loadUrl(url) }
                else {
                    if (why.contains("isn't suitable") && !isVideo(Uri.parse(url))) badHosts[host] = why
                    blocked(url, why)
                }
            }
        }
    }

    private fun blocked(url: String, why: String) {
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        val html = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
            <body style="font-family:sans-serif;text-align:center;padding:60px 24px;color:#1d2433">
            <div style="font-size:56px">🛡️</div><h2>Not opened</h2><p style="color:#5b6478">${esc(why)}</p>
            <p style="color:#9aa1b5;font-size:13px;word-break:break-all">${esc(url)}</p>
            <p><a href="$home" style="color:#3b6cf0;font-weight:bold;text-decoration:none">Back to HWL Search</a></p></body>"""
        web.loadDataWithBaseURL("file:///android_asset/blocked", html, "text/html", "utf-8", null)
        addr.setText(url)
    }
}
