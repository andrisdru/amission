package org.amission

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
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

    fun addWaypoint(lat: Double, lon: Double) {
        val wp = Waypoint(
            number = 0, action = WaypointAction.WAYPOINT,
            latE7 = (lat * 1e7).roundToInt(), lonE7 = (lon * 1e7).roundToInt(),
            altitudeCm = waypoints.lastOrNull()?.altitudeCm ?: DEFAULT_ALTITUDE_CM,
        )
        update(waypoints + wp, sel = waypoints.size)
    }

    fun select(index: Int) { selected = index }

    fun setSelectedAltitudeMeters(meters: Int) {
        val i = selected ?: return
        update(waypoints.toMutableList().also { it[i] = it[i].copy(altitudeCm = meters * 100) })
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissionScreen(vm: MissionViewModel = viewModel()) {
    Column(Modifier.fillMaxSize()) {
        MissionMap(
            waypoints = vm.waypoints,
            selected = vm.selected,
            onMapTap = vm::addWaypoint,
            onSelect = vm::select,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(vm.status, style = MaterialTheme.typography.bodyMedium)
            Text("${vm.waypoints.size} waypoints", style = MaterialTheme.typography.labelMedium)

            val sel = vm.selected
            if (sel != null && sel in vm.waypoints.indices) {
                SelectedWaypointRow(vm, sel)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.uploadToFc(saveToEeprom = false) },
                    enabled = !vm.busy && vm.waypoints.isNotEmpty(),
                ) { Text("Upload") }
                OutlinedButton(
                    onClick = { vm.uploadToFc(saveToEeprom = true) },
                    enabled = !vm.busy && vm.waypoints.isNotEmpty(),
                ) { Text("Upload + save") }
                OutlinedButton(onClick = vm::downloadFromFc, enabled = !vm.busy) { Text("Download") }
                OutlinedButton(onClick = vm::clear, enabled = vm.waypoints.isNotEmpty()) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun SelectedWaypointRow(vm: MissionViewModel, index: Int) {
    val wp = vm.waypoints[index]
    var text by remember { mutableStateOf((wp.altitudeCm / 100).toString()) }
    LaunchedEffect(index, wp.altitudeCm) { text = (wp.altitudeCm / 100).toString() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("WP ${wp.number}", Modifier.padding(top = 16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                text = v
                v.toIntOrNull()?.let { vm.setSelectedAltitudeMeters(it.coerceIn(0, 500)) }
            },
            label = { Text("Altitude (m)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = vm::deleteSelected, modifier = Modifier.padding(top = 8.dp)) { Text("Delete") }
    }
}
