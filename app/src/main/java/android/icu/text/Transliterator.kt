package android.icu.text

import java.text.Normalizer

/**
 * Desktop shim for android.icu.text.Transliterator covering the four transforms the lyrics
 * script converter asks for: "Devanagari-Latin", "Latin-Devanagari", "Latin-ASCII",
 * "Arabic-Latin; Latin-ASCII".
 *
 * shortcut: hand-rolled character-map transliteration, not ICU-grade; good enough for song lyrics
 * (vowels and aspirates come out right, conjuncts map letter-by-letter) — upgrade by bundling ICU4J.
 */
object TransliteratorShim {

    private val diacriticsRegex = Regex("\\p{M}+")

    private val devaLetters = mapOf(
        'क' to "ka", 'ख' to "kha", 'ग' to "ga", 'घ' to "gha", 'ङ' to "\u1e45a",
        'च' to "ca", 'छ' to "cha", 'ज' to "ja", 'झ' to "jha", 'ञ' to "\u00f1a",
        'ट' to "\u1e6da", 'ठ' to "\u1e6dha", 'ड' to "\u1e0da", 'ढ' to "\u1e0dha", 'ण' to "\u1e47a",
        'त' to "ta", 'थ' to "tha", 'द' to "da", 'ध' to "dha", 'न' to "na",
        'प' to "pa", 'फ' to "pha", 'ब' to "ba", 'भ' to "bha", 'म' to "ma",
        'य' to "ya", 'र' to "ra", 'ऱ' to "\u1e5ba", 'ल' to "la", 'ळ' to "\u1e37a", 'व' to "va",
        'श' to "\u015ba", 'ष' to "\u1e63a", 'स' to "sa", 'ह' to "ha",
        'ा' to "\u0101", 'ि' to "i", 'ी' to "\u012b", 'ु' to "u", 'ू' to "\u016b", 'ृ' to "r\u0325",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", '्' to "",
        'ं' to "n", 'ँ' to "m", 'ः' to "h", 'ॅ' to "",
        '।' to ".", '॥' to "..", '\u200c' to "", '\u200d' to "",
        '०' to "0", '१' to "1", '२' to "2", '३' to "3", '४' to "4",
        '५' to "5", '६' to "6", '७' to "7", '८' to "8", '९' to "9",
    )

    private val devaIndependentVowels = mapOf(
        'अ' to "a", 'आ' to "\u0101", 'इ' to "i", 'ई' to "\u012b", 'उ' to "u", 'ऊ' to "\u016b",
        'ऋ' to "r\u0325", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
    )

    private fun isDevaChar(c: Char): Boolean = c.code in 0x0900..0x097F

    private fun devanagariToLatin(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!isDevaChar(c)) { out.append(c); i++; continue }
            if (c == '्') { // virama: drop the inherent 'a' of the previous consonant
                if (out.endsWith("a")) out.setLength(out.length - 1)
                i++
                continue
            }
            // ipvowel/letter
            val m = devaIndependentVowels[c] ?: devaLetters[c]
            out.append(m ?: c.toString())
            i++
        }
        return out.toString()
    }

    /** Latin -> Devanagari offline fallback: syllable-wise, no conjuncts. Used when Google Input Tools is unreachable. */
    private val latinToDevaConsonants = listOf(
        // long consonant maps first so "chh" wins over "ch"
        "chh" to 'छ', "ch" to 'च',
        "kh" to 'ख', "gh" to 'घ', "jh" to 'झ',
        "th" to 'थ', "dh" to 'ध', "ph" to 'फ', "bh" to 'भ', "sh" to 'श',
        "k" to 'क', "g" to 'ग', "j" to 'ज', "n" to 'न', "t" to 'त', "d" to 'द',
        "p" to 'प', "b" to 'ब', "m" to 'म', "y" to 'य', "r" to 'र', "l" to 'ल',
        "v" to 'व', "w" to 'व', "s" to 'स', "h" to 'ह', "f" to 'फ', "z" to 'ज',
    )

    private val latinToDevaVowels: List<Pair<String, Pair<Char?, Char>>> = listOf(
        "aa" to ('ा' to 'आ'), "ai" to ('ै' to 'ऐ'), "au" to ('ौ' to 'औ'),
        "ii" to ('ी' to 'ई'), "ee" to ('ी' to 'ई'),
        "uu" to ('ू' to 'ऊ'), "oo" to ('ो' to 'ओ'),
        "i" to ('ि' to 'इ'), "u" to ('ु' to 'उ'),
        "e" to ('े' to 'ए'), "o" to ('ो' to 'ओ'),
        "a" to (null to 'अ'),
    )

    private fun latinToDevanagari(text: String): String =
        text.split(Regex("(\\s+)")).joinToString("") { part ->
            if (part.isNotEmpty() && part.all { it in 'a'..'z' || it in 'A'..'Z' }) wordToDeva(part) else part
        }

    private fun wordToDeva(word: String): String {
        val w = word.lowercase()
        val out = StringBuilder()
        var i = 0
        var consonantPending = false
        while (i < w.length) {
            val cons = latinToDevaConsonants.firstOrNull { w.startsWith(it.first, i) }
            if (cons != null) {
                if (consonantPending) out.append('्')
                out.append(cons.second)
                consonantPending = true
                i += cons.first.length
                continue
            }
            val vowel = latinToDevaVowels.firstOrNull { w.startsWith(it.first, i) }
            if (vowel != null) {
                val (matra, letter) = vowel.second
                when {
                    consonantPending && matra != null -> out.append(matra) // previous consonant syllable ends here
                    consonantPending -> Unit // bare "a": the consonant's inherent vowel already reads it
                    else -> out.append(letter)
                }
                consonantPending = false
                i += vowel.first.length
                continue
            }
            // anything else: end any pending syllable and pass through
            if (consonantPending) { out.append('्'); consonantPending = false }
            out.append(w[i])
            i++
        }
        return out.toString()
    }

    private val arabicToLatinMap = mapOf(
        'ٲ' to "a", 'ب' to "b", 'ٹ' to "ṭ", 'ت' to "t", 'ث' to "s", 'ج' to "j",
        'ح' to "h", 'خ' to "kh", 'د' to "d", 'ڈ' to "ḍ", 'ذ' to "z", 'ر' to "r",
        'ڑ' to "ṛ", 'ز' to "z", 'ژ' to "zh", 'س' to "s", 'ش' to "sh", 'ص' to "s",
        'ض' to "z", 'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh", 'ف' to "f",
        'ق' to "q", 'ک' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m", 'ن' to "n",
        'ں' to "n", 'و' to "w", 'ہ' to "h", 'ھ' to "h", 'ء' to "", 'ی' to "y",
        'ے' to "e", 'ے' to "e",
        'َ' to "a", 'ِ' to "i", 'ُ' to "u", 'ْ' to "", 'ٰ' to "a", 'ّ' to "",
        '\u0660' to "0", '\u0661' to "1", '\u0662' to "2", '\u0663' to "3", '\u0664' to "4",
        '\u0665' to "5", '\u0666' to "6", '\u0667' to "7", '\u0668' to "8", '\u0669' to "9",
        'آ' to "ā", 'ا' to "a", 'أ' to "a", 'إ' to "i", 'ؤ' to "w", 'ئ' to "y",
    )

    private fun arabicToLatin(text: String): String =
        text.map { arabicToLatinMap[it] ?: it.toString() }.joinToString("")

    private fun latinToAscii(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(diacriticsRegex, "")

    fun transliterate(transform: String, text: String): String {
        var t = transform
        var result = text
        // transforms are identified by their first occurrence, applied in string order
        val ops = listOf(
            Triple("Devanagari-Latin", "Devanagari-Latin", ::devanagariToLatin),
            Triple("Latin-Devanagari", "Latin-Devanagari", ::latinToDevanagari),
            Triple("Arabic-Latin", "Arabic-Latin", ::arabicToLatin),
            Triple("Latin-ASCII", "Latin-ASCII", ::latinToAscii),
        )
        var remaining = t
        while (remaining.isNotBlank()) {
            val hits = ops.filter { remaining.startsWith(it.second) }
            // longest prefix first so "Devanagari-Latin" doesn't match "Latin-"
            val hit = hits.maxByOrNull { it.second.length }
            if (hit == null) {
                // consume one separator char and continue
                remaining = remaining.substring(1)
                continue
            }
            result = hit.third(result)
            remaining = remaining.substring(hit.second.length)
        }
        return result
    }
}

class Transliterator private constructor(private val id: String) {

    fun transliterate(text: String): String = TransliteratorShim.transliterate(id, text)

    companion object {
        @JvmStatic
        fun getInstance(id: String): Transliterator = Transliterator(id)
    }
}
