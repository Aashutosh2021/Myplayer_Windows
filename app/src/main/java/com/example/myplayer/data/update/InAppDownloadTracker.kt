package com.example.myplayer.data.update

import android.content.Context
import android.util.Log
import com.example.myplayer.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Logs in-app update downloads directly to the MyPlayer website's Firebase Firestore
 * analytics database (`myplayer-660ee`) so every in-app update increments the website's
 * Total Downloads & Windows platform counters in `admin.html` (no Firebase SDK needed,
 * fully compliant with existing `firestore.rules`).
 */
object InAppDownloadTracker {
    private const val TAG = "InAppDownloadTracker"
    private const val PROJECT_ID = "myplayer-660ee"
    private const val API_KEY = "AIzaSyDJT1PkZYFLZ_QpTj81L1fPQZwuRSiOVuA"
    private const val FIRESTORE_BASE =
        "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"
    private const val PREFS_NAME = "myplayer_analytics_prefs"
    private const val KEY_VISITOR_ID = "persistent_visitor_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun getOrCreateVisitorId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_VISITOR_ID, null)
        if (!existing.isNullOrBlank() && existing.length in 6..128) return existing
        val created = "winapp_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        prefs.edit().putString(KEY_VISITOR_ID, created).apply()
        return created
    }

    /**
     * Fires a non-blocking Firestore REST event (`type = "download"`, `platform = "Windows"`)
     * whenever the user downloads an update inside the Windows Desktop app.
     */
    fun trackInAppUpdateDownload(
        context: Context,
        okHttpClient: OkHttpClient,
        update: UpdateInfo,
        assetName: String,
        downloadUrl: String
    ) {
        scope.launch {
            runCatching {
                val nowMs = System.currentTimeMillis()
                val isoNow = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowMs))
                val visitorId = getOrCreateVisitorId(context)
                val eventId = "inapp_win_${nowMs}_${UUID.randomUUID().toString().take(8)}"
                val osName = System.getProperty("os.name").orEmpty().ifBlank { "Windows" }
                val osArch = System.getProperty("os.arch").orEmpty().ifBlank { "amd64" }

                val fields = JSONObject().apply {
                    put("type", strVal("download"))
                    put("ts", JSONObject().put("timestampValue", isoNow))
                    put("clientTsMs", JSONObject().put("integerValue", nowMs.toString()))
                    put("platform", strVal("Windows"))
                    put("version", strVal(update.tagName.ifBlank { update.versionName }))
                    put("installedVersion", strVal(BuildConfig.VERSION_NAME))
                    put("assetName", strVal(assetName))
                    put("button", strVal("in-app-update-windows"))
                    put("href", strVal(downloadUrl))
                    put("visitorId", strVal(visitorId))
                    put("deviceId", strVal(visitorId))
                    put("isBot", JSONObject().put("booleanValue", false))
                    put("device", strVal("Desktop"))
                    put("os", strVal(osName))
                    put("arch", strVal(osArch))
                    put("browser", strVal("MyPlayer Desktop App"))
                    put("page", strVal("in-app-updater"))
                    put("referrer", strVal("in-app"))
                }

                val payload = JSONObject().put("fields", fields).toString()
                val eventUrl = "$FIRESTORE_BASE/events?documentId=$eventId&key=$API_KEY"
                val req = Request.Builder()
                    .url(eventUrl)
                    .post(payload.toRequestBody(jsonMediaType))
                    .header("User-Agent", "MyPlayer-Desktop/${BuildConfig.VERSION_NAME}")
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Log.i(TAG, "In-app Windows update download tracked on website ($eventId)")
                    } else {
                        Log.w(TAG, "Firestore download track returned HTTP ${resp.code}")
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Failed to track in-app download (offline or blocked): ${err.message}")
            }
        }
    }

    private fun strVal(value: String): JSONObject = JSONObject().put("stringValue", value)
}
