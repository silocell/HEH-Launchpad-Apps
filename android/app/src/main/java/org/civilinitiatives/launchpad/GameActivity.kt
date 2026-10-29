package org.civilinitiatives.launchpad

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * Rocket Rush, the native version. The game itself is GameView; this
 * screen makes it full-screen and keeps a fresh sign-in token for the
 * play-time calls, from a tiny hidden page (app-token.html) that reads
 * the same sign-in the Launchpad uses.
 */
class GameActivity : Activity() {
    private lateinit var game: GameView
    private var tokenPage: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        game = GameView(this, if (tablet) GameView.Quality.RICH else GameView.Quality.LIGHT) { finish() }
        val root = FrameLayout(this)
        /* the hidden token page: 1x1, never seen */
        val wv = WebView(this)
        CookieManager.getInstance().setAcceptCookie(true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = WebViewClient()
        wv.addJavascriptInterface(object {
            @JavascriptInterface fun token(msg: String) {
                try {
                    if (msg == "null") { Server.token = null; game.post { game.signedOut() } }
                    else { Server.token = JSONObject(msg).getString("at"); game.post { game.tokenReady() } }
                } catch (_: Exception) { }
            }
        }, "LaunchpadApp")
        wv.loadUrl("https://civilinitiatives.org/app-token.html")
        tokenPage = wv
        root.addView(wv, FrameLayout.LayoutParams(1, 1))
        root.addView(game, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        immersive()
    }

    private fun immersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c) }
    override fun onPause() { super.onPause(); game.pauseGame() }
    override fun onResume() { super.onResume(); game.resumeGame() }
    override fun onDestroy() { game.finishUp(); tokenPage?.destroy(); super.onDestroy() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!game.back()) super.onBackPressed() }
}
