package com.bloodymary.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/**
 * The three-stage scare. Each "bloody mary" calls [advance]:
 *   1. candles off (ESP32 IR burst)
 *   2. break-in sounds on the stereo (ESP32 Bluetooth audio)
 *   3. floodlight on (ESP32) + scream from the phone speaker
 */
class ShowController(
    private val context: Context,
    private val esp: Esp32Client,
    private val listener: () -> PhraseListener?,
    private val onChange: (String) -> Unit,
) {
    var stage = 0
        private set

    private var player: MediaPlayer? = null

    fun advance() {
        when (stage) {
            0 -> esp.get("/candles/off", done = ::report)
            1 -> esp.get("/audio/play", done = ::report)
            2 -> {
                esp.get("/light/on", done = ::report)
                playScream()
            }
            else -> return  // show is over until reset
        }
        stage++
        onChange(describe())
    }

    fun reset() {
        stopScream()
        stage = 0
        esp.get("/reset", done = ::report)
        onChange(describe())
    }

    fun describe(): String = when (stage) {
        0 -> "Waiting for 1st \"bloody mary\""
        1 -> "Candles out. Waiting for 2nd"
        2 -> "Break-in playing. Waiting for 3rd"
        else -> "Scream! Press Reset for the next guest"
    }

    private fun report(body: String?) {
        if (body == null) onChange("${describe()}\n(ESP32 did not respond)")
    }

    private fun playScream() {
        stopScream()
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)

        val mp = MediaPlayer()
        val asset = SCREAM_ASSETS.firstOrNull { it in context.assets.list("").orEmpty() } ?: SCREAM_ASSETS[0]
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
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { mp.setPreferredDevice(it) }
            mp.prepare()
        } catch (e: Exception) {
            Log.e(TAG, "Can't play assets/$asset", e)
            onChange("${describe()}\n(can't play assets/$asset)")
            mp.release()
            return
        }

        // Don't let the scream (or the guest's reaction) count as another trigger
        listener()?.setPaused(true)
        mp.setOnCompletionListener { stopScream() }
        mp.start()
        player = mp
    }

    private fun stopScream() {
        player?.release()
        player = null
        listener()?.setPaused(false)
    }

    companion object {
        private const val TAG = "ShowController"
        val SCREAM_ASSETS = listOf("scream.mp3", "scream.wav")
    }
}
