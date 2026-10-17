package com.bloodymary.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var stageText: TextView
    private lateinit var listenText: TextView
    private lateinit var heardText: TextView
    private lateinit var espText: TextView
    private lateinit var addressInput: EditText

    private lateinit var esp: Esp32Client
    private lateinit var show: ShowController
    private var listener: PhraseListener? = null

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // The phone is hidden in the bathroom for the whole show
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        stageText = findViewById(R.id.stageText)
        listenText = findViewById(R.id.listenText)
        heardText = findViewById(R.id.heardText)
        espText = findViewById(R.id.espText)
        addressInput = findViewById(R.id.addressInput)

        addressInput.setText(prefs.getString(KEY_ADDRESS, DEFAULT_ADDRESS))
        esp = Esp32Client { addressInput.text.toString() }
        show = ShowController(this, esp, { listener }) { stageText.text = it }
        stageText.text = show.describe()

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            saveAddress()
            show.reset()
        }
        findViewById<Button>(R.id.nextButton).setOnClickListener {
            saveAddress()
            show.advance()
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            listenText.text = "Microphone permission is required"
        }
    }

    private fun startListening() {
        listener = PhraseListener(
            this,
            onPhrase = { show.advance() },
            onStatus = { listenText.text = "Mic: $it" },
            onHeard = { heardText.text = "Heard: \"$it\"" },
        ).also { it.start() }
    }

    override fun onResume() {
        super.onResume()
        main.post(pollStatus)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(pollStatus)
        saveAddress()
    }

    override fun onDestroy() {
        listener?.stop()
        super.onDestroy()
    }

    private val pollStatus: Runnable = object : Runnable {
        override fun run() {
            esp.get("/status", attempts = 1) { body -> espText.text = describeStatus(body) }
            main.postDelayed(this, STATUS_POLL_MS)
        }
    }

    private fun describeStatus(body: String?): String {
        if (body == null) return "ESP32: not reachable"
        return try {
            val s = JSONObject(body)
            "ESP32: ${s.optString("ip")}  amp ${if (s.optBoolean("amp_connected")) "connected" else "NOT connected"}" +
                (if (s.optBoolean("playing")) "  playing" else "") +
                (if (s.optBoolean("light")) "  light on" else "")
        } catch (e: Exception) {
            "ESP32: bad status"
        }
    }

    private fun saveAddress() {
        prefs.edit().putString(KEY_ADDRESS, addressInput.text.toString().trim()).apply()
    }

    companion object {
        private const val REQUEST_MIC = 1
        private const val KEY_ADDRESS = "esp_address"
        private const val DEFAULT_ADDRESS = "bloodymary.local"
        private const val STATUS_POLL_MS = 3000L
    }
}
