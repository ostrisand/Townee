package town.matters.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val version: String, val notes: String, val pageUrl: String, val apkUrl: String)

object UpdateChecker {
    private const val latestUrl = "https://api.github.com/repos/ostrisand/Townee/releases/latest"
    suspend fun latest(current: String = "1.0.0"): Result<AppUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(latestUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000; connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Townee-Android-UpdateChecker")
            try {
                if (connection.responseCode !in 200..299) error("GitHub 返回 HTTP ${connection.responseCode}")
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@runCatching null
                val tag = json.optString("tag_name").removePrefix("v")
                if (tag.isBlank() || compare(tag, current) <= 0) return@runCatching null
                val assets = json.optJSONArray("assets") ?: org.json.JSONArray()
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith("-release.apk") }
                AppUpdate(tag, json.optString("body"), json.optString("html_url"), apk?.optString("browser_download_url").orEmpty())
            } finally { connection.disconnect() }
        }
    }
    private fun compare(a: String, b: String): Int {
        val left = a.split(".", "-").map { it.toIntOrNull() ?: 0 }; val right = b.split(".", "-").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) { val x = left.getOrElse(i) { 0 }; val y = right.getOrElse(i) { 0 }; if (x != y) return x.compareTo(y) }
        return 0
    }
}
