package com.bloodymary.app

/** Picks a first name out of what a guest said ("it's Sarah", "um, Mike", "my name is Jess"). */
object NameParser {

    private val INTRO = Regex("""(?:my name is|my name's|name is|they call me|call me|i am|i'm|im|it is|it's|its|this is)\s+([a-z']+)""")

    // Words that aren't names, even when they're the only thing said. Includes
    // the words of Mary's own question, in case the end of it is overheard
    private val NOT_NAMES = setOf(
        "a", "am", "and", "blood", "bloody", "er", "hello", "hey", "hi", "hmm", "huh", "i", "is", "it",
        "mary", "my", "name", "no", "nope", "nothing", "oh", "ok", "okay", "so", "the", "uh", "um", "well",
        "what", "who", "why", "yeah", "yes",
        "child", "dares", "don't", "face", "into", "know", "look", "mirror", "speak", "your",
    )

    fun extractName(said: String): String? {
        val text = said.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        val word = INTRO.find(text)?.groupValues?.get(1)
            ?: text.split(" ").lastOrNull { it !in NOT_NAMES }
            ?: return null
        if (word in NOT_NAMES || word.length < 2) return null
        return word.replaceFirstChar { it.uppercase() }
    }

    /** The guest in [guests] whose name (or pronunciation spelling) sounds like [name], if any. */
    fun matchGuest(name: String, guests: Map<String, String?>): String? {
        val heard = simplify(name)
        return guests.entries.firstOrNull { (guest, say) ->
            listOfNotNull(guest, say).any { close(heard, simplify(it)) }
        }?.key
    }

    private fun simplify(name: String) = name.lowercase().filter { it in 'a'..'z' }

    // Speech recognition gets names slightly wrong, so allow one letter off for longer names
    private fun close(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 4 && editDistance(a, b) <= 1)

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
