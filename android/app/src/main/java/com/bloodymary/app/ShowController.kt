package com.bloodymary.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The show. When a face appears while idle, Mary greets the guest by name (or
 * generically if unknown). Then each "bloody mary" calls [advance]:
 *   1. candles off (ESP32 IR burst)
 *   2. break-in sounds
 *   3. floodlight on (ESP32) + scream, then a personalized line
 * All audio plays from the phone on the media stream, which goes to the
 * Bluetooth amp when it's connected.
 *
 * Nobody should have to touch the phone. The show resets itself [IDLE_RESET_MS]
 * after the last thing it did (a trigger, or the end of the finale), and a face
 * that appears after the previous guest has left is greeted afresh.
 */
class ShowController(
    private val context: Context,
    private val esp: Esp32Client,
    private val voice: VoiceBank,
    private val knownGuests: () -> List<String>,
    private val listener: () -> PhraseListener?,
    private val onChange: (String) -> Unit,
) {
    var stage = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private val idleReset = Runnable { reset(manual = false) }

    // Loaded ahead of time so they start the instant they're triggered
    private val breakIn = AssetSound(context, listOf("breakin.mp3", "breakin.wav"))
    private val scream = AssetSound(context, listOf("scream.mp3", "scream.wav"))

    // Greeting: watch a new face for GREET_WINDOW_MS, keeping the best match
    private var guest: String? = null
    private var greeted = false
    private var windowStart = 0L
    private var lastSeen = 0L
    private var bestName: String? = null
    private var bestScore = -1f
    // After an automatic reset, someone who stood there the whole show isn't greeted a second time
    private var suppressGreeting = false
    private var visitStart = 0L  // when the face now in view first appeared
    private var showStart = 0L   // when the first "bloody mary" of this show was heard
    private var askToken = 0     // invalidates a pending "SPEAK YOUR NAME" when the visit or show moves on

    init {
        maxVolume()
    }

    fun advance() {
        stopAskingName()
        if (stage == 0) showStart = SystemClock.elapsedRealtime()
        // The scream and finale can run long, so the countdown starts when they end
        val startingScream = stage == 2
        if (startingScream) main.removeCallbacks(idleReset)
        when (stage) {
            0 -> esp.send("candles_off")
            1 -> {
                voice.stop()
                if (!breakIn.available) onChange("${describe()}\n(no breakin.mp3 in assets)")
                breakIn.play()
            }
            2 -> {
                esp.send("light_on")
                voice.stop()
                breakIn.stop()
                playScream()
            }
            else -> return  // show is over until reset
        }
        stage++
        if (!startingScream) scheduleIdleReset()
        onChange(describe())
    }

    private fun scheduleIdleReset() {
        main.removeCallbacks(idleReset)
        main.postDelayed(idleReset, IDLE_RESET_MS)
    }

    /**
     * Back to the start. A [manual] reset (the button) greets whoever is in view
     * again right away, for rehearsals; the automatic one doesn't re-greet
     * someone who has been standing there since the show began (but does greet
     * a guest who walked in partway through).
     */
    fun reset(manual: Boolean = true) {
        main.removeCallbacks(idleReset)
        val faceInView = lastSeen != 0L && SystemClock.elapsedRealtime() - lastSeen < FACE_GAP_MS
        suppressGreeting = !manual && faceInView && visitStart <= showStart
        stopAskingName()
        stopScream()
        breakIn.stop()
        voice.stop()
        maxVolume()
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

        // A face after a gap is a new visit: the last guest has left
        val newVisit = lastSeen == 0L || now - lastSeen > FACE_GAP_MS
        lastSeen = now
        if (newVisit) {
            visitStart = now
            suppressGreeting = false
            if (stage == 0) {  // nobody is mid-show, so start fresh, even if the last guest was never "bloody mary"-ing
                stopAskingName()
                greeted = false
                guest = null
                windowStart = 0
            }
        }
        if (sighting.name != null && guest == null) guest = sighting.name  // remember for the finale

        if (stage != 0 || greeted || suppressGreeting) return

        // Watch the face for a moment first, keeping the best match
        if (windowStart == 0L) {
            windowStart = now
            bestName = null
            bestScore = -1f
        }
        if (sighting.name != null && sighting.score > bestScore) {
            bestName = sighting.name
            bestScore = sighting.score
        }
        if (now - windowStart < GREET_WINDOW_MS) return

        greeted = true
        guest = bestName
        if (bestName != null) voice.play(VoiceBank.Line.GREETING, bestName) else askName()
        onChange(describe())
    }

    /** For rehearsals: run the unknown-guest greeting now, as if a stranger walked in. */
    fun rehearseUnknownGuest() {
        reset()
        greeted = true
        guest = null
        askName()
        onChange(describe())
    }

    // Someone Mary doesn't recognize: demand their name, listen, then answer with it
    private fun askName() {
        val token = ++askToken
        voice.play(VoiceBank.Line.ASK_NAME, null) {
            // Bluetooth audio lags behind, so give her voice time to finish coming
            // out of the speaker before listening, or she'd hear herself
            main.postDelayed({
                if (token != askToken || stage != 0) return@postDelayed
                val ears = listener() ?: return@postDelayed onNameHeard(token, null)
                ears.captureSpeech(NAME_LISTEN_MS) { said -> onNameHeard(token, said) }
            }, 800)
        }
    }

    private fun onNameHeard(token: Int, said: String?) {
        if (token != askToken || stage != 0) return
        if (said != null && said.contains(PhraseListener.PHRASE)) {  // skipped straight to her name
            advance()
            return
        }
        val name = said?.let(NameParser::extractName)
        if (name == null) {
            voice.play(VoiceBank.Line.NO_NAME, null)
            return
        }

        // A guest on the list that the camera missed (it's dark): use their own lines
        val known = NameParser.matchGuest(name, knownGuests().associateWith { voice.pronunciation(it) })
        guest = known ?: name
        onChange(describe())
        if (known != null) {
            voice.play(VoiceBank.Line.GREETING, known)
            return
        }

        // A new name: a short filler while her greeting with that name is made
        var fillerDone = false
        var ready: Boolean? = null
        var spoke = false
        fun speak() {
            if (token != askToken || stage != 0 || spoke || !fillerDone) return
            val ok = ready ?: return
            spoke = true
            if (ok) voice.play(VoiceBank.Line.GREETING, name) else voice.play(VoiceBank.Line.OFFER, null)
        }
        voice.prepare(VoiceBank.Line.GREETING, name) { ok -> ready = ok; speak() }
        voice.prepare(VoiceBank.Line.FINALE, name) {}  // ready for after the scream
        voice.play(VoiceBank.Line.NAME_HEARD, null) {
            fillerDone = true
            speak()
            // No internet or too slow: carry on without the name
            main.postDelayed({ if (ready == null) { ready = false; speak() } }, LIVE_WAIT_MS)
        }
    }

    private fun stopAskingName() {
        askToken++
        listener()?.cancelCapture()
    }

    fun describe(): String = when (stage) {
        0 -> if (greeted) "Greeted. Waiting for 1st \"bloody mary\"" else "Waiting for a guest"
        1 -> "Candles out. Waiting for 2nd"
        2 -> "Break-in playing. Waiting for 3rd"
        else -> "Scream! Resets in 1 min"
    } + "\nGuest: ${guest ?: if (greeted) "unknown" else "-"}" +
        if (stage in 1..2) "\n(resets after 1 min idle)" else ""

    private fun playScream() {
        // Don't let the scream (or the guest's reaction) count as another trigger
        listener()?.setPaused(true)
        maxVolume()
        if (!scream.available) onChange("${describe()}\n(no scream file in assets)")
        scream.play { finishScream() }
    }

    private fun finishScream() {
        listener()?.setPaused(false)
        scheduleIdleReset()  // safety net, in case the finale never reports back
        voice.play(VoiceBank.Line.FINALE, guest) { scheduleIdleReset() }
    }

    private fun stopScream() {
        if (!scream.playing) return
        scream.stop()
        listener()?.setPaused(false)
    }

    // Media volume at max; the amp's knob sets the actual loudness
    private fun maxVolume() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
    }

    companion object {
        private const val TAG = "ShowController"
        private const val IDLE_RESET_MS = 60_000L
        private const val GREET_WINDOW_MS = 1_200L  // look at a new face this long before greeting
        private const val FACE_GAP_MS = 8_000L      // no face for this long = the guest has left
        private const val NAME_LISTEN_MS = 6_000L   // how long to wait for a name
        private const val LIVE_WAIT_MS = 7_000L     // how long to wait for a line with a new name
    }
}
