package com.example.myplayer.playback.dsp

import android.content.Context
import com.example.myplayer.ui.myplayer.MyPlayerAudioFxDspBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.pow

data class AutoEqHeadphoneEntry(
    val name: String,
    val source: String,
    val relativePath: String
)

data class ParametricBiquadFilter(
    val type: String,
    val freqHz: Double,
    val gainDb: Double,
    val q: Double
)

data class AutoEqProfile(
    val modelName: String,
    val preampDb: Double,
    val filters: List<ParametricBiquadFilter>,
    val tenBandGainsDb: FloatArray
)

/**
 * AutoEq Headphone Frequency Response Database Engine ported to MyPlayer Windows Desktop.
 * Applies parametric headphone EQ curves directly to [MasterAudioDspProcessor] and
 * [MyPlayerAudioFxDspBridge] for instant live desktop DSP processing.
 */
@Singleton
class AutoEqManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val masterAudioDspProcessor: MasterAudioDspProcessor
) {
    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
        private const val INDEX_TTL_MS = 30L * 24 * 60 * 60 * 1000L
        private val TEN_BAND_FREQS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        private val LINK_REGEX = Regex("""^\s*-\s*\[([^\]]+)]\(\./([^)]+)\)(?:\s*by\s+(.+))?$""")
        private val PREAMP_REGEX = Regex("""^Preamp:\s*([+-]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        private val FILTER_REGEX = Regex(
            """^Filter\s+\d+:\s+ON\s+(PK|LSC|HSC)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+([+-]?\d+(?:\.\d+)?)\s*dB\s+Q\s+(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )
    }

    private val cacheDir = File(context.filesDir, "autoeq").apply { mkdirs() }
    @Volatile
    private var cachedIndex: List<AutoEqHeadphoneEntry>? = null

    @Volatile
    var activeModelName: String? = null
        private set

    suspend fun loadIndex(): List<AutoEqHeadphoneEntry> = withContext(Dispatchers.IO) {
        cachedIndex?.let { return@withContext it }
        val indexFile = File(cacheDir, "INDEX.md")
        val isFresh = indexFile.exists() && (System.currentTimeMillis() - indexFile.lastModified() < INDEX_TTL_MS)
        val rawMarkdown = if (isFresh) {
            indexFile.readText()
        } else {
            runCatching {
                val req = Request.Builder().url("$BASE_URL/INDEX.md").build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
            }.getOrElse {
                if (indexFile.exists()) indexFile.readText() else ""
            }.also { fetched ->
                if (fetched.isNotBlank()) indexFile.writeText(fetched)
            }
        }
        parseIndexMarkdown(rawMarkdown).also { cachedIndex = it }
    }

    fun searchHeadphones(all: List<AutoEqHeadphoneEntry>, query: String, limit: Int = 50): List<AutoEqHeadphoneEntry> {
        val tokens = query.lowercase(Locale.ROOT).split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return all.take(limit)
        return all.asSequence()
            .filter { entry ->
                val lower = entry.name.lowercase(Locale.ROOT)
                tokens.all { it in lower }
            }
            .sortedWith(compareBy(
                { !it.name.lowercase(Locale.ROOT).startsWith(tokens.first()) },
                { it.name.length },
                { it.name }
            ))
            .take(limit)
            .toList()
    }

    suspend fun fetchAndApplyProfile(entry: AutoEqHeadphoneEntry): AutoEqProfile? = withContext(Dispatchers.IO) {
        val modelFileName = entry.relativePath.substringAfterLast('/')
        val url = "$BASE_URL/${entry.relativePath}/$modelFileName%20ParametricEQ.txt"
        val text = runCatching {
            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        }.getOrNull() ?: return@withContext null

        val profile = parseParametricEq(entry.name, text) ?: return@withContext null
        val preampFloat = profile.preampDb.toFloat().coerceIn(-12f, 12f)
        masterAudioDspProcessor.updateFromAudioLab10Band(true, profile.tenBandGainsDb, preampFloat, false)
        MyPlayerAudioFxDspBridge.setEq(true, profile.tenBandGainsDb.toList(), preampFloat)
        activeModelName = entry.name
        profile
    }

    private fun parseIndexMarkdown(markdown: String): List<AutoEqHeadphoneEntry> {
        return markdown.lineSequence().mapNotNull { line ->
            val m = LINK_REGEX.matchEntire(line) ?: return@mapNotNull null
            val name = m.groupValues[1].trim()
            val path = URLDecoder.decode(m.groupValues[2].trim(), "UTF-8").replace(" ", "%20")
            val source = m.groupValues[3].trim()
            if (name.isBlank() || path.isBlank()) null else AutoEqHeadphoneEntry(name, source, path)
        }.toList()
    }

    internal fun parseParametricEq(name: String, text: String): AutoEqProfile? {
        var preamp = 0.0
        val filters = mutableListOf<ParametricBiquadFilter>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            PREAMP_REGEX.find(line)?.let { preamp = it.groupValues[1].toDoubleOrNull() ?: 0.0 }
            val fm = FILTER_REGEX.matchEntire(line) ?: continue
            val type = fm.groupValues[1].uppercase(Locale.ROOT)
            val fc = fm.groupValues[2].toDoubleOrNull() ?: continue
            val gain = fm.groupValues[3].toDoubleOrNull() ?: continue
            val q = fm.groupValues[4].toDoubleOrNull() ?: continue
            if (fc in 10.0..24000.0 && q > 0.0) {
                filters.add(ParametricBiquadFilter(type, fc, gain, q))
            }
        }
        if (filters.isEmpty()) return null

        val tenBands = FloatArray(TEN_BAND_FREQS.size) { idx ->
            val f = TEN_BAND_FREQS[idx]
            var totalDb = 0.0
            for (biquad in filters) {
                val octaves = kotlin.math.abs(log10(f / biquad.freqHz) / log10(2.0))
                val bw = (1.0 / biquad.q.coerceAtLeast(0.25))
                val weight = 2.0.pow(-(octaves / bw).pow(2.0))
                totalDb += biquad.gainDb * weight
            }
            totalDb.toFloat().coerceIn(-12f, 12f)
        }

        return AutoEqProfile(
            modelName = name,
            preampDb = preamp,
            filters = filters,
            tenBandGainsDb = tenBands
        )
    }
}
