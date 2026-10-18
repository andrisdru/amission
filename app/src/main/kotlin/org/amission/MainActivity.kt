package org.amission

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amission.msp.MissionClient
import org.amission.msp.SerialMspLink
import org.amission.msp.Waypoint
import org.amission.msp.WaypointAction
import kotlin.math.roundToInt

/**
 * The mission is a local document: it can be created and edited with no FC attached and is
 * auto-saved. A connection is only opened for the duration of an upload/download/save.
 */
class MissionViewModel(app: Application) : AndroidViewModel(app) {
    private val store = MissionStore(app)
    private val mapSettings = MapSettingsStore(app)

    var mapConfig by mutableStateOf(mapSettings.load())
        private set

    fun updateMapConfig(config: MapConfig) {
        mapConfig = config
        mapSettings.save(config)
    }

    var waypoints by mutableStateOf(store.load())
        private set
    var selected by mutableStateOf<Int?>(null)
        private set
    var status by mutableStateOf("Tap the map to add waypoints")
        private set
    var busy by mutableStateOf(false)
        private set

    private fun update(list: List<Waypoint>, sel: Int? = selected) {
        waypoints = list.mapIndexed { i, w -> w.copy(number = i + 1, isLast = i == list.lastIndex) }
        selected = sel?.takeIf { it in waypoints.indices }
        store.save(waypoints)
    }

    /** Bumped to ask the map to zoom to the mission; starts at 1 when there is already a mission to show. */
    var fitRequest by mutableIntStateOf(if (waypoints.any { it.action.hasPosition }) 1 else 0)
        private set

    fun requestFit() { fitRequest++ }

    /** Home position (lat, lon) for the "Grd Dist" row. Not known until set; null shows "NO HOME". */
    var home by mutableStateOf<Pair<Double, Double>?>(null)
        private set

    /** Last map camera centre, used by the table's "+" button. */
    private var mapCenter: Pair<Double, Double>? = null

    /** The area currently visible on the map; the offline download dialog saves exactly this. */
    var viewport by mutableStateOf<Viewport?>(null)
        private set

    val offline = OfflineMaps(app)

    fun onViewportChanged(v: Viewport) {
        viewport = v
        mapCenter = (v.south + v.north) / 2 to (v.west + v.east) / 2
    }

    fun addWaypoint(lat: Double, lon: Double) {
        val wp = Waypoint(
            number = 0, action = WaypointAction.WAYPOINT,
            latE7 = (lat * 1e7).roundToInt(), lonE7 = (lon * 1e7).roundToInt(),
            altitudeCm = waypoints.lastOrNull { it.action.hasPosition }?.altitudeCm ?: DEFAULT_ALTITUDE_CM,
        )
        update(waypoints + wp, sel = waypoints.size)
    }

    /** Appends an RTH as the final point (altitude copied from the last positioned waypoint) and selects it. */
    fun addRthAtEnd() {
        if (waypoints.lastOrNull()?.action == WaypointAction.RTH) {
            selected = waypoints.lastIndex
            status = "Mission already ends with RTH"
            return
        }
        val alt = waypoints.lastOrNull { it.action.hasPosition }?.altitudeCm ?: DEFAULT_ALTITUDE_CM
        val rth = Waypoint(number = 0, action = WaypointAction.RTH, latE7 = 0, lonE7 = 0, altitudeCm = alt)
        update(waypoints + rth, sel = waypoints.size)
        status = "Added RTH as point ${waypoints.size}"
    }

    fun addAtMapCenter() {
        val (lat, lon) = mapCenter ?: return
        addWaypoint(lat, lon)
    }

    fun select(index: Int) { selected = index }

    fun updateSelected(change: (Waypoint) -> Waypoint) {
        val i = selected ?: return
        update(waypoints.toMutableList().also { it[i] = change(it[i]) })
    }

    /** Switches the action type and resets the type-specific parameters to sensible defaults. */
    fun setSelectedType(action: WaypointAction) = updateSelected { old ->
        if (old.action == action) return@updateSelected old
        val centre = mapCenter
        var w = old.copy(action = action, p1 = 0, p2 = 0)
        w = when (action) {
            WaypointAction.POSHOLD_TIME -> w.copy(p1 = 10)
            WaypointAction.JUMP -> w.copy(p1 = 1, p2 = 1)
            WaypointAction.SET_HEAD -> w.copy(p1 = -1)
            else -> w
        }
        if (!action.hasPosition) {
            // Positionless actions are sent with zeroed coordinates; RTH keeps its altitude.
            w.copy(latE7 = 0, lonE7 = 0, altitudeCm = if (action == WaypointAction.RTH) old.altitudeCm else 0, p3 = 0)
        } else if (!old.action.hasPosition && centre != null) {
            w.copy(latE7 = (centre.first * 1e7).roundToInt(), lonE7 = (centre.second * 1e7).roundToInt(),
                altitudeCm = if (old.altitudeCm != 0) old.altitudeCm else DEFAULT_ALTITUDE_CM)
        } else w
    }

    fun deleteSelected() {
        val i = selected ?: return
        update(waypoints.toMutableList().also { it.removeAt(i) }, sel = null)
    }

    fun clear() = update(emptyList(), sel = null)

    fun uploadToFc(saveToEeprom: Boolean) = withFc("Uploading…") { client ->
        client.uploadAndVerify(waypoints)
        if (saveToEeprom) client.saveToEeprom()
        if (saveToEeprom) "Uploaded, verified and saved to FC" else "Uploaded and verified (not saved to EEPROM)"
    }

    /** Reads the FC's mission, loading its saved (EEPROM) mission first if working memory is empty. */
    fun downloadFromFc() = withFc("Downloading…") { client ->
        val result = client.downloadOrLoadStored()
        when {
            result.loadRejected ->
                "FC has no mission in memory and refused to load a saved one (none stored, invalid, or armed). Your mission was not changed."
            result.waypoints.isEmpty() -> "FC has no waypoints. Your mission was not changed."
            else -> {
                withContext(Dispatchers.Main) { update(result.waypoints, sel = null); requestFit() }
                val src = if (result.loadedFromStorage) " (loaded from the FC's saved mission)" else ""
                "Downloaded ${result.waypoints.size} waypoints$src"
            }
        }
    }

    private fun withFc(progress: String, block: suspend (MissionClient) -> String) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            status = progress
            try {
                val channel = UsbSerialChannel.open(getApplication())
                status = channel.use {
                    withContext(Dispatchers.IO) { block(MissionClient(SerialMspLink(channel))) }
                }
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        const val DEFAULT_ALTITUDE_CM = 3000
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { MissionScreen() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissionScreen(vm: MissionViewModel = viewModel()) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDownload by remember { mutableStateOf(false) }
    // Selecting a waypoint (map tap or table row) brings its editor up.
    LaunchedEffect(vm.selected) { if (vm.selected != null) { tab = 0; expanded = true } }
    val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp

    var showMapSettings by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    var openedForSatellite by remember { mutableStateOf(false) }
    val mapStyle = remember(vm.mapConfig) { MapStyles.styleFor(vm.mapConfig) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MissionMap(
                waypoints = vm.waypoints,
                selected = vm.selected,
                onMapTap = vm::addWaypoint,
                onSelect = vm::select,
                onViewportChanged = vm::onViewportChanged,
                mapStyle = mapStyle,
                fitRequest = vm.fitRequest,
                modifier = Modifier.fillMaxSize(),
            )
            FlowRow(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = vm.mapConfig.layer == MapLayer.STREET,
                    onClick = { vm.updateMapConfig(vm.mapConfig.copy(layer = MapLayer.STREET)) },
                    label = { Text("Street") },
                    colors = overlayChipColors(),
                )
                FilterChip(
                    selected = vm.mapConfig.layer == MapLayer.SATELLITE,
                    onClick = {
                        if (vm.mapConfig.satelliteReady) {
                            vm.updateMapConfig(vm.mapConfig.copy(layer = MapLayer.SATELLITE))
                        } else {
                            openedForSatellite = true
                            showMapSettings = true
                        }
                    },
                    label = { Text("Satellite") },
                    colors = overlayChipColors(),
                )
                FilterChip(
                    selected = false,
                    onClick = vm::requestFit,
                    label = { Text("Fit") },
                    enabled = vm.waypoints.any { it.action.hasPosition },
                    colors = overlayChipColors(),
                )
                FilterChip(
                    selected = false,
                    onClick = { showOffline = true },
                    label = { Text("Offline") },
                    colors = overlayChipColors(),
                )
                FilterChip(
                    selected = false,
                    onClick = { openedForSatellite = false; showMapSettings = true },
                    label = { Text("⚙") },
                    colors = overlayChipColors(),
                )
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                vm.status,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            ActionBar(
                vm,
                // A download replaces the local mission, so ask first if there is one.
                onDownload = { if (vm.waypoints.isEmpty()) vm.downloadFromFc() else confirmDownload = true },
                onClear = { confirmClear = true },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = expanded && tab == 0, onClick = { tab = 0; expanded = true }, label = { Text("Point") })
                FilterChip(
                    selected = expanded && tab == 1,
                    onClick = { tab = 1; expanded = true },
                    label = { Text("List (${vm.waypoints.size})") },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide ▼" else "Show ▲") }
            }
            if (expanded) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = maxPanelHeight)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    val sel = vm.selected
                    if (tab == 0) {
                        if (sel != null && sel in vm.waypoints.indices) WaypointEditor(vm, sel)
                        else Text("Tap the map to add a waypoint, or tap a marker to edit it.", Modifier.padding(8.dp))
                    } else {
                        MissionTable(vm)
                    }
                }
            }
        }
    }

    if (showMapSettings) {
        MapSettingsDialog(
            initial = vm.mapConfig,
            promptForSatellite = openedForSatellite,
            onDismiss = { showMapSettings = false },
            onSave = { cfg ->
                // Opening Settings from the Satellite chip means the user wants satellite once it is usable.
                val next = if (openedForSatellite && cfg.satelliteReady) cfg.copy(layer = MapLayer.SATELLITE) else cfg
                vm.updateMapConfig(next)
                showMapSettings = false
            },
        )
    }

    if (showOffline) OfflineMapsDialog(vm, onDismiss = { showOffline = false })

    if (confirmDownload) {
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Replace mission?") },
            text = { Text("Downloading replaces the ${vm.waypoints.size} waypoints on this device with the mission from the FC.") },
            confirmButton = { TextButton(onClick = { confirmDownload = false; vm.downloadFromFc() }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Cancel") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear mission?") },
            text = { Text("All ${vm.waypoints.size} waypoints will be removed from this device.") },
            confirmButton = { TextButton(onClick = { vm.clear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionBar(vm: MissionViewModel, onDownload: () -> Unit, onClear: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { vm.uploadToFc(saveToEeprom = false) },
            enabled = !vm.busy && vm.waypoints.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) { Text("Upload") }
        OutlinedButton(onClick = onDownload, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Download") }
        Box {
            TextButton(onClick = { menu = true }) { Text("More ▾") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Upload and save to FC") },
                    enabled = !vm.busy && vm.waypoints.isNotEmpty(),
                    onClick = { menu = false; vm.uploadToFc(saveToEeprom = true) },
                )
                DropdownMenuItem(
                    text = { Text("Add RTH at end") },
                    enabled = vm.waypoints.isNotEmpty(),
                    onClick = { menu = false; vm.addRthAtEnd() },
                )
                DropdownMenuItem(
                    text = { Text("Clear mission") },
                    enabled = vm.waypoints.isNotEmpty(),
                    onClick = { menu = false; onClear() },
                )
            }
        }
    }
}

@Composable
private fun overlayChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    labelColor = MaterialTheme.colorScheme.onSurface,
)

/** Satellite imagery needs the user's own provider key or tile URL; nothing is bundled in the app. */
@Composable
private fun MapSettingsDialog(
    initial: MapConfig,
    promptForSatellite: Boolean,
    onDismiss: () -> Unit,
    onSave: (MapConfig) -> Unit,
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var url by remember { mutableStateOf(initial.customUrl) }
    val candidate = initial.copy(provider = provider, apiKey = key, customUrl = url)
    val urlProblem = provider == SatelliteProvider.CUSTOM && url.isNotBlank() && !MapConfig.isValidTemplate(url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Satellite imagery") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (promptForSatellite) Text("Satellite needs imagery from a provider you have an account with.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SatelliteProvider.entries.forEach { p ->
                        FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(p.label) })
                    }
                }
                if (provider == SatelliteProvider.CUSTOM) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, label = { Text("Tile URL (https, {z}/{x}/{y})") },
                        isError = urlProblem, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it.trim() }, label = { Text("${provider.label} API key") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "The key is stored only on this device. Providers set their own limits and terms, " +
                        "including whether tiles may be saved for offline use.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(candidate) }, enabled = !urlProblem) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
