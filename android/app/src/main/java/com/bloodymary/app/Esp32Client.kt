package com.bloodymary.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Talks to the ESP32: show triggers go out as UDP packets (fast), status and
 * reset use HTTP (slower, but confirmed). Each has its own thread so a slow
 * status request can never delay a trigger.
 */
class Esp32Client(private val address: () -> String) {

    private val httpExecutor = Executors.newSingleThreadExecutor()
    private val udpExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val socket by lazy { DatagramSocket() }
    private var resolvedHost = ""
    private var resolvedAddress: InetAddress? = null
    private var nextId = Random.nextInt(1, 1_000_000)

    /**
     * Sends [command] (candles_off, audio_play, light_on, reset) as a UDP packet.
     * The packet goes out 3 times, 20 ms apart, in case one is lost; the ESP32
     * runs it only once.
     */
    fun send(command: String) {
        val host = host()  // read on the caller's (UI) thread
        val payload = "${nextId++} $command".toByteArray()
        udpExecutor.execute {
            try {
                val addr = resolve(host)
                repeat(UDP_COPIES) { i ->
                    if (i > 0) Thread.sleep(UDP_COPY_GAP_MS)
                    socket.send(DatagramPacket(payload, payload.size, addr, UDP_PORT))
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP $command failed: $e")
            }
        }
    }

    /** Runs GET [path] and passes the response body (or null on failure) to [done] on the main thread. */
    fun get(path: String, attempts: Int = 3, done: (String?) -> Unit = {}) {
        val host = host()
        httpExecutor.execute {
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

    private fun host() = address().trim().removePrefix("http://").trimEnd('/')

    // Looking up bloodymary.local can be slow, so only do it when the address changes
    private fun resolve(host: String): InetAddress {
        if (host != resolvedHost || resolvedAddress == null) {
            resolvedAddress = InetAddress.getByName(host)
            resolvedHost = host
        }
        return resolvedAddress!!
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
        private const val UDP_PORT = 4210
        private const val UDP_COPIES = 3
        private const val UDP_COPY_GAP_MS = 20L
    }
}
