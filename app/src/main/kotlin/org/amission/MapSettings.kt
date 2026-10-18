package org.amission

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

enum class MapLayer { STREET, SATELLITE }

/** Where satellite imagery comes from. Every provider needs the user's own key or URL. */
enum class SatelliteProvider(val label: String) {
    MAPTILER("MapTiler"),
    MAPBOX("Mapbox"),
    CUSTOM("Custom tile URL"),
}

data class MapConfig(
    val layer: MapLayer = MapLayer.STREET,
    val provider: SatelliteProvider = SatelliteProvider.MAPTILER,
    val apiKey: String = "",
    /** XYZ raster template containing {z}, {x} and {y}. Used when [provider] is CUSTOM. */
    val customUrl: String = "",
) {
    /** True when the satellite layer has everything it needs to load. */
    val satelliteReady: Boolean
        get() = when (provider) {
            SatelliteProvider.CUSTOM -> isValidTemplate(customUrl)
            else -> apiKey.isNotBlank()
        }

    companion object {
        fun isValidTemplate(url: String): Boolean =
            url.startsWith("https://") && "{z}" in url && "{x}" in url && "{y}" in url
    }
}

/** Keys are stored in this app's private preferences only; they never touch the repo. */
class MapSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("amission_map", Context.MODE_PRIVATE)

    fun load(): MapConfig = MapConfig(
        layer = runCatching { MapLayer.valueOf(prefs.getString("layer", "")!!) }.getOrDefault(MapLayer.STREET),
        provider = runCatching { SatelliteProvider.valueOf(prefs.getString("provider", "")!!) }
            .getOrDefault(SatelliteProvider.MAPTILER),
        apiKey = prefs.getString("apiKey", "") ?: "",
        customUrl = prefs.getString("customUrl", "") ?: "",
    )

    fun save(c: MapConfig) {
        prefs.edit()
            .putString("layer", c.layer.name).putString("provider", c.provider.name)
            .putString("apiKey", c.apiKey).putString("customUrl", c.customUrl)
            .apply()
    }
}

sealed interface MapStyle {
    data class Url(val url: String) : MapStyle
    data class Json(val json: String) : MapStyle
}

object MapStyles {
    private const val STREET_URL = "https://tiles.openfreemap.org/styles/liberty"

    fun styleFor(config: MapConfig): MapStyle =
        if (config.layer == MapLayer.SATELLITE && config.satelliteReady) satellite(config) else MapStyle.Url(STREET_URL)

    private fun satellite(c: MapConfig): MapStyle {
        val key = URLEncoder.encode(c.apiKey.trim(), "UTF-8")
        val (template, attribution, maxZoom) = when (c.provider) {
            SatelliteProvider.MAPTILER -> Triple(
                "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=$key", "© MapTiler © OpenStreetMap contributors", 20,
            )
            SatelliteProvider.MAPBOX -> Triple(
                "https://api.mapbox.com/v4/mapbox.satellite/{z}/{x}/{y}@2x.jpg90?access_token=$key", "© Mapbox © OpenStreetMap contributors", 21,
            )
            SatelliteProvider.CUSTOM -> Triple(c.customUrl.trim(), "Custom imagery source", 19)
        }
        val style = JSONObject()
            .put("version", 8)
            .put(
                "sources",
                JSONObject().put(
                    "imagery",
                    JSONObject().put("type", "raster").put("tiles", JSONArray().put(template))
                        .put("tileSize", 256).put("maxzoom", maxZoom).put("attribution", attribution),
                ),
            )
            .put("layers", JSONArray().put(JSONObject().put("id", "imagery").put("type", "raster").put("source", "imagery")))
        return MapStyle.Json(style.toString())
    }
}
