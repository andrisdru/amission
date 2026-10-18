package org.amission

import android.content.Context
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
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textSize
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
private const val LAYER_POINTS = "wp-points-layer"

/** MapLibre map showing the mission. Tapping empty map adds a waypoint; tapping a waypoint selects it. */
@Composable
fun MissionMap(
    waypoints: List<Waypoint>,
    selected: Int?,
    onMapTap: (lat: Double, lon: Double) -> Unit,
    onSelect: (index: Int) -> Unit,
    onCenterChanged: (lat: Double, lon: Double) -> Unit,
    mapStyle: MapStyle,
    fitRequest: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { createMapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onMapTap)
    val select by rememberUpdatedState(onSelect)
    val centerChanged by rememberUpdatedState(onCenterChanged)

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
                m.cameraPosition.target?.let { centerChanged(it.latitude, it.longitude) }
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
        if (m != null && styleReady) m.getStyle { updateMission(it, waypoints, selected) }
    }
}

private fun createMapView(context: Context): MapView {
    MapLibre.getInstance(context)
    return MapView(context)
}

private fun addMissionLayers(style: Style) {
    style.addSource(GeoJsonSource(SRC_LINE))
    style.addSource(GeoJsonSource(SRC_POINTS))
    style.addLayer(LineLayer("wp-line-layer", SRC_LINE).withProperties(lineColor("#e53935"), lineWidth(3f)))
    style.addLayer(
        CircleLayer(LAYER_POINTS, SRC_POINTS).withProperties(
            circleRadius(14f),
            circleColor(switchCase(eq(get("sel"), literal(true)), color(0xFFFFB300.toInt()), color(0xFFE53935.toInt()))),
            circleStrokeColor("#ffffff"),
            circleStrokeWidth(2f),
        )
    )
    style.addLayer(
        SymbolLayer("wp-label-layer", SRC_POINTS).withProperties(
            textField(get("n")),
            textSize(13f),
            textColor("#ffffff"),
            textAllowOverlap(true),
            textFont(arrayOf("Noto Sans Regular")),
        )
    )
}

private fun updateMission(style: Style, waypoints: List<Waypoint>, selected: Int?) {
    // Jump / Set Head / RTH have no position of their own, so they are not drawn.
    val positioned = waypoints.withIndex().filter { it.value.action.hasPosition }
    val points = positioned.map { (i, w) ->
        Feature.fromGeometry(Point.fromLngLat(w.longitude, w.latitude)).also {
            it.addNumberProperty("idx", i)
            it.addStringProperty("n", (i + 1).toString())
            it.addBooleanProperty("sel", i == selected)
        }
    }
    style.getSourceAs<GeoJsonSource>(SRC_POINTS)?.setGeoJson(FeatureCollection.fromFeatures(points))
    val line = if (positioned.size >= 2) {
        Feature.fromGeometry(LineString.fromLngLats(positioned.map { Point.fromLngLat(it.value.longitude, it.value.latitude) }))
    } else null
    style.getSourceAs<GeoJsonSource>(SRC_LINE)?.setGeoJson(
        if (line != null) FeatureCollection.fromFeature(line) else FeatureCollection.fromFeatures(emptyList<Feature>())
    )
}
