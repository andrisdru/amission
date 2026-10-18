package org.amission

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
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
import org.maplibre.android.MapLibre
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

/** MapLibre map showing the mission. Tapping empty map adds a waypoint; tapping a waypoint selects it. */
@Composable
fun MissionMap(
    waypoints: List<Waypoint>,
    selected: Int?,
    onMapTap: (lat: Double, lon: Double) -> Unit,
    onSelect: (index: Int) -> Unit,
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
            m.addOnMapClickListener { latLng ->
                val screen: PointF = m.projection.toScreenLocation(latLng)
                val hit = m.queryRenderedFeatures(screen, LAYER_POINTS).firstOrNull()
                val idx = hit?.getNumberProperty("idx")?.toInt()
                if (idx != null) select(idx) else tap(latLng.latitude, latLng.longitude)
                true
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    // Zoom to the mission whenever a fit is requested (after a download, on start, or via the Fit chip).
    val currentWaypoints by rememberUpdatedState(waypoints)
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

private fun createMapView(context: Context): MapView {
    MapLibre.getInstance(context)
    return MapView(context)
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
