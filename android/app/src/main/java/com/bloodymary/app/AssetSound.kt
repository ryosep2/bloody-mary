package com.bloodymary.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * A sound from the app's assets (the first of [names] that exists), loaded
 * ahead of time so it starts the instant [play] is called. Plays on the media
 * stream, which goes to the Bluetooth amp whenever it's connected.
 */
class AssetSound(private val context: Context, private val names: List<String>) {

    private var player: MediaPlayer? = null
    var playing = false
        private set

    val available: Boolean get() = player != null

    init {
        load()
    }

    fun play(onDone: () -> Unit = {}) {
        val mp = player ?: return onDone()
        mp.setOnCompletionListener {
            stop()
            onDone()
        }
        playing = true
        mp.start()
    }

    /** Stops if playing, and reloads so it's ready for next time. */
    fun stop() {
        if (!playing) return
        playing = false
        player?.release()
        player = null
        load()
    }

    fun release() {
        player?.release()
        player = null
    }

    private fun load() {
        val asset = names.firstOrNull { it in context.assets.list("").orEmpty() }
        if (asset == null) {
            Log.e(TAG, "None of $names in assets")
            return
        }
        val mp = MediaPlayer()
        try {
            context.assets.openFd(asset).use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.prepare()
            player = mp
        } catch (e: Exception) {
            Log.e(TAG, "Can't load assets/$asset", e)
            mp.release()
        }
    }

    companion object {
        private const val TAG = "AssetSound"
    }
}
