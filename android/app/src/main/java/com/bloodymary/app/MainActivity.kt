package com.bloodymary.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private lateinit var stageText: TextView
    private lateinit var listenText: TextView
    private lateinit var heardText: TextView
    private lateinit var faceText: TextView
    private lateinit var espText: TextView
    private lateinit var addressInput: EditText

    private lateinit var esp: Esp32Client
    private lateinit var voice: VoiceBank
    private lateinit var show: ShowController
    private lateinit var guests: GuestStore
    private lateinit var recognizer: FaceRecognizer
    private lateinit var watcher: FaceWatcher
    private var listener: PhraseListener? = null

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) startListening()
            else listenText.text = "Microphone permission is required"
            if (granted[Manifest.permission.CAMERA] == true) startCamera()
            else faceText.text = "Camera permission is required for greetings"
        }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        // Keep access, so the folder is re-checked automatically on every launch
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
        }
        prefs.edit().putString(KEY_PHOTO_FOLDER, uri.toString()).remove(KEY_FOLDER_SIGNATURE).apply()
        importFolder(uri, interactive = true)
    }
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Newer Android draws apps behind the status and navigation bars; keep the buttons clear of them
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // The phone is hidden in the bathroom for the whole show
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        stageText = findViewById(R.id.stageText)
        listenText = findViewById(R.id.listenText)
        heardText = findViewById(R.id.heardText)
        faceText = findViewById(R.id.faceText)
        espText = findViewById(R.id.espText)
        addressInput = findViewById(R.id.addressInput)

        addressInput.setText(prefs.getString(KEY_ADDRESS, DEFAULT_ADDRESS))
        esp = Esp32Client(prefs) { addressInput.text.toString() }
        voice = VoiceBank(this, prefs)
        guests = GuestStore(this)
        recognizer = FaceRecognizer(this)
        show = ShowController(this, esp, voice, { guests.names }, { listener }) { stageText.text = it }
        watcher = FaceWatcher(this, findViewById<PreviewView>(R.id.preview), recognizer, guests) { s ->
            faceText.text = "Sees: ${s.name ?: "unknown"} (match %.2f)".format(s.score)
            show.onFace(s)
        }
        stageText.text = show.describe()
        faceText.text = if (recognizer.ready) "Guests: ${guests.names.size}"
        else "Face model missing (assets/${FaceRecognizer.MODEL_ASSET})"

        findViewById<Button>(R.id.resetButton).setOnClickListener { saveAddress(); show.reset() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { saveAddress(); show.advance() }
        // Long-press: rehearse the "SPEAK YOUR NAME, CHILD" greeting without needing a stranger
        findViewById<Button>(R.id.nextButton).setOnLongClickListener {
            show.rehearseUnknownGuest()
            true
        }
        findViewById<Button>(R.id.addGuestButton).setOnClickListener { addGuest() }
        findViewById<Button>(R.id.importButton).setOnClickListener { pickFolder.launch(null) }
        findViewById<Button>(R.id.guestsButton).setOnClickListener { showGuests() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.switchCameraButton).setOnClickListener {
            prefs.edit().putBoolean(KEY_FRONT_CAMERA, !prefs.getBoolean(KEY_FRONT_CAMERA, false)).apply()
            startCamera()
        }
        findViewById<Button>(R.id.previewButton).setOnClickListener {
            // Recognition keeps running either way; hiding just turns off the on-screen picture
            prefs.edit().putBoolean(KEY_SHOW_PREVIEW, !prefs.getBoolean(KEY_SHOW_PREVIEW, true)).apply()
            startCamera()
        }

        val needed = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            startListening()
            startCamera()
        } else {
            requestPermissions.launch(needed)
        }

        // Fill in any voice lines that are missing (already generated ones cost nothing)
        voice.generate(guests.names + null)
    }

    private fun startListening() {
        listener = PhraseListener(
            this,
            onPhrase = { show.advance() },
            onStatus = { listenText.text = "Mic: $it" },
            onHeard = { heardText.text = "Heard: \"$it\"" },
        ).also { it.start() }
    }

    private fun startCamera() {
        val showPreview = prefs.getBoolean(KEY_SHOW_PREVIEW, true)
        findViewById<PreviewView>(R.id.preview).visibility = if (showPreview) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.previewButton).text = if (showPreview) "Hide camera" else "Show camera"
        val selfie = prefs.getBoolean(KEY_FRONT_CAMERA, false)
        // The button shows the camera in use; tap to switch to the other one
        findViewById<Button>(R.id.switchCameraButton).text = if (selfie) "Front camera" else "Rear camera"
        watcher.start(selfie, showPreview)
    }

    // Live enrollment: type a name, then the camera takes a few samples of the face
    private fun addGuest() {
        val nameInput = EditText(this).apply {
            hint = "Guest's name"
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle("Add guest")
            .setMessage("Have them look at the camera, then press Capture.")
            .setView(nameInput)
            .setPositiveButton("Capture") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                toast("Hold still, $name...")
                var finished = false
                watcher.captureFaces(CAPTURE_SAMPLES) { faces ->
                    finished = true
                    guests.add(name, faces)
                    faceText.text = "Added $name. Guests: ${guests.names.size}"
                    voice.generate(listOf(name)) { error -> toast(error ?: "$name's voice lines are ready") }
                }
                main.postDelayed({
                    if (!finished) {
                        watcher.cancelCapture()
                        toast("No face seen. Try again with more light.")
                    }
                }, CAPTURE_TIMEOUT_MS)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Re-imports the remembered photo folder if anything in it changed since the last import. */
    private fun refreshFolder() {
        val tree = prefs.getString(KEY_PHOTO_FOLDER, null)?.let(Uri::parse) ?: return
        if (importing || contentResolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission }) return
        thread {
            val signature = try {
                folderSignature(tree)
            } catch (e: Exception) {
                return@thread
            }
            if (signature == prefs.getString(KEY_FOLDER_SIGNATURE, null)) return@thread
            main.post { if (!importing) importFolder(tree, interactive = false) }
        }
    }

    // Names, sizes and dates of everything in the folder, to notice changes
    private fun folderSignature(tree: Uri): String {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val entries = mutableListOf<String>()
        contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) entries.add("${c.getString(0)}|${c.getLong(1)}|${c.getLong(2)}")
        }
        return entries.sorted().joinToString("\n")
    }

    // Photo import: every image in the folder is a guest, named by its file
    // ("Sarah.jpg", "Sarah_2.jpg" and "Sarah (3).jpg" are all Sarah).
    // [interactive] = picked just now, so show a full summary.
    private fun importFolder(tree: Uri, interactive: Boolean) {
        importing = true
        toast(if (interactive) "Importing photos..." else "Photo folder changed, updating guests...")
        thread {
            val signature = try {
                folderSignature(tree)
            } catch (e: Exception) {
                null
            }
            val faces = mutableMapOf<String, MutableList<FloatArray>>()
            val noFace = mutableListOf<String>()
            var linesNote = ""
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val fileName = c.getString(1)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    // Personal voice lines for guests, next to their photos
                    if (fileName.equals("lines.json", ignoreCase = true)) {
                        linesNote = try {
                            voice.setPersonalLines(contentResolver.openInputStream(uri)!!.bufferedReader().readText())
                            "\n\nPersonal lines for: ${voice.personalGuests.joinToString()}"
                        } catch (e: Exception) {
                            "\n\nlines.json has a problem: ${e.message}"
                        }
                        continue
                    }
                    if (c.getString(2)?.startsWith("image/") != true) continue
                    val embedding = try {
                        recognizer.embedLargestFace(decode(uri))
                    } catch (e: Exception) {
                        null
                    }
                    if (embedding == null) noFace.add(fileName)
                    else faces.getOrPut(guestName(fileName)) { mutableListOf() }.add(embedding)
                }
            }
            faces.forEach { (name, list) -> guests.set(name, list) }
            // A photo that was renamed or deleted removes its old guest. Only guests that
            // came from the folder are touched, never ones added live with "Add guest"
            if (faces.isNotEmpty()) {
                val before = prefs.getStringSet(KEY_FOLDER_GUESTS, emptySet()).orEmpty()
                (before - faces.keys).forEach { guests.remove(it) }
                prefs.edit().putStringSet(KEY_FOLDER_GUESTS, faces.keys.toSet()).apply()
            }
            if (signature != null) prefs.edit().putString(KEY_FOLDER_SIGNATURE, signature).apply()

            main.post {
                importing = false
                faceText.text = "Imported ${faces.size} guests. Guests: ${guests.names.size}"
                val message = "Imported: ${faces.keys.joinToString().ifEmpty { "nobody" }}" +
                    (if (noFace.isNotEmpty()) "\n\nNo face found in: ${noFace.joinToString()}" else "") +
                    linesNote
                if (interactive || noFace.isNotEmpty() || linesNote.contains("problem")) {
                    AlertDialog.Builder(this).setTitle("Photo import").setMessage(message)
                        .setPositiveButton("OK", null).show()
                } else {
                    toast("Guests updated: ${guests.names.size} total")
                }
                voice.generate(guests.names) { error -> toast(error ?: "Voice lines are ready") }
            }
        }
    }

    private fun showGuests() {
        val names = guests.names
        AlertDialog.Builder(this)
            .setTitle("Guests (${names.size})")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                AlertDialog.Builder(this)
                    .setMessage("Remove $name?")
                    .setPositiveButton("Remove") { _, _ -> guests.remove(name) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNeutralButton("Generate voices") { _, _ ->
                toast("Generating voice lines...")
                voice.generate(guests.names + null) { error -> toast(error ?: "Voice lines are ready") }
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val apiKey = EditText(this).apply {
            hint = "ElevenLabs API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(VoiceBank.KEY_API_KEY, ""))
        }
        val voiceId = EditText(this).apply {
            hint = "ElevenLabs voice ID"
            setText(prefs.getString(VoiceBank.KEY_VOICE_ID, ""))
        }
        val model = EditText(this).apply {
            hint = "Model (default ${VoiceBank.DEFAULT_MODEL})"
            setText(prefs.getString(VoiceBank.KEY_MODEL, VoiceBank.DEFAULT_MODEL))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            listOf(apiKey, voiceId, model).forEach { addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(VoiceBank.KEY_API_KEY, apiKey.text.toString().trim())
                    .putString(VoiceBank.KEY_VOICE_ID, voiceId.text.toString().trim())
                    .putString(VoiceBank.KEY_MODEL, model.text.toString().trim())
                    .apply()
                startCamera()
                toast("Generating voice lines...")
                voice.generate(guests.names + null) { error -> toast(error ?: "Voice lines are ready") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        main.post(pollStatus)
        refreshFolder()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(pollStatus)
        saveAddress()
    }

    override fun onDestroy() {
        listener?.stop()
        voice.shutdown()
        recognizer.close()
        super.onDestroy()
    }

    private val pollStatus: Runnable = object : Runnable {
        override fun run() {
            esp.get("/status", attempts = 1) { body -> espText.text = describeStatus(body) + "\n" + describeAmp() }
            main.postDelayed(this, STATUS_POLL_MS)
        }
    }

    private fun describeStatus(body: String?): String {
        if (body == null) return "ESP32: not reachable"
        return try {
            val s = JSONObject(body)
            "ESP32: ${s.optString("ip")}" + (if (s.optBoolean("light")) "  light on" else "")
        } catch (e: Exception) {
            "ESP32: bad status"
        }
    }

    // All show audio goes to the Bluetooth amp when the phone is connected to it
    private fun describeAmp(): String {
        val amp = getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        return if (amp != null) "Amp: ${amp.productName} connected"
        else "Amp: NOT connected (sound plays on the phone)"
    }

    private fun saveAddress() {
        prefs.edit().putString(KEY_ADDRESS, addressInput.text.toString().trim()).apply()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_ADDRESS = "esp_address"
        private const val KEY_FRONT_CAMERA = "use_selfie_camera"  // default: main (back) camera
        private const val KEY_SHOW_PREVIEW = "show_camera_preview"
        private const val KEY_PHOTO_FOLDER = "photo_folder"
        private const val KEY_FOLDER_SIGNATURE = "photo_folder_signature"
        private const val KEY_FOLDER_GUESTS = "photo_folder_guests"
        private const val DEFAULT_ADDRESS = "bloodymary.local"
        private const val STATUS_POLL_MS = 3000L
        private const val CAPTURE_SAMPLES = 5
        private const val CAPTURE_TIMEOUT_MS = 10_000L

        /** "Sarah_2.jpg" / "Sarah (3).jpg" / "Mary_Jane.png" -> "Sarah" / "Sarah" / "Mary Jane" */
        fun guestName(fileName: String): String =
            fileName.substringBeforeLast('.')
                .replace(Regex("[ _-]*\\(?\\d+\\)?$"), "")
                .replace('_', ' ')
                .trim()

        /** Decodes a photo upright (EXIF rotation applied), at most ~1280 px, readable pixels. */
        private fun decodeOptions(decoder: ImageDecoder, info: ImageDecoder.ImageInfo) {
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > 1280) decoder.setTargetSampleSize(longest / 1280)
        }
    }

    private fun decode(uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decodeOptions(decoder, info)
        }
}
