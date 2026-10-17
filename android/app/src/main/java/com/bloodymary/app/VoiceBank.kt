package com.bloodymary.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Mary's spoken lines. They are generated with ElevenLabs when a guest is added
 * and saved on the phone, so playback during the show is instant and offline.
 * If a line hasn't been generated, the phone's own voice says it, pitched down.
 */
class VoiceBank(private val context: Context, private val prefs: SharedPreferences) {

    enum class Line { GREETING, FINALE }

    private val dir = File(context.filesDir, "voice").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setPitch(0.5f)
            tts?.setSpeechRate(0.75f)
        }
    }

    /** The text of [line], with [name] filled in (null for someone unknown). */
    fun text(line: Line, name: String?): String = when (line) {
        Line.GREETING -> if (name != null) VoiceLines.GREETING_NAMED.format(name) else VoiceLines.GREETING_UNKNOWN
        Line.FINALE -> if (name != null) VoiceLines.FINALE_NAMED.format(name) else VoiceLines.FINALE_UNKNOWN
    }

    /**
     * Generates any missing lines for each of [names] (null = the unknown-guest
     * lines) in the background. [done] gets an error message, or null on success.
     */
    fun generate(names: List<String?>, done: (String?) -> Unit = {}) {
        val key = prefs.getString(KEY_API_KEY, "").orEmpty()
        val voice = prefs.getString(KEY_VOICE_ID, "").orEmpty()
        val model = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        if (key.isBlank() || voice.isBlank()) {
            done("Add your ElevenLabs API key and voice ID in Settings")
            return
        }
        executor.execute {
            var error: String? = null
            for (name in names) for (line in Line.entries) {
                val text = text(line, name)
                val file = fileFor(text, voice, model)
                if (file.exists()) continue
                try {
                    synthesize(text, key, voice, model, file)
                } catch (e: Exception) {
                    Log.e(TAG, "ElevenLabs failed for \"$text\"", e)
                    error = "ElevenLabs: ${e.message}"
                }
            }
            main.post { done(error) }
        }
    }

    /** Speaks [line] for [name] from the phone's speaker, then calls [onDone]. */
    fun play(line: Line, name: String?, onDone: () -> Unit = {}) {
        stop()
        val text = text(line, name)
        val voice = prefs.getString(KEY_VOICE_ID, "").orEmpty()
        val model = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        val file = fileFor(text, voice, model)

        if (voice.isNotBlank() && file.exists()) {
            val mp = MediaPlayer()
            try {
                mp.setDataSource(file.path)
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                builtInSpeaker()?.let { mp.setPreferredDevice(it) }
                mp.setOnCompletionListener { stop(); onDone() }
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                Log.e(TAG, "Can't play ${file.name}", e)
                mp.release()
            }
        }

        // Fallback: the phone's own text-to-speech
        val engine = tts
        if (engine == null || !ttsReady) {
            onDone()
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post(onDone) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post(onDone) }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "line")
    }

    fun stop() {
        player?.release()
        player = null
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
    }

    private fun builtInSpeaker(): AudioDeviceInfo? =
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    // One cached file per exact text + voice + model, so editing any of them
    // simply generates a new file
    private fun fileFor(text: String, voice: String, model: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest("$model|$voice|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.mp3")
    }

    private fun synthesize(text: String, apiKey: String, voice: String, model: String, out: File) {
        val conn = URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("xi-api-key", apiKey)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "audio/mpeg")
        val body = JSONObject()
            .put("text", text)
            .put("model_id", model)
            .put("voice_settings", JSONObject().put("stability", 0.35).put("similarity_boost", 0.75).put("speed", 0.85))
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                val detail = conn.errorStream?.bufferedReader()?.readText().orEmpty().take(200)
                throw IllegalStateException("HTTP ${conn.responseCode} $detail")
            }
            val tmp = File(out.path + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(out)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "VoiceBank"
        const val KEY_API_KEY = "elevenlabs_api_key"
        const val KEY_VOICE_ID = "elevenlabs_voice_id"
        const val KEY_MODEL = "elevenlabs_model"
        const val DEFAULT_MODEL = "eleven_v4"
    }
}

/**
 * What Mary says. %s is the guest's name. Edit freely: changed lines are
 * regenerated the next time guests are imported or settings are saved.
 * Don't put the words "bloody mary" in a line, or the phone will trigger itself.
 */
object VoiceLines {
    const val GREETING_NAMED = "%s... I've been waiting for you. Look into the mirror... and say my name."
    const val GREETING_UNKNOWN = "I see you... Look into the mirror... and say my name."
    const val FINALE_NAMED = "%s... I'm going to peel off your pretty face... and wear it."
    const val FINALE_UNKNOWN = "I'm going to peel off your pretty face... and wear it."
}
