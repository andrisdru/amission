package org.amission

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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

    /** Home position (lat, lon) for the "Grd Dist" row. Not known until set; null shows "NO HOME". */
    var home by mutableStateOf<Pair<Double, Double>?>(null)
        private set

    /** Last map camera centre, used by the table's "+" button. */
    private var mapCenter: Pair<Double, Double>? = null

    fun onMapCenterChanged(lat: Double, lon: Double) { mapCenter = lat to lon }

    fun addWaypoint(lat: Double, lon: Double) {
        val wp = Waypoint(
            number = 0, action = WaypointAction.WAYPOINT,
            latE7 = (lat * 1e7).roundToInt(), lonE7 = (lon * 1e7).roundToInt(),
            altitudeCm = waypoints.lastOrNull { it.action.hasPosition }?.altitudeCm ?: DEFAULT_ALTITUDE_CM,
        )
        update(waypoints + wp, sel = waypoints.size)
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

    fun downloadFromFc() = withFc("Downloading…") { client ->
        val wps = client.download()
        withContext(Dispatchers.Main) { update(wps, sel = null) }
        "Downloaded ${wps.size} waypoints from FC"
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

@Composable
private fun MissionScreen(vm: MissionViewModel = viewModel()) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    // Selecting a waypoint (map tap or table row) brings its editor up.
    LaunchedEffect(vm.selected) { if (vm.selected != null) { tab = 0; expanded = true } }
    val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp

    Column(Modifier.fillMaxSize()) {
        MissionMap(
            waypoints = vm.waypoints,
            selected = vm.selected,
            onMapTap = vm::addWaypoint,
            onSelect = vm::select,
            onCenterChanged = vm::onMapCenterChanged,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                vm.status,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            ActionBar(vm, onClear = { confirmClear = true })
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
private fun ActionBar(vm: MissionViewModel, onClear: () -> Unit) {
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
        OutlinedButton(onClick = vm::downloadFromFc, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Download") }
        Box {
            TextButton(onClick = { menu = true }) { Text("More ▾") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Upload and save to FC") },
                    enabled = !vm.busy && vm.waypoints.isNotEmpty(),
                    onClick = { menu = false; vm.uploadToFc(saveToEeprom = true) },
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
