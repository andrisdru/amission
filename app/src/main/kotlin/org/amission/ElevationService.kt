package org.amission

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Ground elevation lookup for the "Elevation (m)" row. Uses the free Open-Meteo elevation API
 * (no key). Needs internet; returns null when offline so the UI can show a dash.
 */
object ElevationService {
    private val cache = HashMap<String, Int>()

    suspend fun elevationMeters(lat: Double, lon: Double): Int? {
        val key = String.format(Locale.US, "%.4f,%.4f", lat, lon)
        synchronized(cache) { cache[key]?.let { return it } }
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(String.format(Locale.US, "https://api.open-meteo.com/v1/elevation?latitude=%.5f&longitude=%.5f", lat, lon))
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000; readTimeout = 4000
                }
                try {
                    if (conn.responseCode != 200) return@withContext null
                    val value = JSONObject(conn.inputStream.bufferedReader().readText())
                        .getJSONArray("elevation").getDouble(0)
                    Math.round(value).toInt().also { synchronized(cache) { cache[key] = it } }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
