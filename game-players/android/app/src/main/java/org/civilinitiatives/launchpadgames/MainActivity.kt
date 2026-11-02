// Launchpad Games for Android.
//
// One app holds every game you download from the Game Maker. Each download
// is this same app with one game tucked into the APK's zip comment, like
//   "HLGAME01" + base64(gzip({"title":...,"by":...,"html":...}))
// Installing a new download updates the app; on start it copies the game
// it carries into its own list, so the games you had before stay.
// Games run with the Launchpad's lock: no internet at all.
package org.civilinitiatives.launchpadgames

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class MainActivity : Activity() {
    private val lock = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; form-action 'none'\">"
    private var web: WebView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val fresh = importCarried()
        val games = list()
        if (fresh != null && games.size == 1) play(fresh) else showList(games, fresh)
    }

    private fun dir() = File(filesDir, "games").apply { mkdirs() }

    /** Reads the game in this APK's zip comment and keeps a copy. Returns its id. */
    private fun importCarried(): String? = try {
        RandomAccessFile(applicationInfo.sourceDir, "r").use { f ->
            val n = f.length()
            val tailLen = minOf(n, 65557L + 22)
            val tail = ByteArray(tailLen.toInt())
            f.seek(n - tailLen); f.readFully(tail)
            var at = -1
            for (i in tail.size - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte()) { at = i; break }
            }
            if (at < 0) return null
            val clen = (tail[at + 20].toInt() and 0xff) or ((tail[at + 21].toInt() and 0xff) shl 8)
            val comment = String(tail, at + 22, minOf(clen, tail.size - at - 22), Charsets.US_ASCII)
            if (!comment.startsWith("HLGAME01")) return null
            val gz = Base64.decode(comment.substring(8), Base64.DEFAULT)
            val json = GZIPInputStream(ByteArrayInputStream(gz)).readBytes().toString(Charsets.UTF_8)
            val o = JSONObject(json)
            val id = MessageDigest.getInstance("SHA-256").digest((o.optString("title") + "\n" + o.optString("html")).toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
            val file = File(dir(), "$id.json")
            if (!file.exists()) { o.put("added", System.currentTimeMillis()); file.writeText(o.toString()) }
            id
        }
    } catch (e: Exception) { null }

    private fun list(): List<Pair<String, JSONObject>> =
        (dir().listFiles() ?: arrayOf()).filter { it.name.endsWith(".json") }
            .mapNotNull { f -> try { f.name.removeSuffix(".json") to JSONObject(f.readText()) } catch (e: Exception) { null } }
            .sortedByDescending { it.second.optLong("added") }

    private fun showList(games: List<Pair<String, JSONObject>>, fresh: String?) {
        web?.destroy(); web = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48); setBackgroundColor(Color.rgb(20, 22, 44)) }
        col.addView(TextView(this).apply { text = "Launchpad Games"; textSize = 26f; setTextColor(Color.WHITE); setPadding(0, 0, 0, 12) })
        col.addView(TextView(this).apply {
            text = if (games.isEmpty()) "No games yet. Make a game on the Homework Launchpad's Game Maker, then download it for Android." else "Your games from the Game Maker:"
            textSize = 15f; setTextColor(Color.rgb(190, 196, 230)); setPadding(0, 0, 0, 24)
        })
        for ((id, g) in games) {
            col.addView(Button(this).apply {
                text = g.optString("title", "Game") + (if (g.optString("by").isNotEmpty()) "  ·  by " + g.optString("by") else "") + (if (id == fresh) "  ·  new" else "")
                isAllCaps = false; textSize = 17f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { play(id) }
                setOnLongClickListener { AlertBuilder.ask(this@MainActivity, "Remove \"" + g.optString("title") + "\" from this app?") { File(dir(), "$id.json").delete(); showList(list(), null) }; true }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 })
        }
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Color.rgb(20, 22, 44)) })
    }

    private fun play(id: String) {
        val g = try { JSONObject(File(dir(), "$id.json").readText()) } catch (e: Exception) { return }
        var html = g.optString("html")
        val m = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)
        html = if (m != null) m.value + lock + html.substring(m.value.length) else lock + html
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.setBackgroundColor(Color.BLACK)
        w.webViewClient = object : WebViewClient() {
            // The game stays the game: no going anywhere, nothing loaded from the internet.
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                return if (u.startsWith("data:") || u.startsWith("blob:") || u == "https://game.launchpad/") null
                       else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        w.loadDataWithBaseURL("https://game.launchpad/", html, "text/html", "utf-8", null)
        web = w
        setContentView(w)
    }

    @Deprecated("Back goes to the list")
    override fun onBackPressed() {
        if (web != null && list().size > 1) showList(list(), null) else super.onBackPressed()
    }
}

object AlertBuilder {
    fun ask(a: Activity, q: String, yes: () -> Unit) {
        android.app.AlertDialog.Builder(a).setMessage(q).setPositiveButton("Remove") { _, _ -> yes() }.setNegativeButton("Keep", null).show()
    }
}
