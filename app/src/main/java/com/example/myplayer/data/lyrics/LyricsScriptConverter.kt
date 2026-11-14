package com.example.myplayer.data.lyrics

import android.icu.text.Transliterator
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * The four ways the lyrics sheet can show the same song:
 *  - [ORIGINAL]  whatever script the lyrics provider returned
 *  - [HINGLISH]  Roman letters (chat-style Hindi / Urdu: "tum hi ho")
 *  - [HINDI]     Devanagari (हिन्दी)
 *  - [URDU]      Nastaliq / Perso-Arabic (اردو)
 *
 * Uses Unicode escape sequences for non-ASCII script labels and IAST diacritics so
 * Windows file encoding (CP1252 vs UTF-8) can never corrupt them into Mojibake.
 */
enum class LyricsScript(val label: String) {
    ORIGINAL("Original"),
    HINGLISH("Hinglish"),
    HINDI("\u0939\u093F\u0928\u094D\u0926\u0940"), // हिन्दी
    URDU("\u0627\u0631\u062F\u0648")                 // اردو
}

/**
 * Converts lyric lines between Devanagari, Urdu and Roman Hinglish.
 *
 * Strategy:
 *  - Script detection is per-character Unicode block counting, synchronous and free.
 *  - Devanagari -> Latin and Urdu -> Latin: offline via [Transliterator] + Hinglish clean-up.
 *  - Latin Hinglish -> Devanagari or Urdu: Google Input Tools phonetic transliteration
 *    (`inputtools.google.com`, free, no key, built for exact Hinglish -> Hindi / Urdu),
 *    falling back to offline ICU transliteration when offline.
 *  - Devanagari <-> Urdu: hops through Latin so both offline and online paths compose cleanly.
 */
object LyricsScriptConverter {

    private val cache = ConcurrentHashMap<String, String>()

    /** True when the lyrics look like an Indian / Pakistani song rather than an English one. */
    fun supportsIndianScripts(sample: String): Boolean {
        if (sample.isBlank()) return false
        val native = detectScript(sample)
        if (native == LyricsScript.HINDI || native == LyricsScript.URDU) return true
        return looksLikeRomanHindi(sample)
    }

    /** Which script the raw text is already written in. */
    fun detectScript(text: String): LyricsScript {
        var deva = 0
        var arab = 0
        var latin = 0
        for (ch in text) {
            when (ch.code) {
                in 0x0900..0x097F -> deva++
                in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> arab++
                in 'A'.code..'Z'.code, in 'a'.code..'z'.code -> latin++
            }
        }
        return when {
            deva > arab && deva * 3 >= latin -> LyricsScript.HINDI
            arab > deva && arab * 3 >= latin -> LyricsScript.URDU
            else -> LyricsScript.HINGLISH
        }
    }

    /**
     * Convert a whole block of lines (preserving line breaks) to [target].
     * Run on a background dispatcher — the Latin -> Hindi/Urdu path may call Input Tools over HTTPS.
     */
    fun convertBlock(lines: List<String>, target: LyricsScript): List<String> {
        if (lines.isEmpty() || target == LyricsScript.ORIGINAL) return lines
        val joined = lines.joinToString("\n")
        val cacheKey = "${target.name}:${joined.hashCode()}:${joined.length}"
        cache[cacheKey]?.let { cached ->
            val split = cached.split('\n')
            if (split.size == lines.size) return split
        }

        val source = detectScript(joined)
        if (source == target) return lines

        val converted = when (target) {
            LyricsScript.ORIGINAL -> lines
            LyricsScript.HINGLISH -> lines.map { toHinglish(it, source) }
            LyricsScript.HINDI -> toIndicScript(lines, source, target = LyricsScript.HINDI)
            LyricsScript.URDU -> toIndicScript(lines, source, target = LyricsScript.URDU)
        }
        cache[cacheKey] = converted.joinToString("\n")
        return converted
    }

    // ---- to Hinglish (always fast & offline) ---------------------------------------------------

    private fun toHinglish(line: String, source: LyricsScript): String {
        if (line.isBlank()) return line
        return when (source) {
            LyricsScript.HINDI -> devanagariToHinglish(line)
            LyricsScript.URDU -> arabicToHinglish(line)
            else -> line
        }
    }

    private fun devanagariToHinglish(line: String): String {
        val raw = runCatching {
            Transliterator.getInstance("Devanagari-Latin").transliterate(line)
        }.getOrDefault(line)
        return raw.split(Regex("(\\s+)")).joinToString("") { token ->
            if (token.isBlank()) token else cleanIastWord(token)
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun arabicToHinglish(line: String): String {
        val raw = runCatching {
            Transliterator.getInstance("Arabic-Latin").transliterate(line)
        }.getOrDefault(line)
        return cleanIastWord(raw)
    }

    /**
     * ICU's `Devanagari-Latin` outputs scholarly ISO-15919 (`py\u0101ra`, `dil\u0101`, `m\u0113\u1e41`).
     * Spoken Hinglish drops the final mute schwa (`py\u0101ra` -> `pyaar`) and strips diacritics.
     */
    private fun cleanIastWord(word: String): String {
        var w = word
        // Drop trailing inherent schwa 'a' after a consonant: pyāra -> pyār
        w = w.replace(Regex("([bcdfghjklmnpqrstvwxyz\u1E45\u00F1\u1E6D\u1E0D\u1E47\u1E5B\u015B\u1E63\u1E25]h?)a(?=\\P{L}|$)"), "$1")
        w = w.replace("m\u0310", "n")
            .replace("\u1E41", "n")
            .replace("\u1E43", "n")
            .replace("\u1E45", "n")
            .replace("\u00F1", "n")
            .replace("\u1E47", "n")
            .replace("\u1E6D", "t")
            .replace("\u1E0D", "d")
            .replace("\u1E5B", "r")
            .replace("\u015B", "sh")
            .replace("\u1E63", "sh")
            .replace("\u1E25", "h")
            .replace("\u1E5D", "ri")
            .replace("\u0101", "a")
            .replace("\u012B", "i")
            .replace("\u016B", "u")
            .replace("\u0113", "e")
            .replace("\u014D", "o")
        return runCatching {
            Transliterator.getInstance("NFD; [:Nonspacing Mark:] Remove; NFC").transliterate(w)
        }.getOrDefault(w)
    }

    // ---- to Hindi / Urdu (Google Input Tools first, ICU fallback) ------------------------------

    private fun toIndicScript(
        lines: List<String>,
        source: LyricsScript,
        target: LyricsScript
    ): List<String> {
        val romanLines = if (source == LyricsScript.HINGLISH) {
            lines
        } else {
            lines.map { toHinglish(it, source) }
        }

        val itc = if (target == LyricsScript.HINDI) "hi-t-i0-und" else "ur-t-i0-und"
        val online = transliterateViaGoogleInputTools(romanLines, itc)
        if (online != null) return online

        val icuId = if (target == LyricsScript.HINDI) "Latin-Devanagari" else "Latin-Arabic"
        val tx = runCatching { Transliterator.getInstance(icuId) }.getOrNull() ?: return lines
        return romanLines.map { line ->
            if (line.isBlank()) line else runCatching { tx.transliterate(line) }.getOrDefault(line)
        }
    }

    private const val VOWELS = "aeiou\u0101\u012B\u016B\u0113\u014D"

    /**
     * Calls Google Input Tools line-by-line in small batches so punctuation and line breaks stay
     * aligned with LRC timestamps. Returns null on any network error so caller falls back to ICU.
     */
    private fun transliterateViaGoogleInputTools(lines: List<String>, itc: String): List<String>? {
        return try {
            val out = ArrayList<String>(lines.size)
            val wordRegex = Regex("[A-Za-z']+")
            for (line in lines) {
                if (line.isBlank()) {
                    out.add(line)
                    continue
                }
                val words = wordRegex.findAll(line).map { it.value }.toList()
                if (words.isEmpty()) {
                    out.add(line)
                    continue
                }
                val joinedWords = words.joinToString(" ")
                val url = "https://inputtools.google.com/request?text=" +
                    URLEncoder.encode(joinedWords, "UTF-8") +
                    "&itc=$itc&num=1&cp=0&cs=1&ie=utf-8&oe=utf-8&app=demopage"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()

                val root = JSONArray(body)
                if (root.optString(0) != "SUCCESS") return null
                val pairs = root.getJSONArray(1)
                val mapped = HashMap<String, String>(pairs.length())
                for (i in 0 until pairs.length()) {
                    val entry = pairs.getJSONArray(i)
                    val src = entry.getString(0)
                    val cands = entry.getJSONArray(1)
                    if (cands.length() > 0) mapped[src] = cands.getString(0)
                }
                val convertedWords = mapped[joinedWords]?.split(' ')
                if (convertedWords != null && convertedWords.size == words.size) {
                    var idx = 0
                    out.add(wordRegex.replace(line) { convertedWords[idx++] })
                } else {
                    out.add(wordRegex.replace(line) { m -> mapped[m.value] ?: m.value })
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    // ---- Roman-Hindi vs English heuristic ------------------------------------------------------

    private val HINGLISH_MARKERS = setOf(
        "hai", "hain", "ho", "hoon", "tha", "thi", "the",
        "mera", "meri", "mere", "tera", "teri", "tere", "tum", "tumhara", "tumhari", "apna", "apni", "apne",
        "kya", "kyun", "kyon", "kaise", "kaisa", "kaisi", "kahan", "kab", "kaun", "koi", "kuch",
        "nahi", "nahin", "naa", "mat", "bhi", "toh", "aur", "par", "lekin", "magar", "agar", "ya",
        "dil", "pyaar", "pyar", "ishq", "mohabbat", "zindagi", "jaan", "jaana", "sanam", "yaar", "dost",
        "aankh", "aankhein", "aankhon", "raat", "shaam", "subah", "sapna", "sapne", "khwab", "yaad", "yaadein",
        "chup", "baat", "baatein", "sath", "saath", "paas", "door", "raah", "rasta", "manzil",
        "main", "hum", "woh", "ye", "yeh", "is", "us", "in", "un", "se", "ko", "ka", "ki", "ke", "mein", "pe",
        "dekh", "dekho", "dekha", "sun", "suno", "suna", "chal", "chalo", "aa", "aaja", "ja", "jao", "rehna",
        "lagta", "lagti", "laga", "lagi", "mile", "mila", "mili", "milna", "chaha", "chahe", "chahu",
        "rab", "khuda", "maula", "dua", "sajda", "nazar", "dard", "gham", "khushi", "hasi", "ansoo"
    )

    private fun looksLikeRomanHindi(text: String): Boolean {
        val tokens = Regex("[A-Za-z]+").findAll(text.lowercase()).map { it.value }.toList()
        if (tokens.size < 6) return false
        val hits = tokens.count { it in HINGLISH_MARKERS }
        return hits >= 3 && hits.toFloat() / tokens.size >= 0.06f
    }
}