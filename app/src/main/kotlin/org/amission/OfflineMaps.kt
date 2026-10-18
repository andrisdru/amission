package org.amission

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.amission.msp.ProviderErrors
import org.amission.msp.TileMath
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The part of the world currently visible on the map. */
data class Viewport(val south: Double, val west: Double, val north: Double, val east: Double, val zoom: Double)

class SavedRegion(
    val region: OfflineRegion,
    val name: String,
    val layer: String,
    val minZoom: Int,
    val maxZoom: Int,
    val bytes: Long,
    val complete: Boolean,
    val createdAt: Long,
)

/**
 * Saves map areas for use without internet, using MapLibre's offline region database. The
 * tiles of whichever style is active (street or the user's satellite source) are fetched once
 * and then served from the cache when the phone has no connection.
 */
class OfflineMaps(private val context: Context) {
    var regions by mutableStateOf<List<SavedRegion>>(emptyList())
        private set

    /** 0..1 while a download runs, null when idle. */
    var progress by mutableStateOf<Float?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    private var active: OfflineRegion? = null

    init {
        // Earlier builds wrote the satellite style, which contains the user's imagery key, to a file for the download.
        // That never worked and is no longer used, so remove any left over.
        context.filesDir.listFiles { f -> f.name.startsWith("offline_style_") }?.forEach { it.delete() }
    }

    private val manager: OfflineManager by lazy {
        MapSdk.init(context)
        OfflineManager.getInstance(context)
    }

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(list: Array<OfflineRegion>?) {
                val all = list.orEmpty()
                if (all.isEmpty()) { regions = emptyList(); return }
                val out = ArrayList<SavedRegion>()
                for (r in all) {
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) = add(r, status)
                        override fun onError(error: String?) = add(r, null)
                        private fun add(region: OfflineRegion, status: OfflineRegionStatus?) {
                            out += describe(region, status)
                            if (out.size == all.size) regions = out.sortedByDescending { it.createdAt }
                        }
                    })
                }
            }

            override fun onError(error: String) { message = "Could not list offline maps: $error" }
        })
    }

    private fun describe(r: OfflineRegion, status: OfflineRegionStatus?): SavedRegion {
        val meta = runCatching { JSONObject(String(r.metadata)) }.getOrDefault(JSONObject())
        val def = r.definition as? OfflineTilePyramidRegionDefinition
        return SavedRegion(
            region = r,
            name = meta.optString("name", "Offline area"),
            layer = meta.optString("layer", ""),
            minZoom = def?.minZoom?.toInt() ?: 0,
            maxZoom = def?.maxZoom?.toInt() ?: 0,
            bytes = status?.completedResourceSize ?: 0L,
            complete = status?.isComplete ?: false,
            createdAt = meta.optLong("createdAt", 0L),
        )
    }

    /** Serves the satellite style to the downloader while a download runs; null for the street style. */
    private var styleServer: LocalStyleServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastProgressAt = 0L
    private var lastCompleted = -1L

    private fun stopStyleServer() {
        styleServer?.close()
        styleServer = null
    }

    /**
     * The style address the downloader should fetch for [config]. The street style has a web address. The satellite
     * style is built in the app, so it is served from the phone itself (see [LocalStyleServer]); a file:// address
     * does not work for downloads.
     */
    private fun prepareStyleUrl(config: MapConfig): String {
        stopStyleServer()
        return when (val s = MapStyles.styleFor(config)) {
            is MapStyle.Url -> s.url
            is MapStyle.Json -> LocalStyleServer(s.json).also { styleServer = it }.url
        }
    }

    /** Ends a download that has stopped making progress, so the dialog never sits at a percentage forever. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (progress == null) return
            if (System.currentTimeMillis() - lastProgressAt > STALL_MS) {
                message = "The download stopped making progress and was cancelled. Check the internet connection and try again."
                cancel(keepMessage = true)
            } else {
                handler.postDelayed(this, 5_000)
            }
        }
    }

    fun download(config: MapConfig, v: Viewport, minZoom: Int, maxZoom: Int, name: String, layerLabel: String) {
        if (progress != null) return
        val bounds = LatLngBounds.Builder().include(LatLng(v.south, v.west)).include(LatLng(v.north, v.east)).build()
        val definition = OfflineTilePyramidRegionDefinition(
            prepareStyleUrl(config), bounds, minZoom.toDouble(), maxZoom.toDouble(), context.resources.displayMetrics.density,
        )
        val metadata = JSONObject().put("name", name).put("layer", layerLabel).put("createdAt", System.currentTimeMillis())
            .toString().toByteArray()
        progress = 0f
        message = null
        lastProgressAt = System.currentTimeMillis()
        lastCompleted = -1L
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 5_000)
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                active = offlineRegion
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        val required = status.requiredResourceCount
                        progress = if (required > 0) status.completedResourceCount.toFloat() / required else 0f
                        if (status.completedResourceCount != lastCompleted) {
                            lastCompleted = status.completedResourceCount
                            lastProgressAt = System.currentTimeMillis()
                        }
                        if (status.isComplete) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            offlineRegion.setObserver(null)
                            active = null
                            progress = null
                            handler.removeCallbacks(watchdog)
                            stopStyleServer()
                            message = "Saved \"$name\" (${formatMb(status.completedResourceSize)})"
                            refresh()
                        }
                    }

                    // Individual tile failures are retried by the engine; just surface the latest one, in plain words.
                    override fun onError(error: OfflineRegionError) { message = ProviderErrors.describe(error.message) }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        message = "Too many tiles for this download (limit $limit)"
                        cancel()
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) {
                progress = null
                handler.removeCallbacks(watchdog)
                stopStyleServer()
                message = "Could not start the download: $error"
            }
        })
    }

    /** Stops a running download and removes the partial area. */
    fun cancel(keepMessage: Boolean = false) {
        handler.removeCallbacks(watchdog)
        stopStyleServer()
        val r = active
        progress = null
        if (r == null) { if (!keepMessage) message = "Download cancelled"; return }
        r.setDownloadState(OfflineRegion.STATE_INACTIVE)
        r.setObserver(null)
        active = null
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { if (!keepMessage) message = "Download cancelled"; refresh() }
            override fun onError(error: String) { message = "Cancelled, but cleanup failed: $error" }
        })
    }

    fun delete(saved: SavedRegion) {
        saved.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { message = "Deleted \"${saved.name}\""; refresh() }
            override fun onError(error: String) { message = "Could not delete: $error" }
        })
    }
}

private fun formatMb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

// Satellite imagery limits are the user's provider's business; this only stops runaway downloads.
private const val MAX_SATELLITE_TILES = 25_000L
// A download that completes no new resource for this long is treated as stuck (no internet, or the style could not load).
private const val STALL_MS = 30_000L
// OpenFreeMap's terms (checked 2026-10) allow an app to offer offline areas of at most 3,000 tiles or 50 MB per
// download, with at most 4 parallel requests and an identifying User-Agent (see MapSdk).
private const val MAX_STREET_TILES = 3_000L
private const val MAX_STREET_MB = 50.0
// Rough averages for the size preview: measured 22 KB per tile in rural land (max 35 KB); built-up areas are larger.
private const val STREET_KB_PER_TILE = 40
private const val SATELLITE_KB_PER_TILE = 45
// The street vector tiles stop at this zoom; deeper levels are drawn from it, so nothing is gained by fetching more.
private const val STREET_MAX_ZOOM = 14
// Measured: the street style's fonts (every glyph range) are about 17-18 MB, stored once and shared by every later Street area.
private const val STREET_FONTS_MB = 18.0

@Composable
fun OfflineMapsDialog(vm: MissionViewModel, onDismiss: () -> Unit) {
    val offline = vm.offline
    val config = vm.mapConfig
    val viewport = vm.viewport
    LaunchedEffect(Unit) { offline.refresh() }

    val satellite = config.layer == MapLayer.SATELLITE && config.satelliteReady
    val layerLabel = if (satellite) "Satellite (${config.provider.label})" else "Street"
    var satMaxZoom by remember { mutableIntStateOf(17) }
    var accepted by remember { mutableStateOf(false) }
    var name by remember(layerLabel) {
        mutableStateOf("$layerLabel ${SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())}")
    }

    val maxZoom = if (satellite) satMaxZoom else STREET_MAX_ZOOM
    val minZoom = viewport?.let { min(max(6, floor(it.zoom).toInt() - 3), maxZoom) } ?: maxZoom
    // Imagery comes in 256 px tiles, which the map fetches one zoom level deeper than the map zoom (see TileMath.tileZoomOffset).
    val tileOffset = if (satellite) TileMath.tileZoomOffset(MapStyles.SATELLITE_TILE_SIZE) else 0
    val tiles = viewport?.let { TileMath.tileCount(it.south, it.west, it.north, it.east, minZoom + tileOffset, maxZoom + tileOffset) } ?: 0L
    val needsFonts = !satellite && offline.regions.none { it.layer == "Street" }
    val estimatedMb = tiles * (if (satellite) SATELLITE_KB_PER_TILE else STREET_KB_PER_TILE) / 1024.0 + if (needsFonts) STREET_FONTS_MB else 0.0
    val tooBig = if (satellite) tiles > MAX_SATELLITE_TILES else tiles > MAX_STREET_TILES || estimatedMb > MAX_STREET_MB
    val downloading = offline.progress != null
    val canStart = viewport != null && !downloading && !tooBig && (!satellite || accepted)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline maps") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save the area currently on screen so it works without internet.", style = MaterialTheme.typography.bodyMedium)
                Text("Layer: $layerLabel", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (satellite) {
                        "Without internet the imagery shows only inside the area on screen now and between map zoom " +
                            "$minZoom and $maxZoom. Zoom in further, zoom out, or pan outside it and the map goes dark."
                    } else {
                        "Without internet the map shows only the area on screen now. It stays readable when zoomed in " +
                            "further, but zooming out past level $minZoom or panning outside leaves it blank."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                if (satellite) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Max zoom")
                        for (z in listOf(16, 17, 18)) {
                            FilterChip(selected = satMaxZoom == z, onClick = { satMaxZoom = z }, label = { Text("$z") }, enabled = !downloading)
                        }
                    }
                }

                if (viewport == null) {
                    Text("Move the map first so the area is known.")
                } else {
                    Text(
                        "Zoom $minZoom to $maxZoom · about ${String.format(Locale.US, "%,d", tiles)} tiles · roughly ${formatEstimate(estimatedMb)} (estimate)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (needsFonts) {
                        Text(
                            "The first Street area also stores the map fonts (about 18 MB). Later Street areas reuse them.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (tooBig) {
                        Text(
                            if (satellite) "Too large. Zoom the map in to a smaller area or lower the max zoom."
                            else "Too large for the free street map service (limit $MAX_STREET_TILES tiles or ${MAX_STREET_MB.toInt()} MB per download). Zoom in to a smaller area.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") },
                    singleLine = true, enabled = !downloading, modifier = Modifier.fillMaxWidth(),
                )

                if (satellite) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = accepted, onCheckedChange = { accepted = it }, enabled = !downloading)
                        Text(
                            "I checked that my imagery provider allows saving tiles for offline use.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                val p = offline.progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${(p * 100).toInt()}%", Modifier.weight(1f))
                        OutlinedButton(onClick = offline::cancel) { Text("Cancel") }
                    }
                } else {
                    Button(
                        onClick = { viewport?.let { offline.download(config, it, minZoom, maxZoom, name.ifBlank { layerLabel }, layerLabel) } },
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Download this area") }
                }

                offline.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Saved areas", style = MaterialTheme.typography.titleSmall)
                if (offline.regions.isEmpty()) {
                    Text("None yet.", style = MaterialTheme.typography.bodySmall)
                }
                offline.regions.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyMedium)
                            val state = if (r.complete) formatMb(r.bytes) else "incomplete · ${formatMb(r.bytes)}"
                            Text(
                                "${r.layer} · zoom ${r.minZoom}–${r.maxZoom} · $state",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (r.complete) Color.Unspecified else MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(onClick = { offline.delete(r) }, enabled = !downloading) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun formatEstimate(mb: Double): String = when {
    mb < 10 -> String.format(Locale.US, "%.1f MB", mb)
    mb < 100_000 -> String.format(Locale.US, "%,.0f MB", mb)
    else -> "over 100,000 MB"
}

