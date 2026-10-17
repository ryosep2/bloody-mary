package com.bloodymary.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Calls the ESP32's HTTP endpoints off the main thread, retrying a few times. */
class Esp32Client(private val address: () -> String) {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Runs GET [path] and passes the response body (or null on failure) to [done] on the main thread. */
    fun get(path: String, attempts: Int = 3, done: (String?) -> Unit = {}) {
        val host = address().trim().removePrefix("http://").trimEnd('/')  // read on the caller's (UI) thread
        executor.execute {
            var body: String? = null
            for (attempt in 1..attempts) {
                body = try {
                    fetch(host, path)
                } catch (e: Exception) {
                    Log.w(TAG, "GET $path attempt $attempt failed: $e")
                    null
                }
                if (body != null) break
            }
            main.post { done(body) }
        }
    }

    private fun fetch(host: String, path: String): String {
        val conn = URL("http://$host$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 3000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "Esp32Client"
    }
}
