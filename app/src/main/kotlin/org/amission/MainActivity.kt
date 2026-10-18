package org.amission

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amission.msp.GpsFix
import org.amission.msp.FlightProfileBuilder
import org.amission.msp.MissionClient
import org.amission.msp.MissionFileException
import org.amission.msp.MissionImport
import org.amission.msp.MissionXml
import org.amission.msp.PathVertex
import org.amission.msp.SerialMspLink
import org.amission.msp.TelemetryClient
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

    private val stored = store.load()
    private val prefs = app.getSharedPreferences("amission_prefs", Context.MODE_PRIVATE)

    var waypoints by mutableStateOf(stored.waypoints)
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
        persist()
        scheduleProfile()
    }

    private fun persist() = store.save(waypoints, home)

    /** Bumped to ask the map to zoom to the mission; starts at 1 when there is already a mission to show. */
    var fitRequest by mutableIntStateOf(if (waypoints.any { it.action.hasPosition }) 1 else 0)
        private set

    fun requestFit() { fitRequest++ }

    /**
     * Planned take-off point (lat, lon). Relative altitudes count from here, so it matters for the
     * height-above-ground check and the "Grd Dist" row. Null shows "NO HOME".
     */
    var home by mutableStateOf(stored.home)
        private set

    fun setHome(lat: Double, lon: Double) {
        home = lat to lon
        persist()
        scheduleProfile()
        status = "Home set. Relative altitudes now count from there."
    }

    fun setHomeAtMapCenter() {
        val c = mapCenter ?: return
        setHome(c.first, c.second)
    }

    fun setHomeAtMyPosition() {
        val l = locateResult
        if (l == null) { status = "Tap Locate first, then set home at your position."; return }
        setHome(l.lat, l.lon)
    }

    fun clearHome() {
        home = null
        persist()
        scheduleProfile()
        status = "Home cleared."
    }

    /** Height above the take-off point that a newly added waypoint starts at. */
    var defaultAltM by mutableIntStateOf(prefs.getInt("defaultAltM", DEFAULT_ALTITUDE_CM / 100))
        private set

    fun setDefaultAlt(m: Int) {
        defaultAltM = m.coerceIn(0, 500)
        prefs.edit().putInt("defaultAltM", defaultAltM).apply()
    }

    /** Warn when the plan is lower than this above the ground. */
    var minAglM by mutableIntStateOf(prefs.getInt("minAglM", DEFAULT_MIN_AGL_M))
        private set

    fun setMinAgl(m: Int) {
        minAglM = m.coerceIn(0, 500)
        prefs.edit().putInt("minAglM", minAglM).apply()
        scheduleProfile()
    }

    var profile by mutableStateOf<ProfileState>(ProfileState.Empty)
        private set

    val hasClearanceIssues: Boolean get() = (profile as? ProfileState.Ready)?.profile?.hasIssues == true

    private var profileJob: Job? = null

    /** Recomputes the terrain profile shortly after the last change, so typing an altitude does not spam lookups. */
    private fun scheduleProfile() {
        profileJob?.cancel()
        val path = waypoints.filter { it.action.isFlownTo }
            .map { PathVertex(it.number, it.latitude, it.longitude, it.altitudeCm / 100.0, it.isSeaLevelRef) }
        if (path.isEmpty()) { profile = ProfileState.Empty; return }
        val homePoint = home
        val minAgl = minAglM.toDouble()
        profileJob = viewModelScope.launch {
            delay(600)
            profile = ProfileState.Loading
            val samples = FlightProfileBuilder.sampleRoute(path)
            val start = homePoint ?: (path[0].lat to path[0].lon)
            val heights = ElevationService.elevations(listOf(start) + samples.map { it.lat to it.lon })
            profile = if (heights.any { it == null }) {
                ProfileState.NoData("Terrain heights are not available. They need internet the first time a route is checked.")
            } else {
                val h = heights.map { it!! }
                ProfileState.Ready(FlightProfileBuilder.build(path, samples, h.drop(1), h[0], minAgl), homeAssumed = homePoint == null)
            }
        }
    }

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
            altitudeCm = defaultAltM * 100,
        )
        update(waypoints + wp, sel = waypoints.size)
    }

    /** A mission read from a file, waiting for the user to confirm replacing the current one. */
    var pendingImport by mutableStateOf<MissionImport?>(null)
        private set

    /** The current mission as a Configurator-compatible file, centred on the visible map area. */
    fun missionXml(): String {
        val v = viewport
        return MissionXml.write(
            waypoints,
            centerLat = v?.let { (it.south + it.north) / 2 },
            centerLon = v?.let { (it.west + it.east) / 2 },
            zoom = v?.zoom?.toInt(),
            home = home,
        )
    }

    fun saveToFile(uri: Uri) {
        val xml = missionXml()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { MissionFiles.write(getApplication(), uri, xml) }
                status = "Saved ${waypoints.size} waypoints to ${MissionFiles.displayName(getApplication(), uri)}"
            } catch (e: Exception) {
                status = "Could not save the file: ${e.message}"
            }
        }
    }

    fun openFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { MissionXml.read(MissionFiles.read(getApplication(), uri)) }
                if (waypoints.isEmpty()) applyImport(imported) else pendingImport = imported
            } catch (e: MissionFileException) {
                status = "Could not open that file: ${e.message}"
            } catch (e: Exception) {
                status = "Could not read the file: ${e.message}"
            }
        }
    }

    fun confirmImport() {
        pendingImport?.let { applyImport(it) }
        pendingImport = null
    }

    fun cancelImport() { pendingImport = null }

    private fun applyImport(imported: MissionImport) {
        if (imported.home != null) home = imported.home
        update(imported.waypoints, sel = null)
        requestFit()
        val extra = if (imported.ignoredItems > 0) " (the file held more missions; only the first was opened)" else ""
        status = "Opened ${imported.waypoints.size} waypoints from file$extra"
    }

    /** Appends an RTH as the final point (altitude copied from the last positioned waypoint) and selects it. */
    fun addRthAtEnd() {
        if (waypoints.lastOrNull()?.action == WaypointAction.RTH) {
            selected = waypoints.lastIndex
            status = "Mission already ends with RTH"
            return
        }
        val alt = waypoints.lastOrNull { it.action.hasPosition }?.altitudeCm ?: (defaultAltM * 100)
        val rth = Waypoint(number = 0, action = WaypointAction.RTH, latE7 = 0, lonE7 = 0, altitudeCm = alt)
        update(waypoints + rth, sel = waypoints.size)
        status = "Added RTH as point ${waypoints.size}"
    }

    /** Latest position to centre on, with a marker; set by [locate]. */
    var locateResult by mutableStateOf<LocateResult?>(null)
        private set
    var locating by mutableStateOf(false)
        private set
    private var locateId = 0

    private sealed interface QuadGps {
        data class Fix(val gps: GpsFix) : QuadGps
        data class NoFix(val gps: GpsFix) : QuadGps
        data class Failed(val reason: String) : QuadGps
        data object NoDevice : QuadGps
    }

    /**
     * Centres the map on the quad's GPS when it is plugged in and reports a real fix, otherwise on
     * the phone's GPS. A quad powered only over USB often has no fix yet, so that is a normal case,
     * not an error.
     */
    fun locate() {
        if (locating) return
        viewModelScope.launch {
            locating = true
            status = "Finding position…"
            try {
                val quad = if (busy) QuadGps.NoDevice else readQuadGps()
                if (quad is QuadGps.Fix) {
                    val g = quad.gps
                    locateResult = LocateResult(g.latitude, g.longitude, "quad GPS (${g.satellites} satellites)", ++locateId)
                    status = "Centered on the quad's GPS (${g.satellites} satellites)"
                    return@launch
                }
                val why = when (quad) {
                    is QuadGps.NoFix -> "Quad has no GPS fix yet (${quad.gps.satellites} satellites). "
                    is QuadGps.Failed -> "Could not read the quad's GPS (${quad.reason}). "
                    else -> ""
                }
                val phone = PhoneLocation.current(getApplication())
                if (phone != null) {
                    locateResult = LocateResult(phone.latitude, phone.longitude, "phone GPS", ++locateId)
                    status = why + "Centered on the phone's GPS."
                } else {
                    val reason = if (PhoneLocation.hasPermission(getApplication())) {
                        "Phone location is unavailable. Turn on location services, or go outside."
                    } else {
                        "Allow location access to use the phone's GPS."
                    }
                    status = why + reason
                }
            } finally {
                locating = false
            }
        }
    }

    private suspend fun readQuadGps(): QuadGps {
        val app = getApplication<Application>()
        if (!UsbSerialChannel.isDeviceAttached(app)) return QuadGps.NoDevice
        return try {
            UsbSerialChannel.open(app).use { channel ->
                withContext(Dispatchers.IO) {
                    val gps = TelemetryClient(SerialMspLink(channel, timeoutMs = 1000, retries = 1)).gps()
                    if (gps.isUsable) QuadGps.Fix(gps) else QuadGps.NoFix(gps)
                }
            }
        } catch (e: Exception) {
            QuadGps.Failed(e.message ?: "error")
        }
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
                altitudeCm = if (old.altitudeCm != 0) old.altitudeCm else defaultAltM * 100)
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

    init {
        ElevationService.init(app)
        scheduleProfile()
    }

    private companion object {
        /** New waypoints start at 80 m above the take-off point. */
        const val DEFAULT_ALTITUDE_CM = 8000
        const val DEFAULT_MIN_AGL_M = 40
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
    var pendingUpload by remember { mutableStateOf<Boolean?>(null) }
    // Selecting a waypoint (map tap or table row) brings its editor up.
    LaunchedEffect(vm.selected) { if (vm.selected != null) { tab = 0; expanded = true } }
    val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp

    var showMapSettings by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.locate() }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MissionFiles.CREATE_MIME)) { uri -> if (uri != null) vm.saveToFile(uri) }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.openFile(uri) }
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
                locate = vm.locateResult,
                home = vm.home,
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
                    onClick = {
                        // Ask once; either way try, because the quad's GPS needs no phone permission.
                        if (PhoneLocation.hasPermission(context)) vm.locate() else locationPermission.launch(PhoneLocation.permissions)
                    },
                    label = { Text("Locate") },
                    enabled = !vm.locating,
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
                onSaveFile = { saveFile.launch(MissionFiles.suggestedName()) },
                onOpenFile = { openFile.launch(arrayOf("*/*")) },
                onShare = { context.startActivity(MissionFiles.shareChooser(context, vm.missionXml())) },
                onAbout = { showAbout = true },
                onUpload = { save ->
                    // Low clearance is a warning, not a block: ask once, then let the user decide.
                    if (vm.hasClearanceIssues) pendingUpload = save else vm.uploadToFc(save)
                },
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
                FilterChip(
                    selected = expanded && tab == 2,
                    onClick = { tab = 2; expanded = true },
                    label = { Text(if (vm.hasClearanceIssues) "Profile ⚠" else "Profile") },
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
                        else Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tap the map to add a waypoint, or tap a marker to edit it.")
                            NumberField("New point alt (m):", vm.defaultAltM.toString(), intOnly = true) { s ->
                                s.toIntOrNull()?.let { vm.setDefaultAlt(it) }
                            }
                        }
                    } else if (tab == 1) {
                        MissionTable(vm)
                    } else {
                        ProfileTab(vm)
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
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    pendingUpload?.let { save ->
        val worst = (vm.profile as? ProfileState.Ready)?.profile?.issues?.minByOrNull { it.lowestAglM }
        AlertDialog(
            onDismissRequest = { pendingUpload = null },
            title = { Text("Low flight height") },
            text = {
                Text(
                    "Part of the route is lower than ${vm.minAglM} m above ground" +
                        (worst?.let { ": the lowest is ${it.lowestAglM.toInt()} m near point ${it.nearWaypoint}" } ?: "") +
                        ". Check the Profile tab. Upload anyway?",
                )
            },
            confirmButton = { TextButton(onClick = { pendingUpload = null; vm.uploadToFc(save) }) { Text("Upload anyway") } },
            dismissButton = {
                TextButton(onClick = { pendingUpload = null; tab = 2; expanded = true }) { Text("Show profile") }
            },
        )
    }

    if (confirmDownload) {
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Replace mission?") },
            text = { Text("Downloading replaces the ${vm.waypoints.size} waypoints on this device with the mission from the FC.") },
            confirmButton = { TextButton(onClick = { confirmDownload = false; vm.downloadFromFc() }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Cancel") } },
        )
    }

    vm.pendingImport?.let { imp ->
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text("Replace mission?") },
            text = { Text("Opening the file replaces the ${vm.waypoints.size} waypoints on this device with ${imp.waypoints.size} from the file.") },
            confirmButton = { TextButton(onClick = vm::confirmImport) { Text("Open") } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Cancel") } },
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
private fun ActionBar(
    vm: MissionViewModel,
    onDownload: () -> Unit,
    onClear: () -> Unit,
    onSaveFile: () -> Unit,
    onOpenFile: () -> Unit,
    onShare: () -> Unit,
    onUpload: (saveToEeprom: Boolean) -> Unit,
    onAbout: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { onUpload(true) },
            enabled = !vm.busy && vm.waypoints.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) { Text("Upload & save") }
        OutlinedButton(onClick = onDownload, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Download") }
        Box {
            TextButton(onClick = { menu = true }) { Text("More ▾") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Upload without saving (FC memory only)") },
                    enabled = !vm.busy && vm.waypoints.isNotEmpty(),
                    onClick = { menu = false; onUpload(false) },
                )
                DropdownMenuItem(
                    text = { Text("Save to file (phone, Drive, pCloud…)") },
                    enabled = vm.waypoints.isNotEmpty(),
                    onClick = { menu = false; onSaveFile() },
                )
                DropdownMenuItem(
                    text = { Text("Open from file…") },
                    onClick = { menu = false; onOpenFile() },
                )
                DropdownMenuItem(
                    text = { Text("Share…") },
                    enabled = vm.waypoints.isNotEmpty(),
                    onClick = { menu = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text("Set home at map center") },
                    onClick = { menu = false; vm.setHomeAtMapCenter() },
                )
                DropdownMenuItem(
                    text = { Text("Set home at my position (after Locate)") },
                    enabled = vm.locateResult != null,
                    onClick = { menu = false; vm.setHomeAtMyPosition() },
                )
                DropdownMenuItem(
                    text = { Text("Clear home") },
                    enabled = vm.home != null,
                    onClick = { menu = false; vm.clearHome() },
                )
                DropdownMenuItem(
                    text = { Text("Add RTH at end") },
                    enabled = vm.waypoints.isNotEmpty(),
                    onClick = { menu = false; vm.addRthAtEnd() },
                )
                DropdownMenuItem(
                    text = { Text("About, licence and credits") },
                    onClick = { menu = false; onAbout() },
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
