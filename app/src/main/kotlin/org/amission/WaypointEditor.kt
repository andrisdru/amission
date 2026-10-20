package org.amission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.amission.msp.AltitudeMode
import org.amission.msp.Geo
import org.amission.msp.GroundHeight
import org.amission.msp.Units
import org.amission.msp.Waypoint
import org.amission.msp.WaypointAction
import java.util.Locale
import kotlin.math.roundToInt

private val LabelWidth = 120.dp
private val FieldWidth = 170.dp
private val TableIndent = 72.dp
private val MoveWidth = 72.dp

/** "Edit point N" panel, modelled on the INAV Configurator mission control panel. */
@Composable
fun WaypointEditor(vm: MissionViewModel, index: Int) {
    val wp = vm.waypoints[index]

    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Edit point ${wp.number}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(
                onClick = vm::deleteSelected,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            ) { Text("Delete") }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TypeRow(wp.action) { vm.setSelectedType(it) }
            if (wp.action.hasPosition) PositionFields(vm, wp)
            ActionFields(vm, wp)
            if (wp.action.hasPosition || wp.action == WaypointAction.RTH) UserActionsRow(vm, wp)
        }
    }
}

@Composable
private fun PositionFields(vm: MissionViewModel, wp: Waypoint) {
    NumberField("Lat:", formatCoord(wp.latE7)) { s ->
        s.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }?.let { v -> vm.updateSelected { it.copy(latE7 = (v * 1e7).roundToInt()) } }
    }
    NumberField("Lon:", formatCoord(wp.lonE7)) { s ->
        s.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }?.let { v -> vm.updateSelected { it.copy(lonE7 = (v * 1e7).roundToInt()) } }
    }
    AltitudeModeRow(vm, wp)
    if (wp.altitudeMode == AltitudeMode.GROUND) GroundFields(vm, wp) else AltitudeFields(vm, wp)
    ElevationRow(wp)
    GroundDistanceRow(vm, wp)
}

/** What the altitude is measured from: the take-off point (default), sea level, or the ground under the point. */
@Composable
private fun AltitudeModeRow(vm: MissionViewModel, wp: Waypoint) {
    LabeledRow("Height from:") {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(AltitudeMode.HOME to "Home", AltitudeMode.SEA_LEVEL to "Sea level", AltitudeMode.GROUND to "Ground").forEach { (mode, label) ->
                FilterChip(
                    selected = wp.altitudeMode == mode,
                    onClick = { vm.setAltitudeMode(mode) },
                    label = { Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 2, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Height above the ground under the point. INAV only knows "above the take-off point" and "above sea level", so this is
 * converted using terrain heights, which come from the internet the first time (then they are kept on the phone).
 */
@Composable
private fun GroundFields(vm: MissionViewModel, wp: Waypoint) {
    NumberField("Above ground (m):", (wp.groundHeightM ?: 0).toString(), intOnly = true) { s ->
        s.toIntOrNull()?.takeIf { it in GroundHeight.MIN_GROUND_M..GroundHeight.MAX_GROUND_M }
            ?.let { v -> vm.updateSelected { it.copy(groundHeightM = v, groundBasis = null) } }
    }
    val resolved = vm.isGroundResolved(wp)
    val problem = !resolved && vm.groundStatus == GroundStatus.UNAVAILABLE
    LabeledRow("Stored as:") {
        Text(
            when {
                resolved -> "${formatMeters(wp.altitudeCm)} above take-off"
                problem -> "waiting for internet (keeps trying)"
                else -> "looking up the ground…"
            },
            color = if (problem) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
    Text(
        "⚠ Ground works online only. The ground height under each point is looked up on the internet the first time and " +
            "then kept on this phone. Until it is known, this mission cannot be uploaded or saved.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        "The quad flies the converted number as a fixed height, it does not follow the ground. Terrain data is bare " +
            "ground at about 90 m resolution, without trees or buildings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AltitudeFields(vm: MissionViewModel, wp: Waypoint) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberField("Alt (cm):", wp.altitudeCm.toString(), intOnly = true) { s ->
            s.toIntOrNull()?.takeIf { it in -1_000_000..1_000_000 }?.let { v -> vm.updateSelected { it.copy(altitudeCm = v) } }
        }
        Text(formatMeters(wp.altitudeCm), Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SeaLevelRow(vm: MissionViewModel, wp: Waypoint) {
    LabeledRow("Sea level Ref:") {
        Switch(checked = wp.isSeaLevelRef, onCheckedChange = { on -> vm.updateSelected { it.withSeaLevelRef(on) } })
    }
}

@Composable
private fun ElevationRow(wp: Waypoint) {
    val elevation by produceState<String>("…", wp.latE7, wp.lonE7) {
        value = ElevationService.elevationMeters(wp.latitude, wp.longitude)?.toString() ?: "—"
    }
    LabeledRow("Elevation (m):") { Text(elevation) }
}

@Composable
private fun GroundDistanceRow(vm: MissionViewModel, wp: Waypoint) {
    val home = vm.home
    LabeledRow("Grd Dist (m):") {
        Text(
            if (home == null) "NO HOME"
            else Geo.distanceMeters(home.first, home.second, wp.latitude, wp.longitude).roundToInt().toString()
        )
    }
}

/** Fields whose meaning depends on the action type (p1/p2 in the wire format). */
@Composable
private fun ActionFields(vm: MissionViewModel, wp: Waypoint) {
    when (wp.action) {
        WaypointAction.WAYPOINT ->
            SpeedField(wp.p1) { v -> vm.updateSelected { it.copy(p1 = v) } }
        WaypointAction.POSHOLD_TIME -> {
            NumberField("Time (s):", wp.p1.toString(), intOnly = true) { s ->
                s.toIntOrNull()?.takeIf { it in 0..32_767 }?.let { v -> vm.updateSelected { it.copy(p1 = v) } }
            }
            SpeedField(wp.p2) { v -> vm.updateSelected { it.copy(p2 = v) } }
        }
        WaypointAction.RTH -> {
            // INAV starts its normal return-to-home here and does not read this entry's altitude, so none is offered.
            LabeledRow("Land after RTH:") {
                Switch(checked = wp.p1 != 0, onCheckedChange = { on -> vm.updateSelected { it.copy(p1 = if (on) 1 else 0) } })
            }
        }
        WaypointAction.JUMP -> {
            NumberField("Target point:", wp.p1.toString(), intOnly = true) { s ->
                s.toIntOrNull()?.takeIf { it in 1..120 }?.let { v -> vm.updateSelected { it.copy(p1 = v) } }
            }
            NumberField("Repeat (-1 = ∞):", wp.p2.toString(), intOnly = true) { s ->
                s.toIntOrNull()?.takeIf { it in -1..32_767 }?.let { v -> vm.updateSelected { it.copy(p2 = v) } }
            }
        }
        WaypointAction.SET_HEAD ->
            NumberField("Heading (°, -1 = off):", wp.p1.toString(), intOnly = true) { s ->
                s.toIntOrNull()?.takeIf { it in -1..359 }?.let { v -> vm.updateSelected { it.copy(p1 = v) } }
            }
        WaypointAction.SET_POI, WaypointAction.LAND -> {}
    }
}

/**
 * User actions 1-4 (p3 bits 1-4). The FC does nothing with them by itself: they are read by
 * logic conditions ("Waypoint user action N") and the optional VTOL mission transition.
 */
@Composable
private fun UserActionsRow(vm: MissionViewModel, wp: Waypoint) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LabeledRow("User Actions:") {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in 1..4) {
                    FilterChip(
                        selected = wp.userAction(n),
                        onClick = { vm.updateSelected { it.withUserAction(n, !wp.userAction(n)) } },
                        label = { Text("$n", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Text(
            "Only take effect through a logic condition (Programming tab).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TypeRow(current: WaypointAction, onSelect: (WaypointAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LabeledRow("Type:") {
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.width(FieldWidth)) {
                Text(current.label, Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                WaypointAction.entries.forEach { a ->
                    DropdownMenuItem(text = { Text(a.label) }, onClick = { open = false; onSelect(a) })
                }
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(LabelWidth))
        content()
    }
}

/**
 * Text field that edits a number. It keeps its own text while focused so partial input such as
 * "56." is not rewritten, and re-syncs from the model when focus is elsewhere.
 */
@Composable
internal fun NumberField(label: String, value: String, intOnly: Boolean = false, onValid: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(LabelWidth))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onValid(it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (intOnly) KeyboardType.Number else KeyboardType.Decimal),
            modifier = Modifier.width(FieldWidth).onFocusChanged { focused = it.isFocused },
        )
    }
}

/** Points table at the bottom of the Configurator panel: #, Type, P1, P2, with an add button. */
@Composable
fun MissionTable(vm: MissionViewModel) {
    Column(Modifier.fillMaxWidth()) {
        MissionProblems(vm)
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF868E8A)).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = vm::addAtMapCenter,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E9E4F)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.padding(start = 4.dp).width(TableIndent - 8.dp),
            ) { Text("+") }
            HeaderCell("#", 0.5f); HeaderCell("Type", 2f); HeaderCell("P1", 1f); HeaderCell("P2", 1f); Box(Modifier.width(MoveWidth))
        }
        vm.waypoints.forEachIndexed { i, w ->
            val bg = if (i == vm.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            Row(
                Modifier.fillMaxWidth().background(bg).clickable { vm.select(i) }.padding(vertical = 6.dp),
            ) {
                Box(Modifier.width(TableIndent))
                Cell("${w.number}", 0.5f); Cell(w.action.label, 2f); Cell("${w.p1}", 1f); Cell("${w.p2}", 1f)
                Row(Modifier.width(MoveWidth)) {
                    Text("▲", Modifier.clickable(enabled = i > 0) { vm.moveEntry(i, -1) }.padding(horizontal = 8.dp),
                        color = if (i > 0) MaterialTheme.colorScheme.primary else Color.Transparent)
                    Text("▼", Modifier.clickable(enabled = i < vm.waypoints.lastIndex) { vm.moveEntry(i, 1) }.padding(horizontal = 8.dp),
                        color = if (i < vm.waypoints.lastIndex) MaterialTheme.colorScheme.primary else Color.Transparent)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) =
    Text(text, Modifier.weight(weight), color = Color.White, style = MaterialTheme.typography.labelLarge)

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float) =
    Text(text, Modifier.weight(weight))

private fun formatCoord(e7: Int): String =
    String.format(Locale.US, "%.7f", e7 / 1e7).trimEnd('0').trimEnd('.')

private fun formatMeters(cm: Int): String =
    if (cm % 100 == 0) "${cm / 100}m" else String.format(Locale.US, "%.2fm", cm / 100.0)

/** Things the FC would accept but that probably are not what was planned, e.g. points after an RTH. */
@Composable
fun MissionProblems(vm: MissionViewModel) {
    val issues = vm.missionIssues
    if (issues.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        issues.forEach { issue ->
            Text(
                "⚠ ${issue.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().let { m -> issue.pointNumber?.let { p -> m.clickable { vm.select(p - 1) } } ?: m },
            )
        }
    }
}

/** Waypoint speed in cm/s as INAV stores it, with the same speed in km/h beside it, like the metres beside the altitude. */
@Composable
private fun SpeedField(cmPerSec: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberField("Speed (cm/s)", cmPerSec.toString(), intOnly = true) { s ->
            s.toIntOrNull()?.takeIf { it in 0..10_000 }?.let(onChange)
        }
        Text(Units.formatKmh(cmPerSec), Modifier.padding(start = 8.dp))
    }
    if (cmPerSec < Units.MIN_WAYPOINT_SPEED_CM_S) {
        Text(
            "A multicopter ignores speeds under ${Units.MIN_WAYPOINT_SPEED_CM_S} cm/s (${Units.formatKmh(Units.MIN_WAYPOINT_SPEED_CM_S)}) " +
                "and flies at its own default speed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
