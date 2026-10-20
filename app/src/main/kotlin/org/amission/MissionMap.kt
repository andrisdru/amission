package org.amission

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.amission.msp.Waypoint
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val SRC_POINTS = "wp-points"
private const val SRC_LINE = "wp-line"
private const val SRC_ME = "me-position"
private const val SRC_HOME = "home-position"
private const val LAYER_POINTS = "wp-points-layer"

/** How long a finger rests on a marker before it is picked up for dragging. */
private const val HOLD_MS = 250L

/** MapLibre map showing the mission. Tapping empty map adds a waypoint; tapping a waypoint selects it. */
@Composable
fun MissionMap(
    waypoints: List<Waypoint>,
    selected: Int?,
    onMapTap: (lat: Double, lon: Double) -> Unit,
    onSelect: (index: Int) -> Unit,
    onInsert: (afterIndex: Int, lat: Double, lon: Double) -> Unit,
    onMoveWaypoint: (index: Int, lat: Double, lon: Double) -> Unit,
    onMoveEnd: (index: Int) -> Unit,
    onViewportChanged: (Viewport) -> Unit,
    mapStyle: MapStyle,
    fitRequest: Int,
    locate: LocateResult?,
    home: Pair<Double, Double>?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { createMapView(context) }
    val markers = remember { MarkerBitmaps(context.resources.displayMetrics.density) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onMapTap)
    val select by rememberUpdatedState(onSelect)
    val insert by rememberUpdatedState(onInsert)
    val currentWaypoints by rememberUpdatedState(waypoints)
    val moveWaypoint by rememberUpdatedState(onMoveWaypoint)
    val moveEnd by rememberUpdatedState(onMoveEnd)
    val viewportChanged by rememberUpdatedState(onViewportChanged)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        // onCreate must precede the replayed ON_START/ON_RESUME from addObserver.
        mapView.onCreate(null)
        lifecycle.addObserver(observer)
        mapView.getMapAsync { m ->
            map = m
            m.addOnCameraIdleListener {
                val b = m.projection.visibleRegion.latLngBounds
                viewportChanged(Viewport(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast, m.cameraPosition.zoom))
            }
            // Long-press-and-drag support: find the marker under a finger and turn finger positions into coordinates.
            mapView.screenToLatLng = { x, y -> m.projection.fromScreenLocation(PointF(x, y)) }
            mapView.markerAt = { x, y ->
                val reach = 28f * mapView.resources.displayMetrics.density
                // Judged from the waypoint positions rather than rendered features, so it works the moment a marker is on screen.
                // Several markers can overlap under a finger; take the one whose centre is closest.
                currentWaypoints.withIndex().filter { it.value.action.hasPosition }.map { (i, w) ->
                    val s = m.projection.toScreenLocation(LatLng(w.latitude, w.longitude))
                    i to hypot(s.x - x, s.y - y)
                }.filter { it.second <= reach }.minByOrNull { it.second }?.first
            }
            mapView.onDragStart = { select(it) }
            mapView.onDragMove = { i, lat, lon -> moveWaypoint(i, lat, lon) }
            mapView.onDragEnd = { moveEnd(it) }
            m.addOnMapClickListener { latLng ->
                val screen: PointF = m.projection.toScreenLocation(latLng)
                val hit = m.queryRenderedFeatures(screen, LAYER_POINTS).firstOrNull()
                val idx = hit?.getNumberProperty("idx")?.toInt()
                if (idx != null) {
                    select(idx)
                } else {
                    val seg = nearestSegment(m, currentWaypoints, screen, 24f * mapView.resources.displayMetrics.density)
                    if (seg != null) insert(seg.first, seg.second.latitude, seg.second.longitude)
                    else tap(latLng.latitude, latLng.longitude)
                }
                true
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    // Zoom to the mission whenever a fit is requested (after a download, on start, or via the Fit chip).
    LaunchedEffect(map, fitRequest) {
        val m = map ?: return@LaunchedEffect
        if (fitRequest == 0) return@LaunchedEffect
        val pts = currentWaypoints.filter { it.action.hasPosition }
        if (pts.isEmpty()) return@LaunchedEffect
        val latSpan = pts.maxOf { it.latitude } - pts.minOf { it.latitude }
        val lonSpan = pts.maxOf { it.longitude } - pts.minOf { it.longitude }
        if (latSpan < 1e-5 && lonSpan < 1e-5) {
            m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].latitude, pts[0].longitude), 16.0))
        } else {
            val bounds = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 140))
        }
    }

    // Centre on a located position (quad or phone GPS), keeping the current zoom if it is already close.
    LaunchedEffect(map, locate?.id) {
        val m = map ?: return@LaunchedEffect
        val l = locate ?: return@LaunchedEffect
        m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(l.lat, l.lon), maxOf(m.cameraPosition.zoom, 16.0)))
    }

    // Initial style and every layer switch: replacing the style drops our mission layers, so re-add them.
    LaunchedEffect(map, mapStyle) {
        val m = map ?: return@LaunchedEffect
        styleReady = false
        val builder = when (mapStyle) {
            is MapStyle.Url -> Style.Builder().fromUri(mapStyle.url)
            is MapStyle.Json -> Style.Builder().fromJson(mapStyle.json)
        }
        m.setStyle(builder) { style ->
            addMissionLayers(style)
            styleReady = true
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier) {
        val m = map
        if (m != null && styleReady) m.getStyle { updateMission(it, markers, waypoints, selected, locate, home) }
    }
}

/**
 * The route segment closest to a tapped screen point, if within [reach] pixels: the list index of the segment's first
 * point and the spot on the segment to insert at. Only points the quad flies to form the route, as in [updateMission].
 */
private fun nearestSegment(m: MapLibreMap, waypoints: List<Waypoint>, tap: PointF, reach: Float): Pair<Int, LatLng>? {
    val flown = waypoints.withIndex().filter { it.value.action.hasPosition && it.value.action.isFlownTo }
    var best: Pair<Int, LatLng>? = null
    var bestDist = reach
    for (k in 0 until flown.size - 1) {
        val a = m.projection.toScreenLocation(LatLng(flown[k].value.latitude, flown[k].value.longitude))
        val b = m.projection.toScreenLocation(LatLng(flown[k + 1].value.latitude, flown[k + 1].value.longitude))
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((tap.x - a.x) * dx + (tap.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        val d = hypot(tap.x - (a.x + t * dx), tap.y - (a.y + t * dy))
        if (d < bestDist) {
            bestDist = d
            best = flown[k].index to m.projection.fromScreenLocation(PointF(a.x + t * dx, a.y + t * dy))
        }
    }
    return best
}

private fun createMapView(context: Context): DraggableMapView {
    MapSdk.init(context)
    return DraggableMapView(context)
}

/**
 * A map where a long press on a waypoint marker picks it up and drags it with the finger, while a touch anywhere
 * else pans the map as usual. The long press is judged here, before the map sees it, because the map would
 * otherwise treat the same movement as a pan.
 */
private class DraggableMapView(context: Context) : MapView(context) {
    /** Index (into the mission list) of the marker under the finger, or null. Set once the map is ready. */
    var markerAt: ((x: Float, y: Float) -> Int?)? = null
    var screenToLatLng: ((x: Float, y: Float) -> LatLng)? = null
    var onDragStart: ((index: Int) -> Unit)? = null
    var onDragMove: ((index: Int, lat: Double, lon: Double) -> Unit)? = null
    var onDragEnd: ((index: Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var pending: Runnable? = null
    private var dragIndex: Int? = null

    // super.dispatchTouchEvent cannot be called from inside a lambda, so go through a member.
    private fun mapDispatch(e: MotionEvent) = super.dispatchTouchEvent(e)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastX = ev.x
        lastY = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPending()
                downTime = ev.downTime
                startX = ev.x
                startY = ev.y
                val r = Runnable { pickUp() }
                pending = r
                postDelayed(r, HOLD_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (dragIndex == null) cancelPending()
            MotionEvent.ACTION_MOVE ->
                if (dragIndex == null && pending != null && hypot(ev.x - startX, ev.y - startY) > slop) cancelPending()
        }

        val index = dragIndex
        if (index != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> moveTo(index, ev.x, ev.y)
                MotionEvent.ACTION_UP -> { moveTo(index, ev.x, ev.y); finish(index) }
                MotionEvent.ACTION_CANCEL -> finish(index)
            }
            return true // the map never sees the rest of this gesture
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) cancelPending()
        return mapDispatch(ev)
    }

    private fun cancelPending() {
        pending?.let { removeCallbacks(it) }
        pending = null
    }

    private fun pickUp() {
        pending = null
        val index = markerAt?.invoke(startX, startY) ?: return // long press on empty map: leave it to the map
        // Tell the map its pan or tap is over, then take the rest of the touch for ourselves.
        val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, lastX, lastY, 0)
        mapDispatch(cancel)
        cancel.recycle()
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        dragIndex = index
        onDragStart?.invoke(index)
        moveTo(index, lastX, lastY)
    }

    private fun moveTo(index: Int, x: Float, y: Float) {
        val ll = screenToLatLng?.invoke(x, y) ?: return
        onDragMove?.invoke(index, ll.latitude, ll.longitude)
    }

    private fun finish(index: Int) {
        dragIndex = null
        parent?.requestDisallowInterceptTouchEvent(false)
        onDragEnd?.invoke(index)
    }
}

private fun addMissionLayers(style: Style) {
    style.addSource(GeoJsonSource(SRC_LINE))
    style.addSource(GeoJsonSource(SRC_POINTS))
    style.addSource(GeoJsonSource(SRC_ME))
    style.addSource(GeoJsonSource(SRC_HOME))
    style.addLayer(
        CircleLayer("me-layer", SRC_ME).withProperties(
            circleRadius(9f), circleColor("#1E88E5"), circleStrokeColor("#ffffff"), circleStrokeWidth(3f),
        )
    )
    style.addLayer(LineLayer("wp-line-layer", SRC_LINE).withProperties(lineColor("#e53935"), lineWidth(3f)))
    // Markers are drawn from generated bitmaps, not map text, so they never depend on font glyphs
    // that might not be in the offline cache.
    style.addLayer(
        SymbolLayer("home-layer", SRC_HOME).withProperties(
            iconImage(MarkerBitmaps.HOME_ICON),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
        )
    )
    style.addLayer(
        SymbolLayer(LAYER_POINTS, SRC_POINTS).withProperties(
            iconImage(get("icon")),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
        )
    )
}

private fun updateMission(
    style: Style,
    markers: MarkerBitmaps,
    waypoints: List<Waypoint>,
    selected: Int?,
    me: LocateResult?,
    home: Pair<Double, Double>?,
) {
    if (style.getImage(MarkerBitmaps.HOME_ICON) == null) style.addImage(MarkerBitmaps.HOME_ICON, markers.homeBitmap())
    style.getSourceAs<GeoJsonSource>(SRC_HOME)?.setGeoJson(
        if (home != null) FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(home.second, home.first))) else FeatureCollection.fromFeatures(emptyList<Feature>())
    )
    style.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(
        if (me != null) FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(me.lon, me.lat))) else FeatureCollection.fromFeatures(emptyList<Feature>())
    )
    // Jump / Set Head / RTH have no position of their own, so they are not drawn.
    val positioned = waypoints.withIndex().filter { it.value.action.hasPosition }
    val points = positioned.map { (i, w) ->
        val icon = markers.nameFor(i + 1, i == selected)
        if (style.getImage(icon) == null) style.addImage(icon, markers.bitmapFor(i + 1, i == selected))
        Feature.fromGeometry(Point.fromLngLat(w.longitude, w.latitude)).also {
            it.addNumberProperty("idx", i)
            it.addStringProperty("icon", icon)
        }
    }
    style.getSourceAs<GeoJsonSource>(SRC_POINTS)?.setGeoJson(FeatureCollection.fromFeatures(points))
    // The route only passes through points the quad flies to; a Set POI is looked at, not visited.
    val flown = positioned.filter { it.value.action.isFlownTo }
    val line = if (flown.size >= 2) {
        Feature.fromGeometry(LineString.fromLngLats(flown.map { Point.fromLngLat(it.value.longitude, it.value.latitude) }))
    } else null
    style.getSourceAs<GeoJsonSource>(SRC_LINE)?.setGeoJson(
        if (line != null) FeatureCollection.fromFeature(line) else FeatureCollection.fromFeatures(emptyList<Feature>())
    )
}

/** Numbered round waypoint markers, rendered once per number with the platform canvas. */
private class MarkerBitmaps(private val density: Float) {
    companion object {
        const val HOME_ICON = "home-point"
    }

    /** Green "H" disc marking the planned take-off point. */
    fun homeBitmap(): Bitmap {
        val size = (30 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF2E7D32.toInt()
        c.drawCircle(r, r, r - 1.5f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = 0xFFFFFFFF.toInt()
        c.drawCircle(r, r, r - 1.5f * density, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 15f * density
        paint.isFakeBoldText = true
        c.drawText("H", r, r - (paint.ascent() + paint.descent()) / 2f, paint)
        return bmp
    }

    fun nameFor(number: Int, selected: Boolean) = (if (selected) "wps-" else "wp-") + number

    fun bitmapFor(number: Int, selected: Boolean): Bitmap {
        val size = (32 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (selected) 0xFFFFB300.toInt() else 0xFFE53935.toInt()
        c.drawCircle(r, r, r - 1.5f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = 0xFFFFFFFF.toInt()
        c.drawCircle(r, r, r - 1.5f * density, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = (if (number > 99) 11f else 14f) * density
        paint.isFakeBoldText = true
        c.drawText(number.toString(), r, r - (paint.ascent() + paint.descent()) / 2f, paint)
        return bmp
    }
}
