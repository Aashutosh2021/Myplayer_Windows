package com.example.myplayer.data.update

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.myplayer.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val versionName: String,
    val tagName: String = versionName,
    val releaseUrl: String = UpdateChecker.UPDATE_WEBSITE_URL,
    val releaseNotes: String = "",
    val apkUrl: String? = null,
    val apkName: String? = null,
    val apkSizeBytes: Long = 0L,
    val publishedAt: String = "",
    val isPreRelease: Boolean = false,
    val githubReleaseUrl: String? = null
)

class NoInternetException(
    message: String = "No internet connection detected. Please check your network."
) : IOException(message)

/**
 * Polls the GitHub Releases API for `Aashutosh2021/Myplayer_Windows` with full
 * Beta/Pre-release channel support, 24-hour snooze, and priority Windows installer
 * asset resolution (`.msi` -> `.exe` -> `.zip`).
 */
@Singleton
class UpdateChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "UpdateChecker"

        const val GITHUB_OWNER = "Aashutosh2021"
        const val GITHUB_REPO = "Myplayer_Windows"

        const val UPDATE_WEBSITE_URL = "https://myplayer-660ee.web.app/"

        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        private const val ALL_RELEASES_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=15"

        private const val PREFS_NAME = "update_check_prefs"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        private const val KEY_SNOOZED_UNTIL_MS = "update_snoozed_until_ms"
        private const val KEY_SNOOZED_VERSION = "update_snoozed_version"
        private const val KEY_INCLUDE_BETA = "update_include_beta"

        private val VERSION_REGEX = Regex("""(?i)\bv?(\d+(?:\.\d+)+)\b""")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var includeBetaReleases: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_BETA, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INCLUDE_BETA, value).apply()
        }

    fun snoozeUpdateFor24Hours(versionName: String) {
        val until = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
        prefs.edit()
            .putString(KEY_SNOOZED_VERSION, versionName)
            .putLong(KEY_SNOOZED_UNTIL_MS, until)
            .apply()
    }

    fun isUpdateSnoozed(versionName: String): Boolean {
        val snoozedVer = prefs.getString(KEY_SNOOZED_VERSION, null) ?: return false
        val until = prefs.getLong(KEY_SNOOZED_UNTIL_MS, 0L)
        return snoozedVer == versionName && System.currentTimeMillis() < until
    }

    fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(network) ?: return true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    suspend fun checkForUpdate(forceCheckBeta: Boolean = includeBetaReleases): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            throw NoInternetException("No internet connection detected. Please check your Wi-Fi or Ethernet connection.")
        }

        try {
            val releaseJson = fetchBestReleaseJson(forceCheckBeta) ?: return@withContext null
            parseReleaseToUpdateInfo(releaseJson)
        } catch (e: UnknownHostException) {
            throw NoInternetException("Unable to reach GitHub. Please check your internet connection.")
        } catch (e: ConnectException) {
            throw NoInternetException("Failed to connect to GitHub. Please check your network connection.")
        } catch (e: SocketTimeoutException) {
            throw IOException("Connection timed out while checking for updates. Please try again.")
        }
    }

    private fun fetchBestReleaseJson(includeBeta: Boolean): JSONObject? {
        val listReq = Request.Builder()
            .url(ALL_RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MyPlayer-Desktop-UpdateChecker")
            .build()

        okHttpClient.newCall(listReq).execute().use { resp ->
            if (resp.code == 403) throw IOException("GitHub API rate limit exceeded. Please try again later.")
            if (resp.code >= 500) throw IOException("GitHub server error (${resp.code}). Please try again later.")
            if (resp.isSuccessful) {
                val arr = runCatching { JSONArray(resp.body?.string().orEmpty()) }.getOrNull()
                if (arr != null && arr.length() > 0) {
                    for (i in 0 until arr.length()) {
                        val rel = arr.optJSONObject(i) ?: continue
                        if (rel.optBoolean("draft", false)) continue
                        val isPre = rel.optBoolean("prerelease", false)
                        if (isPre && !includeBeta) continue
                        return rel
                    }
                }
            }
        }

        // Fallback to /releases/latest
        val latestReq = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MyPlayer-Desktop-UpdateChecker")
            .build()
        okHttpClient.newCall(latestReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GitHub latest release check returned HTTP ${resp.code}")
                return null
            }
            return runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
        }
    }

    private fun parseReleaseToUpdateInfo(json: JSONObject): UpdateInfo? {
        val tagName = json.optString("tag_name", "")
        val releaseName = json.optString("name", "")
        val notes = json.optString("body", "")
        val publishedAt = json.optString("published_at", "")
        val isPreRelease = json.optBoolean("prerelease", false)

        // Priority for Windows Desktop: .msi -> .exe -> .zip
        var bestUrl: String? = null
        var bestName: String? = null
        var bestSize = 0L
        var bestPriority = Int.MAX_VALUE

        val assetsArray = json.optJSONArray("assets")
        if (assetsArray != null) {
            for (i in 0 until assetsArray.length()) {
                val asset = assetsArray.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val priority = when {
                    name.endsWith(".msi", ignoreCase = true) -> 1
                    name.endsWith(".exe", ignoreCase = true) -> 2
                    name.endsWith(".zip", ignoreCase = true) -> 3
                    else -> Int.MAX_VALUE
                }
                if (priority < bestPriority) {
                    bestPriority = priority
                    bestName = name
                    bestUrl = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                    bestSize = asset.optLong("size", 0L)
                }
            }
        }

        val remoteVersion = extractVersion(tagName, releaseName, notes, bestName)
        val defaultUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
        val releaseHtmlUrl = json.optString("html_url", defaultUrl)
        val displayName = releaseName.ifBlank { "v$remoteVersion" }

        val jpackageVersion = System.getProperty("jpackage.app-version").orEmpty()
        val localVersion = if (jpackageVersion.isNotBlank() && isNewerVersion(jpackageVersion, BuildConfig.VERSION_NAME)) {
            jpackageVersion
        } else {
            BuildConfig.VERSION_NAME
        }
        if (!isNewerVersion(remoteVersion, localVersion)) {
            return null
        }

        return UpdateInfo(
            versionName = displayName,
            tagName = tagName.ifBlank { "v$remoteVersion" },
            releaseUrl = UPDATE_WEBSITE_URL,
            releaseNotes = notes,
            apkUrl = bestUrl,
            apkName = bestName,
            apkSizeBytes = bestSize,
            publishedAt = publishedAt,
            isPreRelease = isPreRelease,
            githubReleaseUrl = releaseHtmlUrl
        )
    }

    fun shouldNotifyFor(update: UpdateInfo): Boolean {
        if (isUpdateSnoozed(update.versionName)) return false
        return prefs.getString(KEY_LAST_NOTIFIED_VERSION, null) != update.versionName
    }

    fun markNotified(update: UpdateInfo) {
        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, update.versionName).apply()
    }

    fun extractVersion(
        tagName: String,
        releaseName: String,
        body: String,
        assetName: String?
    ): String {
        VERSION_REGEX.find(tagName)?.groupValues?.get(1)?.let { return it }
        VERSION_REGEX.find(releaseName)?.groupValues?.get(1)?.let { return it }
        if (!assetName.isNullOrBlank()) {
            VERSION_REGEX.find(assetName)?.groupValues?.get(1)?.let { return it }
        }
        VERSION_REGEX.find(body)?.groupValues?.get(1)?.let { return it }
        val clean = tagName.removePrefix("v").removePrefix("V").trim()
        return clean.ifBlank { "0.0" }
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val l = local.split(".").mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val maxLen = maxOf(r.size, l.size)
        for (i in 0 until maxLen) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
