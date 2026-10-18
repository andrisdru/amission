package org.amission

import android.content.Context
import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { createMapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onMapTap)
    val select by rememberUpdatedState(onSelect)

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
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                addMissionLayers(style)
                styleReady = true
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
    val points = waypoints.mapIndexed { i, w ->
        Feature.fromGeometry(Point.fromLngLat(w.longitude, w.latitude)).also {
            it.addNumberProperty("idx", i)
            it.addStringProperty("n", (i + 1).toString())
            it.addBooleanProperty("sel", i == selected)
        }
    }
    style.getSourceAs<GeoJsonSource>(SRC_POINTS)?.setGeoJson(FeatureCollection.fromFeatures(points))
    val line = if (waypoints.size >= 2) {
        Feature.fromGeometry(LineString.fromLngLats(waypoints.map { Point.fromLngLat(it.longitude, it.latitude) }))
    } else null
    style.getSourceAs<GeoJsonSource>(SRC_LINE)?.setGeoJson(
        if (line != null) FeatureCollection.fromFeature(line) else FeatureCollection.fromFeatures(emptyList<Feature>())
    )
}
