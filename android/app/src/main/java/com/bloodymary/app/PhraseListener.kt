package com.bloodymary.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Listens continuously and offline for "bloody mary" using Vosk.
 *
 * The recognizer only knows a few words. Besides the phrase, it knows "bloody",
 * "blood" and "mary" on their own, plus "[unk]" (anything else). Without those
 * alternatives it would report "bloody mary" as soon as it heard "bloody",
 * because nothing else would fit.
 */
class PhraseListener(
    private val context: Context,
    private val onPhrase: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onHeard: (String) -> Unit,
) : RecognitionListener {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var phraseRecognizer: Recognizer? = null
    private var capture: Capture? = null  // listening to anything, e.g. a guest's name
    private var lastTrigger = 0L
    private var counted = 0  // phrases already acted on in the current utterance
    private var pending = 0  // consecutive partials showing a new phrase

    fun start() {
        onStatus("Loading speech model...")
        StorageService.unpack(context, "model-en-us", "model",
            { loaded ->
                model = loaded
                startPhraseListening()
            },
            { e -> onStatus("Speech model failed to load: ${e.message}") })
    }

    private fun startPhraseListening() {
        val m = model ?: return
        val grammar = GRAMMAR.joinToString(", ", "[", "]") { "\"$it\"" }
        try {
            val recognizer = Recognizer(m, SAMPLE_RATE, grammar)
            phraseRecognizer = recognizer
            speech = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(this) }
        } catch (e: Exception) {
            Log.e(TAG, "Can't start listening", e)
            onStatus("Microphone problem: ${e.message}")
            return
        }
        counted = 0
        pending = 0
        onStatus("Listening")
    }

    // The listening thread must be stopped before the microphone is released,
    // and the recognizer closed only after that, or the app crashes
    private fun stopPhraseListening() {
        speech?.let {
            it.cancel()
            it.shutdown()
        }
        speech = null
        phraseRecognizer?.close()
        phraseRecognizer = null
    }

    /**
     * Listens to anything for up to [timeoutMs] (e.g. a guest saying their
     * name) instead of only the phrase, then goes back to the phrase. [onText]
     * gets what was said, or null if nothing was.
     */
    fun captureSpeech(timeoutMs: Long, onText: (String?) -> Unit) {
        val m = model ?: return onText(null)
        capture?.finish(null, deliver = false)
        stopPhraseListening()  // frees the microphone for the capture
        val c = try {
            Capture(m, timeoutMs, onText)
        } catch (e: Exception) {
            Log.e(TAG, "Can't listen for a name", e)
            startPhraseListening()
            return onText(null)
        }
        capture = c
        c.start()
        onStatus("Listening for a name...")
    }

    fun cancelCapture() {
        capture?.finish(null, deliver = false)
    }

    // Full-vocabulary recognition, used only briefly
    private inner class Capture(
        model: Model,
        private val timeoutMs: Long,
        private val onText: (String?) -> Unit,
    ) : RecognitionListener {
        private val recognizer = Recognizer(model, SAMPLE_RATE)
        private val service = SpeechService(recognizer, SAMPLE_RATE)
        private var latest = ""  // words so far, used if time runs out mid-sentence
        private var done = false
        private val timeout = Runnable { finish(latest.ifBlank { null }) }

        fun start() {
            service.startListening(this)
            main.postDelayed(timeout, timeoutMs)
        }

        fun finish(text: String?, deliver: Boolean = true) {
            if (done) return
            done = true
            main.removeCallbacks(timeout)
            service.cancel()    // stop the listening thread first...
            service.shutdown()  // ...then free the microphone...
            recognizer.close()  // ...then the recognizer it was using
            capture = null
            startPhraseListening()
            if (deliver) onText(text)
        }

        override fun onPartialResult(hypothesis: String) {
            latest = JSONObject(hypothesis).optString("partial")
            if (latest.isNotBlank()) onHeard(latest)
        }

        override fun onResult(hypothesis: String) {
            JSONObject(hypothesis).optString("text").takeIf { it.isNotBlank() }?.let { finish(it) }
        }

        override fun onFinalResult(hypothesis: String) = onResult(hypothesis)
        override fun onError(e: Exception) = finish(null)
        override fun onTimeout() = finish(latest.ifBlank { null })
    }

    /** Stops hearing while true, e.g. while the phone itself is screaming. */
    fun setPaused(paused: Boolean) {
        speech?.setPause(paused)
        onStatus(if (paused) "Paused" else "Listening")
    }

    fun stop() {
        cancelCapture()
        stopPhraseListening()
        model?.close()
        model = null
    }

    override fun onPartialResult(hypothesis: String) = check(hypothesis, "partial")
    override fun onResult(hypothesis: String) = check(hypothesis, "text")
    override fun onFinalResult(hypothesis: String) = check(hypothesis, "text")
    override fun onError(e: Exception) = onStatus("Speech error: ${e.message}")
    override fun onTimeout() = onStatus("Speech timeout")

    // Vosk keeps one running transcript per utterance until it hears a pause,
    // and long sounds (like the knocking) can delay that pause. So instead of
    // matching the text, count how many times the phrase appears in the
    // current utterance and trigger only when that number goes up.
    private fun check(hypothesis: String, key: String) {
        val text = JSONObject(hypothesis).optString(key)
        if (text.isNotBlank()) onHeard(text)  // shown on screen, handy for tuning
        val count = text.windowed(PHRASE.length).count { it == PHRASE }

        if (key == "text") {
            // Utterance finished: count anything the partials hadn't, then start over
            val missed = count > counted
            counted = 0
            pending = 0
            if (missed) trigger()
            return
        }

        if (count <= counted) {
            pending = 0
            return
        }
        // Partial results arrive every 0.2 s; requiring several in a row is
        // stricter but slower
        if (++pending < CONFIRM_PARTIALS) return
        pending = 0
        counted = count
        trigger()
    }

    private fun trigger() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < COOLDOWN_MS) return
        lastTrigger = now
        onPhrase()
    }

    companion object {
        const val PHRASE = "bloody mary"
        private val GRAMMAR = listOf(PHRASE, "bloody", "blood", "mary", "[unk]")

        // 1 = trigger on the first partial result containing the phrase (fastest).
        // Raise to 2 if half-said phrases still trigger (adds about 0.2 s).
        private const val CONFIRM_PARTIALS = 1
        const val COOLDOWN_MS = 2000L  // safety net against a flickering partial result
        private const val TAG = "PhraseListener"
        private const val SAMPLE_RATE = 16000f
    }
}
