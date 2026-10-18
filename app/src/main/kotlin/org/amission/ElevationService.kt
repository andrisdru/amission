package org.amission

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Ground elevation lookups (metres above sea level) from the free Open-Meteo elevation API, which
 * needs no key. Its terrain model is coarse (about 90 m cells) and shows bare ground, not trees or
 * buildings. Results are cached on the device, so a route looked up once can be shown again offline.
 */
object ElevationService {
    private const val BATCH = 100              // the API accepts up to 100 coordinates per request
    private const val MAX_CACHE = 50_000
    private val cache = HashMap<String, Double>()
    private var cacheFile: File? = null
    private var loaded = false

    fun init(context: Context) {
        if (cacheFile == null) cacheFile = File(context.filesDir, "elevation_cache.json")
    }

    private fun key(lat: Double, lon: Double) = String.format(Locale.US, "%.4f,%.4f", lat, lon)

    private fun loadCacheOnce() {
        if (loaded) return
        loaded = true
        val f = cacheFile ?: return
        try {
            if (f.exists()) {
                val o = JSONObject(f.readText())
                for (k in o.keys()) cache[k] = o.getDouble(k)
            }
        } catch (e: Exception) {
            cache.clear() // a damaged cache is just a cache miss
        }
    }

    private fun saveCache() {
        val f = cacheFile ?: return
        try {
            if (cache.size > MAX_CACHE) cache.clear()
            val o = JSONObject()
            synchronized(cache) { cache.forEach { (k, v) -> o.put(k, v) } }
            f.writeText(o.toString())
        } catch (e: Exception) {
            // the cache is an optimisation only
        }
    }

    /** One height per input point, in order; null for any point that could not be looked up. */
    suspend fun elevations(points: List<Pair<Double, Double>>): List<Double?> = withContext(Dispatchers.IO) {
        synchronized(cache) { loadCacheOnce() }
        val result = arrayOfNulls<Double>(points.size)
        val missing = ArrayList<Int>()
        points.forEachIndexed { i, (lat, lon) ->
            val hit = synchronized(cache) { cache[key(lat, lon)] }
            if (hit != null) result[i] = hit else missing += i
        }
        var fetchedAny = false
        for (chunk in missing.chunked(BATCH)) {
            val values = fetch(chunk.map { points[it] }) ?: continue
            chunk.forEachIndexed { n, idx ->
                result[idx] = values[n]
                synchronized(cache) { cache[key(points[idx].first, points[idx].second)] = values[n] }
            }
            fetchedAny = true
        }
        if (fetchedAny) saveCache()
        result.toList()
    }

    suspend fun elevationMeters(lat: Double, lon: Double): Int? =
        elevations(listOf(lat to lon)).first()?.let { Math.round(it).toInt() }

    private fun fetch(points: List<Pair<Double, Double>>): List<Double>? {
        return try {
            val lats = points.joinToString(",") { String.format(Locale.US, "%.5f", it.first) }
            val lons = points.joinToString(",") { String.format(Locale.US, "%.5f", it.second) }
            val conn = (URL("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 8000
            }
            try {
                if (conn.responseCode != 200) return null
                val arr = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("elevation")
                if (arr.length() != points.size) return null
                List(points.size) { arr.getDouble(it) }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
