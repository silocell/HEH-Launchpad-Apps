package org.civilinitiatives.launchpad

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Talks to the Launchpad's server for the native screens: play time
 * (30 minutes free a day, then 5p for each half hour) and pence for a
 * finished round. The same calls the website makes.
 */
object Server {
    private const val FN = "https://hehs-live.duckdns.org/functions/v1/profile"
    private const val KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzkyODMyMjkwLCJleHAiOjIxMDgxOTIyOTB9.7gFhQa5RBwVtiOPLO7Qf2-aVEbkd5xgpLiN1J06Gybo"
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The sign-in token, kept fresh by the hidden token page (see GameActivity). */
    @Volatile var token: String? = null

    fun call(body: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val t = token
        if (t == null) { done(null, "Sign in on the Launchpad first."); return }
        pool.execute {
            var result: JSONObject? = null
            var error: String? = null
            try {
                val c = URL(FN).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 12000; c.readTimeout = 15000
                c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer $t")
                c.setRequestProperty("apikey", KEY)
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
                val j = JSONObject(text)
                if (code >= 400 || j.has("error")) error = j.optString("error", "Something went wrong.") else result = j
            } catch (e: Exception) {
                error = "Can't reach the Launchpad."
            }
            main.post { done(result, error) }
        }
    }
}
