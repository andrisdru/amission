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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.amission.msp.AltitudeMode
import org.amission.msp.GpsFix
import org.amission.msp.GroundHeight
import org.amission.msp.FlightProfileBuilder
import org.amission.msp.Geo
import org.amission.msp.MissionCheck
import org.amission.msp.MissionClient
import org.amission.msp.MissionFileException
import org.amission.msp.MissionImport
import org.amission.msp.MissionIssue
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

    fun setHome(lat: Double, lon: Double, source: String) {
        home = Geo.clampLatitude(lat) to Geo.normalizeLongitude(lon)
        persist()
        scheduleProfile()
        status = "Home set from $source. Relative altitudes now count from there."
    }

    /** Lets a dialog put a message on the status line. */
    fun reportStatus(message: String) { status = message }

    /** True while a GPS read for the home point is running. */
    var homeBusy by mutableStateOf(false)
        private set

    /** Returns true if home was set. */
    fun setHomeAtMapCenter(): Boolean {
        val c = mapCenter ?: run { status = "Move the map first."; return false }
        setHome(c.first, c.second, "the map centre")
        return true
    }

    fun setHomeFromPhoneGps(onDone: () -> Unit) {
        if (homeBusy) return
        viewModelScope.launch {
            homeBusy = true
            status = "Finding the phone's position…"
            try {
                val loc = PhoneLocation.current(getApplication())
                if (loc == null) {
                    status = if (PhoneLocation.hasPermission(getApplication())) {
                        "The phone has no position yet. Turn on location services, or go outside."
                    } else {
                        "Allow location access to use the phone's GPS."
                    }
                    return@launch
                }
                locateResult = LocateResult(loc.latitude, loc.longitude, "phone GPS", ++locateId)
                val accuracy = if (loc.hasAccuracy()) " (accurate to about ${loc.accuracy.toInt()} m)" else ""
                setHome(loc.latitude, loc.longitude, "the phone's GPS$accuracy")
                onDone()
            } finally {
                homeBusy = false
            }
        }
    }

    fun setHomeFromQuadGps(onDone: () -> Unit) {
        if (homeBusy) return
        if (busy) { status = "Wait for the current transfer to the flight controller to finish."; return }
        viewModelScope.launch {
            homeBusy = true
            status = "Reading the quad's GPS…"
            try {
                when (val q = readQuadGps()) {
                    is QuadGps.Fix -> {
                        val g = q.gps
                        locateResult = LocateResult(g.latitude, g.longitude, "quad GPS", ++locateId)
                        setHome(g.latitude, g.longitude, "the quad's GPS (${g.satellites} satellites)")
                        onDone()
                    }
                    is QuadGps.NoFix ->
                        status = "The quad has no GPS fix yet (${q.gps.satellites} satellites). Wait for a fix and try again, or use the phone's GPS."
                    is QuadGps.Failed -> status = "Could not read the quad's GPS (${q.reason})."
                    QuadGps.NoDevice ->
                        status = "No quad to read. Plug it in with USB, or choose its Bluetooth module in More ▸ Bluetooth connection."
                }
            } finally {
                homeBusy = false
            }
        }
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

    /** Height of the lower panel in dp; 0 means "use the default for this screen". */
    var panelHeightDp by mutableFloatStateOf(prefs.getFloat("panelHeightDp", 0f))
        private set

    /** Moves the panel edge by [deltaDp] (positive = up, so taller). Always starts from the current height, never a stale one. */
    fun dragPanel(deltaDp: Float, defaultDp: Float, minDp: Float, maxDp: Float) {
        val current = if (panelHeightDp > 0f) panelHeightDp else defaultDp
        panelHeightDp = (current + deltaDp).coerceIn(minDp, maxDp)
    }

    fun savePanelHeight() { prefs.edit().putFloat("panelHeightDp", panelHeightDp).apply() }

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

    /** Planning mistakes such as points after an RTH. Cheap, so recomputed on read. */
    val missionIssues: List<MissionIssue> get() = MissionCheck.check(waypoints)

    /** Everything worth stopping for before an upload, in plain words. Empty means nothing to warn about. */
    fun uploadWarnings(): List<String> {
        val out = ArrayList<String>()
        missionIssues.forEach { out += it.message }
        val worst = (profile as? ProfileState.Ready)?.profile?.issues?.minByOrNull { it.lowestAglM }
        if (worst != null) {
            out += "Part of the route is lower than $minAglM m above ground: the lowest is ${worst.lowestAglM.toInt()} m near point ${worst.nearWaypoint}."
        }
        return out
    }

    private var profileJob: Job? = null

    /** Recomputes the terrain profile shortly after the last change, so typing an altitude does not spam lookups. */
    private fun scheduleProfile() {
        scheduleGround()
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
    val connections = FcConnections(app)

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
        exportBlocker()?.let { status = it; return }
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
        if (!connections.canReachFc()) return QuadGps.NoDevice
        return try {
            connections.open().use { link ->
                withContext(Dispatchers.IO) {
                    val gps = TelemetryClient(SerialMspLink(link.channel, timeoutMs = 1200, retries = 1)).gps()
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

    /**
     * Called continuously while a marker is dragged on the map: moves it on screen but neither saves nor looks up
     * terrain, which would be hundreds of times per drag. [moveWaypointDone] does both once the finger lifts.
     */
    fun moveWaypointLive(index: Int, lat: Double, lon: Double) {
        if (index !in waypoints.indices) return
        val la = Geo.clampLatitude(lat)
        val lo = Geo.normalizeLongitude(lon)
        waypoints = waypoints.toMutableList().also {
            it[index] = it[index].copy(latE7 = (la * 1e7).roundToInt(), lonE7 = (lo * 1e7).roundToInt())
        }
    }

    fun moveWaypointDone(index: Int) {
        if (index !in waypoints.indices) return
        persist()
        scheduleProfile()
        status = "Moved point ${index + 1}"
    }

    // ---- altitude measured from the ground under a point ("Ground" mode) ----

    var groundStatus by mutableStateOf(GroundStatus.IDLE)
        private set

    private var groundJob: Job? = null

    private fun homeKeyNow(): String = GroundHeight.homeKey(home, waypoints.firstOrNull { it.action.hasPosition })

    /** The point that altitudes count from: the planned home, or point 1 when no home is set. */
    private fun homeRefPoint(): Pair<Double, Double>? =
        home ?: waypoints.firstOrNull { it.action.hasPosition }?.let { it.latitude to it.longitude }

    /** Ground-mode points whose stored altitude is out of date and must be converted again. */
    fun pendingGround(): List<Waypoint> {
        val key = homeKeyNow()
        return waypoints.filter { it.action.hasPosition && !GroundHeight.isResolved(it, key) }
    }

    /** True when this point is not in ground mode, or its stored altitude is current. */
    fun isGroundResolved(w: Waypoint): Boolean = GroundHeight.isResolved(w, homeKeyNow())

    /**
     * Converts every out-of-date ground-mode point into the home-relative altitude INAV needs, using terrain heights.
     * The lookup needs internet unless those heights were fetched before (they are kept on the phone).
     */
    private fun scheduleGround() {
        groundJob?.cancel()
        val key = homeKeyNow()
        val ref = homeRefPoint()
        val pending = waypoints.withIndex().filter { it.value.action.hasPosition && !GroundHeight.isResolved(it.value, key) }
        if (pending.isEmpty() || ref == null) { groundStatus = GroundStatus.IDLE; return }
        groundJob = viewModelScope.launch {
            // Stay "unavailable" while retrying, so the screen does not flicker between the two.
            if (groundStatus != GroundStatus.UNAVAILABLE) groundStatus = GroundStatus.LOOKING
            delay(400)
            val heights = ElevationService.elevations(listOf(ref) + pending.map { it.value.latitude to it.value.longitude })
            if (heights.any { it == null }) {
                // No internet (or the service is down). Keep trying while the app is open: the connection may come back.
                groundStatus = GroundStatus.UNAVAILABLE
                delay(GROUND_RETRY_MS)
                scheduleGround()
                return@launch
            }
            if (homeKeyNow() != key) { scheduleGround(); return@launch } // home changed while looking up: start over
            val homeElevation = heights[0]!!
            val current = waypoints.toMutableList()
            pending.forEachIndexed { n, (i, w) ->
                val now = current.getOrNull(i) ?: return@forEachIndexed
                // Only convert points that did not change during the lookup; a changed point is handled by the next pass.
                if (now.latE7 == w.latE7 && now.lonE7 == w.lonE7 && now.groundHeightM == w.groundHeightM) {
                    current[i] = GroundHeight.resolve(now, heights[n + 1]!!, homeElevation, key)
                }
            }
            groundStatus = GroundStatus.IDLE
            update(current)
        }
    }

    /** Why the mission cannot be uploaded, saved or shared right now, or null if it can. */
    fun exportBlocker(): String? {
        if (pendingGround().isEmpty()) return null
        return when (groundStatus) {
            GroundStatus.LOOKING -> "Ground heights are still being looked up. Wait a moment and try again."
            GroundStatus.UNAVAILABLE ->
                "Some points are set to Ground and their ground heights could not be looked up. That needs internet the " +
                    "first time. Connect to the internet, or switch those points to Home."
            GroundStatus.IDLE -> "Ground heights are not ready yet. Wait a moment and try again."
        }
    }

    /** Changes what the selected point's altitude is measured from, keeping its real height where that can be worked out. */
    fun setAltitudeMode(mode: AltitudeMode) {
        val i = selected ?: return
        val before = waypoints.getOrNull(i) ?: return
        if (before.altitudeMode == mode) return
        viewModelScope.launch {
            val ref = homeRefPoint()
            // Cached heights answer at once; a slow or missing connection must not freeze the switch.
            val heights = if (ref != null) {
                withTimeoutOrNull(2_000) { ElevationService.elevations(listOf(ref, before.latitude to before.longitude)) }
            } else {
                null
            }
            val now = waypoints.getOrNull(i) ?: return@launch
            if (now.latE7 != before.latE7 || now.lonE7 != before.lonE7 || now.altitudeMode != before.altitudeMode) return@launch
            val switched = GroundHeight.switchMode(now, mode, heights?.getOrNull(0), heights?.getOrNull(1), homeKeyNow())
            update(waypoints.toMutableList().also { it[i] = switched })
            status = when (mode) {
                AltitudeMode.GROUND ->
                    "Ground mode works online only: ground heights are looked up on the internet the first time, then kept on this phone."
                AltitudeMode.SEA_LEVEL -> "Altitude is now measured from sea level."
                AltitudeMode.HOME -> "Altitude is now measured from the take-off point."
            }
        }
    }

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
            w.copy(
                latE7 = 0, lonE7 = 0, altitudeCm = if (action == WaypointAction.RTH) old.altitudeCm else 0, p3 = 0,
                groundHeightM = null, groundBasis = null, // no height, so nothing to measure from the ground
            )
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

    fun uploadToFc(saveToEeprom: Boolean) {
        // Never upload a guess: points set to Ground must have been converted from real terrain heights first.
        exportBlocker()?.let { status = it; return }
        withFc("Uploading…") { client ->
            client.uploadAndVerify(waypoints)
            if (saveToEeprom) client.saveToEeprom()
            if (saveToEeprom) "Uploaded, verified and saved to FC" else "Uploaded and verified (not saved to EEPROM)"
        }
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
                status = connections.open().use { link ->
                    status = "$progress (${link.description})"
                    withContext(Dispatchers.IO) { block(MissionClient(SerialMspLink(link.channel, timeoutMs = if (link.description.startsWith("USB")) 1000 else 1500))) }
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
    var uploadWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    // Selecting a waypoint (map tap or table row) brings its editor up.
    LaunchedEffect(vm.selected) { if (vm.selected != null) { tab = 0; expanded = true } }
    val screenDp = LocalConfiguration.current.screenHeightDp
    // The lower panel's content height is the user's to choose (drag the handle). The fixed parts around it (status, buttons,
    // tabs, handle, system bars) and a minimum strip of map set the upper limit.
    val maxPanelDp = (screenDp - PANEL_RESERVED_DP).coerceAtLeast(PANEL_MIN_DP)
    val defaultPanelDp = screenDp * 0.40f
    val panelDp = (if (vm.panelHeightDp > 0f) vm.panelHeightDp else defaultPanelDp).coerceIn(PANEL_MIN_DP, maxPanelDp)
    val density = LocalDensity.current.density

    var showMapSettings by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showBluetooth by remember { mutableStateOf(false) }
    var showHome by remember { mutableStateOf(false) }
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
                onMoveWaypoint = vm::moveWaypointLive,
                onMoveEnd = vm::moveWaypointDone,
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
            PanelHandle(
                onDrag = { dyPx ->
                    // Dragging up makes the panel taller; a hidden panel opens as soon as it is pulled up.
                    if (!expanded && dyPx < 0f) expanded = true
                    vm.dragPanel(-dyPx / density, defaultPanelDp, PANEL_MIN_DP, maxPanelDp)
                },
                onDragEnd = vm::savePanelHeight,
            )
            Text(
                vm.status,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            ActionBar(
                vm,
                // A download replaces the local mission, so ask first if there is one.
                onDownload = { if (vm.waypoints.isEmpty()) vm.downloadFromFc() else confirmDownload = true },
                onClear = { confirmClear = true },
                onSaveFile = { vm.exportBlocker()?.let(vm::reportStatus) ?: saveFile.launch(MissionFiles.suggestedName()) },
                onOpenFile = { openFile.launch(arrayOf("*/*")) },
                onShare = { vm.exportBlocker()?.let(vm::reportStatus) ?: context.startActivity(MissionFiles.shareChooser(context, vm.missionXml())) },
                onAbout = { showAbout = true },
                onBluetooth = { showBluetooth = true },
                onHome = { showHome = true },
                onUpload = { save ->
                    // Warnings never block an upload: show them once, then let the user decide.
                    val warnings = vm.uploadWarnings()
                    if (warnings.isNotEmpty()) { uploadWarnings = warnings; pendingUpload = save } else vm.uploadToFc(save)
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
                    label = { Text("List (${vm.waypoints.size})" + if (vm.missionIssues.isNotEmpty()) " ⚠" else "") },
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
                    Modifier.fillMaxWidth().height(panelDp.dp)
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
    if (showBluetooth) BluetoothDialog(vm, onDismiss = { showBluetooth = false })
    if (showHome) HomeDialog(vm, onDismiss = { showHome = false })

    pendingUpload?.let { save ->
        AlertDialog(
            onDismissRequest = { pendingUpload = null },
            title = { Text("Check before uploading") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uploadWarnings.forEach { Text("⚠ $it") }
                    Text("Upload anyway?")
                }
            },
            confirmButton = { TextButton(onClick = { pendingUpload = null; vm.uploadToFc(save) }) { Text("Upload anyway") } },
            dismissButton = {
                TextButton(onClick = {
                    pendingUpload = null
                    expanded = true
                    tab = if (vm.missionIssues.isNotEmpty()) 1 else 2
                }) { Text("Show me") }
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
    onBluetooth: () -> Unit,
    onHome: () -> Unit,
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
                    text = { Text(if (vm.home == null) "Home point… (not set)" else "Home point…") },
                    onClick = { menu = false; onHome() },
                )
                DropdownMenuItem(
                    text = { Text("Add RTH at end") },
                    enabled = vm.waypoints.isNotEmpty(),
                    onClick = { menu = false; vm.addRthAtEnd() },
                )
                DropdownMenuItem(
                    text = { Text("Bluetooth connection…") },
                    onClick = { menu = false; onBluetooth() },
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

private const val PANEL_MIN_DP = 90f
// Status line, button row, tab row, handle, system bars and a strip of map that always stays visible.
private const val PANEL_RESERVED_DP = 360f

/** Grab bar between the map and the lower panel; drag it up or down to give the map or the panel more room. */
@Composable
private fun PanelHandle(onDrag: (dyPx: Float) -> Unit, onDragEnd: () -> Unit) {
    // The gesture block below is created once, so it must call the latest callbacks, not the ones from the first frame.
    val latestDrag by rememberUpdatedState(onDrag)
    val latestEnd by rememberUpdatedState(onDragEnd)
    Box(
        Modifier.fillMaxWidth().height(28.dp)
            .semantics { contentDescription = "Resize panel: drag up or down" }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { latestEnd() },
                    onDragCancel = { latestEnd() },
                    onVerticalDrag = { change, dy -> change.consume(); latestDrag(dy) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(48.dp).height(5.dp)
                .background(MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)),
        )
    }
}

/** Progress of converting "above ground" points into the altitude INAV stores. */
enum class GroundStatus { IDLE, LOOKING, UNAVAILABLE }

/** How often the app tries again to look up ground heights after a failure. */
private const val GROUND_RETRY_MS = 15_000L
