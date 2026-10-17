package com.bloodymary.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * The show. When a face appears while idle, Mary greets the guest by name (or
 * generically if unknown). Then each "bloody mary" calls [advance]:
 *   1. candles off (ESP32 IR burst)
 *   2. break-in sounds on the stereo (ESP32 Bluetooth audio)
 *   3. floodlight on (ESP32) + scream, then a personalized line
 * After [IDLE_RESET_MS] with no new trigger, the show resets itself.
 */
class ShowController(
    private val context: Context,
    private val esp: Esp32Client,
    private val voice: VoiceBank,
    private val listener: () -> PhraseListener?,
    private val onChange: (String) -> Unit,
) {
    var stage = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private val idleReset = Runnable { reset() }

    // Loaded ahead of time so the scream starts the instant it's triggered
    private var scream: MediaPlayer? = null
    private var screaming = false

    // Greeting: watch a new face for GREET_WINDOW_MS, keeping the best match
    private var guest: String? = null
    private var greeted = false
    private var windowStart = 0L
    private var lastSeen = 0L
    private var bestName: String? = null
    private var bestScore = -1f
    private val lastGreeted = mutableMapOf<String?, Long>()

    init {
        prepareScream()
    }

    fun advance() {
        when (stage) {
            0 -> esp.send("candles_off")
            1 -> esp.send("audio_play")
            2 -> {
                esp.send("light_on")
                voice.stop()
                playScream()
            }
            else -> return  // show is over until reset
        }
        stage++
        main.removeCallbacks(idleReset)
        main.postDelayed(idleReset, IDLE_RESET_MS)
        onChange(describe())
    }

    fun reset() {
        main.removeCallbacks(idleReset)
        stopScream()
        voice.stop()
        stage = 0
        guest = null
        greeted = false
        windowStart = 0
        esp.get("/reset") { body -> if (body == null) onChange("${describe()}\n(ESP32 did not respond to reset)") }
        onChange(describe())
    }

    /** Called a few times per second while the camera sees a face. */
    fun onFace(sighting: FaceWatcher.Sighting) {
        val now = SystemClock.elapsedRealtime()
        if (sighting.name != null && guest == null) guest = sighting.name  // remember for the finale

        if (stage != 0 || greeted) return

        // A new visit starts when a face shows up after a gap
        if (windowStart == 0L || now - lastSeen > FACE_GAP_MS) {
            windowStart = now
            bestName = null
            bestScore = -1f
        }
        lastSeen = now
        if (sighting.name != null && sighting.score > bestScore) {
            bestName = sighting.name
            bestScore = sighting.score
        }
        if (now - windowStart < GREET_WINDOW_MS) return

        greeted = true
        guest = bestName
        // Someone who lingers through an auto-reset isn't greeted twice in a row
        val last = lastGreeted[bestName]
        if (last != null && now - last < REGREET_MS) return
        lastGreeted[bestName] = now
        voice.play(VoiceBank.Line.GREETING, bestName)
        onChange(describe())
    }

    fun describe(): String = when (stage) {
        0 -> if (greeted) "Greeted. Waiting for 1st \"bloody mary\"" else "Waiting for a guest"
        1 -> "Candles out. Waiting for 2nd"
        2 -> "Break-in playing. Waiting for 3rd"
        else -> "Scream! Resets in 1 min"
    } + "\nGuest: ${guest ?: if (greeted) "unknown" else "-"}" +
        if (stage in 1..2) "\n(resets after 1 min idle)" else ""

    private fun prepareScream() {
        val asset = SCREAM_ASSETS.firstOrNull { it in context.assets.list("").orEmpty() }
        if (asset == null) {
            Log.e(TAG, "No scream file in assets ($SCREAM_ASSETS)")
            return
        }
        val mp = MediaPlayer()
        try {
            context.assets.openFd(asset).use { fd ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            // Always the phone's own speaker, even if something is paired over Bluetooth
            context.getSystemService(AudioManager::class.java)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { mp.setPreferredDevice(it) }
            mp.setOnCompletionListener {
                stopScream()
                voice.play(VoiceBank.Line.FINALE, guest)
            }
            mp.prepare()
            scream = mp
        } catch (e: Exception) {
            Log.e(TAG, "Can't load assets/$asset", e)
            mp.release()
        }
    }

    private fun playScream() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)

        val mp = scream
        if (mp == null) {
            onChange("${describe()}\n(no playable scream file in assets)")
            voice.play(VoiceBank.Line.FINALE, guest)
            return
        }
        // Don't let the scream (or the guest's reaction) count as another trigger
        listener()?.setPaused(true)
        screaming = true
        mp.start()
    }

    /** Stops the scream if it's playing and loads a fresh copy for the next guest. */
    private fun stopScream() {
        if (!screaming) return
        screaming = false
        scream?.release()
        scream = null
        listener()?.setPaused(false)
        prepareScream()
    }

    companion object {
        private const val TAG = "ShowController"
        private const val IDLE_RESET_MS = 60_000L
        private const val GREET_WINDOW_MS = 1_200L  // look at a new face this long before greeting
        private const val FACE_GAP_MS = 3_000L      // no face for this long = the visit is over
        private const val REGREET_MS = 5 * 60_000L
        val SCREAM_ASSETS = listOf("scream.mp3", "scream.wav")
    }
}
