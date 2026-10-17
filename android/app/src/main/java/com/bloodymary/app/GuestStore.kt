package com.bloodymary.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Known guests: each name has one or more face embeddings (from photos or live
 * captures). Saved in the app's private storage, so it survives restarts and
 * never leaves the phone.
 */
class GuestStore(context: Context) {

    private val file = File(context.filesDir, "guests.json")
    private val guests = linkedMapOf<String, MutableList<FloatArray>>()

    init {
        load()
    }

    // Used from the camera, import and UI threads
    val names: List<String> @Synchronized get() = guests.keys.toList()

    /** Replaces [name]'s faces (used by photo import, so re-importing doesn't duplicate). */
    @Synchronized
    fun set(name: String, embeddings: List<FloatArray>) {
        guests[name] = embeddings.toMutableList()
        save()
    }

    /** Adds more faces for [name] (used by live capture). */
    @Synchronized
    fun add(name: String, embeddings: List<FloatArray>) {
        guests.getOrPut(name) { mutableListOf() }.addAll(embeddings)
        save()
    }

    @Synchronized
    fun remove(name: String) {
        guests.remove(name)
        save()
    }

    /**
     * Returns the best-matching guest and the similarity (cosine, -1..1), or a
     * null name when nobody is similar enough.
     */
    @Synchronized
    fun match(embedding: FloatArray): Pair<String?, Float> {
        var bestName: String? = null
        var best = -1f
        for ((name, faces) in guests) {
            for (face in faces) {
                val score = FaceRecognizer.similarity(embedding, face)
                if (score > best) {
                    best = score
                    bestName = name
                }
            }
        }
        return (if (best >= MATCH_THRESHOLD) bestName else null) to best
    }

    private fun load() {
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        for (name in json.keys()) {
            val faces = json.getJSONArray(name)
            guests[name] = MutableList(faces.length()) { i ->
                val arr = faces.getJSONArray(i)
                FloatArray(arr.length()) { j -> arr.getDouble(j).toFloat() }
            }
        }
    }

    private fun save() {
        val json = JSONObject()
        for ((name, faces) in guests) {
            json.put(name, JSONArray(faces.map { face -> JSONArray(face.map { it.toDouble() }) }))
        }
        file.writeText(json.toString())
    }

    companion object {
        // Similarity needed to call someone by name. Higher = fewer wrong names
        // but more "unknown" greetings. The app shows live scores for tuning.
        const val MATCH_THRESHOLD = 0.5f
    }
}
