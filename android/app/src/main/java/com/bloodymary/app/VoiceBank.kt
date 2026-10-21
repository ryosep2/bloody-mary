package com.bloodymary.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
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
 *
 * Guests can have their own personal lines (imported from lines.json next to
 * their photos); everyone else gets the general [VoiceLines].
 */
class VoiceBank(
    private val context: Context,
    private val prefs: SharedPreferences,
) {

    /**
     * GREETING/FINALE are said to a guest by name. A face Mary doesn't recognize
     * hears ASK_NAME; then NAME_HEARD (while a line with their name is made) or
     * NO_NAME if they stay silent. OFFER is the nameless offer, used as a fallback.
     */
    enum class Line { GREETING, FINALE, ASK_NAME, NAME_HEARD, NO_NAME, OFFER }

    private val dir = File(context.filesDir, "voice").apply {
        mkdirs()
        listFiles { f -> f.extension == "pcm" }?.forEach { it.delete() }  // left over from an older version
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val liveExecutor = Executors.newSingleThreadExecutor()  // lines needed right now, never queued behind a batch
    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private val lastVariant = mutableMapOf<Line, Int>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Personal lines per guest, kept in private storage (never in the app or repo)
    private val personalFile = File(context.filesDir, "guest_lines.json")
    @Volatile private var personal: Map<String, Personal> = parsePersonal(
        if (personalFile.exists()) personalFile.readText() else "{}"
    )

    /** [say] is how to spell the guest's name so it's pronounced right; [lines] are their own lines, if any. */
    private class Personal(val say: String?, val lines: Map<Line, List<String>>)

    private fun personalFor(name: String?): Personal? =
        name?.let { n -> personal.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value }

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setPitch(0.5f)
            tts?.setSpeechRate(0.75f)
        }
    }

    private fun personalLines(line: Line, name: String?): List<String>? =
        personalFor(name)?.lines?.get(line)?.takeIf { it.isNotEmpty() }

    // The guest's name, spelled the way it should be said
    private fun spoken(name: String) = personalFor(name)?.say ?: name

    /** Every version of [line] for [name] (null for someone unknown or unnamed). */
    private fun versions(line: Line, name: String?): List<String> = when (line) {
        Line.GREETING -> if (name == null) VoiceLines.OFFER
            else personalLines(line, name) ?: VoiceLines.GREETINGS.map { it.format(spoken(name)) }
        Line.FINALE -> personalLines(line, name)
            ?: VoiceLines.FINALES.map { if (name != null) it.named.format(spoken(name)) else it.unknown }
        Line.ASK_NAME -> VoiceLines.ASK_NAME
        Line.NAME_HEARD -> VoiceLines.NAME_HEARD
        Line.NO_NAME -> VoiceLines.NO_NAME
        Line.OFFER -> VoiceLines.OFFER
    }

    private fun versionCount(line: Line, name: String?): Int = versions(line, name).size

    /** The text of version [variant] of [line] for [name]. */
    fun text(line: Line, name: String?, variant: Int): String = versions(line, name)[variant]

    // What gets generated ahead of time: a guest's greetings and finales, and
    // (for null) everything said to someone Mary doesn't recognize
    private fun linesFor(name: String?): List<Line> =
        if (name != null) listOf(Line.GREETING, Line.FINALE)
        else listOf(Line.ASK_NAME, Line.NAME_HEARD, Line.NO_NAME, Line.OFFER, Line.FINALE)

    /**
     * Replaces the personal settings with [json]:
     * {"Sarah": {"say": "Sah-rah", "greetings": ["..."], "finales": ["..."]}, ...}
     * Everything per guest is optional. Throws if it isn't valid.
     */
    fun setPersonalLines(json: String) {
        val parsed = parsePersonal(json)
        personalFile.writeText(json)
        personal = parsed
    }

    val personalGuests: Set<String> get() = personal.keys

    /** How [name] should be spelled so it's pronounced right, if lines.json says. */
    fun pronunciation(name: String): String? = personalFor(name)?.say

    private fun parsePersonal(json: String): Map<String, Personal> {
        val root = JSONObject(json)
        return root.keys().asSequence().associateWith { name ->
            val guest = root.getJSONObject(name)
            fun list(key: String) = guest.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            Personal(
                guest.optString("say").takeIf { it.isNotBlank() },
                mapOf(Line.GREETING to list("greetings"), Line.FINALE to list("finales")),
            )
        }
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
            // One version at a time for everyone, so if credits run out midway
            // every guest still has at least one line in Mary's real voice
            val rounds = names.maxOfOrNull { name -> linesFor(name).maxOf { versionCount(it, name) } } ?: 0
            for (variant in 0 until rounds) for (name in names) for (line in linesFor(name)) {
                if (variant >= versionCount(line, name)) continue
                val text = text(line, name, variant)
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

    /**
     * Generates one version of [line] for [name] right now (for a name Mary just
     * heard), so [play] can use it. [done] gets true once it's ready.
     */
    fun prepare(line: Line, name: String, done: (Boolean) -> Unit) {
        val key = prefs.getString(KEY_API_KEY, "").orEmpty()
        val voice = prefs.getString(KEY_VOICE_ID, "").orEmpty()
        val model = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        val texts = versions(line, name)
        if (texts.any { fileFor(it, voice, model).exists() }) return done(true)
        if (key.isBlank() || voice.isBlank()) return done(false)
        val text = texts.random()
        liveExecutor.execute {
            val ok = try {
                synthesize(text, key, voice, model, fileFor(text, voice, model))
                true
            } catch (e: Exception) {
                Log.e(TAG, "ElevenLabs failed for \"$text\"", e)
                false
            }
            main.post { done(ok) }
        }
    }

    /**
     * Speaks a random version of [line] for [name], then calls [onDone]. Prefers
     * versions already generated in Mary's voice (falling back to the nameless
     * one if none exist for that name) and never repeats the previous pick.
     */
    fun play(line: Line, name: String?, onDone: () -> Unit = {}) {
        stop()
        val voice = prefs.getString(KEY_VOICE_ID, "").orEmpty()
        val model = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        fun readyFor(n: String?) = versions(line, n).indices.filter { fileFor(text(line, n, it), voice, model).exists() }
        var who = name
        var ready = readyFor(name)
        if (ready.isEmpty() && name != null && readyFor(null).isNotEmpty()) {
            who = null
            ready = readyFor(null)
        }
        val pool = ready.ifEmpty { versions(line, who).indices.toList() }
        val variant = pool.filter { it != lastVariant[line] }.ifEmpty { pool }.random()
        lastVariant[line] = variant

        val text = text(line, who, variant)
        val file = fileFor(text, voice, model)

        if (voice.isNotBlank() && file.exists()) {
            val mp = MediaPlayer()
            try {
                mp.setDataSource(file.path)
                // Media stream: goes to the Bluetooth amp whenever it's connected
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
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
 * What Mary says. Each moment picks one version at random. Add or remove
 * versions freely: each one is generated once per guest, so more versions use
 * more ElevenLabs credits. %s is the guest's name.
 * Don't put the words "bloody mary" in a line, or the phone will trigger itself.
 */
object VoiceLines {
    class Version(val named: String, val unknown: String)

    // On arrival, said to a guest by name. The words are written in blood on the
    // shower behind them, so they turn around to look. %1$s is the guest's name.
    val GREETINGS = listOf(
        "%1\$s... do you want love? Power? Money? Luck? I can give it all... Look behind you, and say my name three times... slowly... release me, %1\$s.",
        "%1\$s, darling... love, power, money, luck... I can give you everything. Look behind you... say my name three times... slowly... and release me, %1\$s.",
        "Ahh, %1\$s... what do you desire? Love? Power? Money? Luck? It's all yours... Look behind you, say my name three times... and release me.",
    )

    // A face Mary doesn't recognize: she demands a name, then listens
    val ASK_NAME = listOf(
        "SPEAK YOUR NAME, CHILD!",
        "Who dares look into my mirror? SPEAK YOUR NAME, CHILD!",
        "I don't know your face... SPEAK YOUR NAME, CHILD!",
    )

    // Said right after hearing a name, while the greeting with that name is made
    val NAME_HEARD = listOf(
        "Mmm... what a delicious name...",
        "Ahh... I will remember that name...",
    )

    // They wouldn't say their name
    val NO_NAME = listOf(
        "Silence? Very well... Love, power, money, luck... I can give it all. Look behind you, say my name three times... and release me.",
        "You won't tell me? No matter... Anything you desire is yours. Look behind you, say my name three times... and set me free.",
    )

    // The offer without a name, if a greeting with their name couldn't be made in time
    val OFFER = listOf(
        "Love, power, money, luck... I can give it all. Look behind you, say my name three times... and release me.",
    )

    // After the scream: the deal was a lie
    val FINALES = listOf(
        Version(
            "%s... I'm going to peel off your pretty face... and wear it.",
            "I'm going to peel off your pretty face... and wear it.",
        ),
        Version(
            "Riches? Love? Power? Oh, %s... the only thing you'll get from me... is your face, peeled off and hanging on my wall.",
            "Riches? Love? Power? Oh, darling... the only thing you'll get from me... is your face, peeled off and hanging on my wall.",
        ),
        Version(
            "You fool, %s... I promised you everything. And now... I'm taking everything. Starting with your eyes.",
            "You fool... I promised you everything. And now... I'm taking everything. Starting with your eyes.",
        ),
    )
}
