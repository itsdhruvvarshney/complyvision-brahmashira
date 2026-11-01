package com.example.complyvision

/**
 * Recognises the Accessibility Mode wake phrase "Khul Ja Sim Sim" in speech-to-text output.
 *
 * The recogniser runs in Hindi, so the phrase usually arrives in Devanagari ("खुल जा सिम सिम"), but Latin
 * spellings and the usual mis-hearings ("khulja simsim", "cool ja sim sim", "full jaa seem seem", "khol ja sim sim")
 * and the English "open sesame" must match too, while ordinary speech that merely contains "sim" must not.
 * Both sides are reduced to a rough phonetic spelling and compared with an approximate-substring edit distance.
 */
object WakePhrase {
    private val TARGETS = listOf("khul ja sim sim", "open sesame").map(::phonetic)
    private val SIM_SIM = phonetic("sim sim")

    fun matchesAny(candidates: List<String>): Boolean = candidates.any(::matches)

    fun matches(text: String): Boolean {
        val s = phonetic(text)
        if (s.length < 6) return false
        val khulja = TARGETS[0]
        // "sim sim" has to be there almost exactly, and the whole phrase within two edits
        if (distanceInside(khulja, s) <= 2 && distanceInside(SIM_SIM, s) <= 1) return true
        return distanceInside(TARGETS[1], s) <= 2
    }

    /** Lower-case Latin letters only, with sound-alike spellings folded together */
    internal fun phonetic(text: String): String {
        val latin = transliterate(text.lowercase())
        val sb = StringBuilder()
        var i = 0
        while (i < latin.length) {
            val c = latin[i]
            val next = latin.getOrNull(i + 1)
            when {
                !c.isLetter() -> {}
                c == 'h' && sb.isNotEmpty() && sb.last() !in "aeiou" -> {}   // kh, gh, jh, th, dh, bh ... -> k, g, j, t, d, b
                c == 'p' && next == 'h' -> { sb.append('f'); i++ }
                c == 'c' && next == 'h' -> { sb.append('c'); i++ }
                c == 'c' || c == 'q' -> sb.append('k')
                c == 'z' -> sb.append('j')
                c == 'w' -> sb.append('v')
                c == 'y' -> sb.append('i')
                c == 'o' -> sb.append('u')
                c == 'e' -> sb.append('i')
                else -> sb.append(c)
            }
            i++
        }
        // collapse doubled letters: "jaa" -> "ja", "seem" -> "sim", "simm" -> "sim"
        val out = StringBuilder()
        for (ch in sb) if (out.isEmpty() || out.last() != ch) out.append(ch)
        return out.toString()
    }

    /** Smallest edit distance between [pattern] and any substring of [text] (Sellers' algorithm) */
    internal fun distanceInside(pattern: String, text: String): Int {
        var prev = IntArray(text.length + 1)            // row 0: the match may start anywhere in the text
        for (i in 1..pattern.length) {
            val cur = IntArray(text.length + 1)
            cur[0] = i
            for (j in 1..text.length) {
                val cost = if (pattern[i - 1] == text[j - 1]) 0 else 1
                cur[j] = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev.min()
    }

    // ------------------------------------------------------------------ Devanagari -> Latin
    // Hindi drops the inherent "a" at the end of words and in compounds like "खुलजा", so consonants are
    // written without it; vowel signs supply the vowels that are actually pronounced.
    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j",
        'झ' to "jh", 'ञ' to "n", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t",
        'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh",
        'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s",
        'ह' to "h", 'ळ' to "l"
    )
    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri", 'ए' to "e",
        'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri", 'े' to "e", 'ै' to "ai",
        'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e", 'ं' to "n", 'ँ' to "n", 'ः' to "h"
    )

    private fun transliterate(text: String): String {
        if (text.none { it in 'ऀ'..'ॿ' }) return text
        val sb = StringBuilder()
        for (ch in text) {
            when {
                ch == '्' || ch == '़' -> {}
                CONSONANTS.containsKey(ch) -> sb.append(CONSONANTS.getValue(ch))
                VOWELS.containsKey(ch) -> sb.append(VOWELS.getValue(ch))
                ch in 'ऀ'..'ॿ' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
