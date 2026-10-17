package com.bloodymary.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Listens continuously and offline for "bloody mary" using Vosk.
 * The recognizer is limited to that phrase plus "[unk]" (anything else), which
 * keeps it accurate and lightweight.
 */
class PhraseListener(
    private val context: Context,
    private val onPhrase: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onHeard: (String) -> Unit,
) : RecognitionListener {

    private var model: Model? = null
    private var speech: SpeechService? = null
    private var lastTrigger = 0L

    fun start() {
        onStatus("Loading speech model...")
        StorageService.unpack(context, "model-en-us", "model",
            { loaded ->
                model = loaded
                val recognizer = Recognizer(loaded, SAMPLE_RATE, "[\"$PHRASE\", \"[unk]\"]")
                speech = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(this) }
                onStatus("Listening")
            },
            { e -> onStatus("Speech model failed to load: ${e.message}") })
    }

    /** Stops hearing while true, e.g. while the phone itself is screaming. */
    fun setPaused(paused: Boolean) {
        speech?.setPause(paused)
        onStatus(if (paused) "Paused" else "Listening")
    }

    fun stop() {
        speech?.shutdown()
        speech = null
        model?.close()
        model = null
    }

    override fun onPartialResult(hypothesis: String) = check(hypothesis, "partial")
    override fun onResult(hypothesis: String) = check(hypothesis, "text")
    override fun onFinalResult(hypothesis: String) = check(hypothesis, "text")
    override fun onError(e: Exception) = onStatus("Speech error: ${e.message}")
    override fun onTimeout() = onStatus("Speech timeout")

    private fun check(hypothesis: String, key: String) {
        val text = JSONObject(hypothesis).optString(key)
        if (!text.contains(PHRASE)) return
        onHeard(text)

        // Partial results repeat while the phrase is being said, so one
        // utterance must only count once
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < COOLDOWN_MS) return
        lastTrigger = now
        onPhrase()
    }

    companion object {
        const val PHRASE = "bloody mary"
        const val COOLDOWN_MS = 4000L
        private const val SAMPLE_RATE = 16000f
    }
}
