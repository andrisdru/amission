package org.amission

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.amission.msp.FlightProfile
import org.amission.msp.FlightProfileBuilder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface ProfileState {
    data object Empty : ProfileState
    data object Loading : ProfileState
    data class NoData(val reason: String) : ProfileState
    data class Ready(val profile: FlightProfile, val homeAssumed: Boolean) : ProfileState
}

private val FlightColor = Color(0xFF1E88E5)
private val WarnColor = Color(0xFFD32F2F)
private val LimitColor = Color(0xFFFB8C00)
private val TerrainColor = Color(0xFF8D6E63)

/** Altitude against distance along the route, with the terrain under it and a low-clearance warning. */
@Composable
fun ProfileTab(vm: MissionViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            NumberField("Warn below (m):", vm.minAglM.toString(), intOnly = true) { s ->
                s.toIntOrNull()?.let { vm.setMinAgl(it) }
            }
        }
        when (val state = vm.profile) {
            ProfileState.Empty -> Text("Add waypoints to see the altitude profile.")
            ProfileState.Loading -> Text("Loading terrain heights…")
            is ProfileState.NoData -> Text(state.reason, color = MaterialTheme.colorScheme.error)
            is ProfileState.Ready -> ProfileContent(state, vm)
        }
        Text(
            "Terrain heights: Open-Meteo.com (CC BY 4.0), from the Copernicus DEM. Heights above ground use terrain data of about 90 m resolution. Trees, buildings and masts are " +
                "not included, so keep a margin. Heights are measured from the ground where the quad takes off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileContent(state: ProfileState.Ready, vm: MissionViewModel) {
    val p = state.profile
    if (p.hasIssues) {
        val worst = p.issues.minBy { it.lowestAglM }
        Column(Modifier.fillMaxWidth()) {
            Text(
                "⚠ Lower than ${vm.minAglM} m above ground: ${formatM(worst.lowestAglM)} near point ${worst.nearWaypoint}.",
                color = WarnColor, style = MaterialTheme.typography.titleSmall,
            )
            p.issues.sortedBy { it.lowestAglM }.take(4).forEach {
                Text(
                    "Point ${it.nearWaypoint}: ${formatM(it.lowestAglM)} above ground, at ${formatDistance(it.lowestAtM)}",
                    style = MaterialTheme.typography.bodySmall, color = WarnColor,
                )
            }
            if (p.issues.size > 4) Text("…and ${p.issues.size - 4} more.", style = MaterialTheme.typography.bodySmall, color = WarnColor)
        }
    } else {
        val low = p.lowest
        Text(
            "Clear: lowest point is ${low?.let { formatM(it.aglM) } ?: "-"} above ground (limit ${vm.minAglM} m).",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    ProfileChart(p, Modifier.fillMaxWidth().height(220.dp))
    ProfileLegend(vm.minAglM)
    Text(
        "Route ${formatDistance(p.totalDistanceM)} · take-off ground ${p.homeElevationM.roundToInt()} m above sea level" +
            if (state.homeAssumed) " (assumed to be at point 1's ground: set the real take-off spot in More ▸ Home point)" else "",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** What each line in the chart means. Heights are measured from the ground where the quad takes off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileLegend(minAglM: Int) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem("Your flight (waypoints numbered)") { Canvas(Modifier.width(26.dp).height(10.dp)) { swatchLine(FlightColor, 3.dp.toPx()) } }
        LegendItem("Too low") { Canvas(Modifier.width(26.dp).height(10.dp)) { swatchLine(WarnColor, 3.dp.toPx()) } }
        LegendItem("Lowest allowed: ground + $minAglM m") {
            Canvas(Modifier.width(26.dp).height(10.dp)) { swatchLine(LimitColor, 2.dp.toPx(), dashed = true) }
        }
        LegendItem("Ground") { Canvas(Modifier.width(26.dp).height(10.dp)) { drawRect(TerrainColor.copy(alpha = 0.55f)) } }
    }
}

@Composable
private fun LegendItem(text: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatch()
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.swatchLine(color: Color, width: Float, dashed: Boolean = false) {
    drawLine(
        color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = width,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(9f, 6f)) else null,
    )
}

@Composable
private fun ProfileChart(profile: FlightProfile, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val label = TextStyle(fontSize = 10.sp, color = axisColor)
    val vertexLabel = TextStyle(fontSize = 11.sp, color = FlightColor)

    Canvas(modifier) {
        val pts = profile.points
        if (pts.size < 2) return@Canvas
        val left = 46.dp.toPx(); val right = 10.dp.toPx(); val top = 16.dp.toPx(); val bottom = 22.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val (low0, high0) = FlightProfileBuilder.heightRange(profile)
        val highWithLimit = max(high0, pts.maxOf { profile.terrainAboveHomeM(it) + profile.minAglM })
        val pad = max(10.0, (highWithLimit - low0) * 0.1)
        val step = niceStep((highWithLimit - low0 + 2 * pad) / 5)
        val lo = floor((low0 - pad) / step) * step
        val hi = ceil((highWithLimit + pad) / step) * step
        val total = max(profile.totalDistanceM, 1.0)
        fun x(d: Double) = left + (d / total * w).toFloat()
        fun y(m: Double) = top + (h - (m - lo) / (hi - lo) * h).toFloat()

        // horizontal grid and height labels
        var g = lo
        while (g <= hi + 1e-6) {
            drawLine(gridColor, Offset(left, y(g)), Offset(left + w, y(g)), strokeWidth = 1f)
            val t = measurer.measure("${g.roundToInt()}", label)
            drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), y(g) - t.size.height / 2f))
            g += step
        }
        // 0 is the take-off ground: a stronger line so "above" and "below" take-off are easy to tell apart
        if (0.0 in lo..hi) {
            drawLine(axisColor, Offset(left, y(0.0)), Offset(left + w, y(0.0)), strokeWidth = 1.5.dp.toPx())
        }
        val axisTitle = measurer.measure("m above take-off", label)
        drawText(axisTitle, topLeft = Offset(left + 4.dp.toPx(), 0f))

        // distance labels
        val xStep = niceStep(total / 4)
        var d = 0.0
        while (d <= total + 1e-6) {
            val text = when {
                total < 2000 -> "${d.roundToInt()} m"
                xStep >= 1000 -> String.format(Locale.US, "%.0f km", d / 1000)
                else -> String.format(Locale.US, "%.1f km", d / 1000)
            }
            val t = measurer.measure(text, label)
            drawText(t, topLeft = Offset(x(d) - t.size.width / 2f, top + h + 3.dp.toPx()))
            d += xStep
        }

        // terrain
        val ground = Path().apply {
            moveTo(x(pts.first().distanceM), y(lo))
            pts.forEach { lineTo(x(it.distanceM), y(profile.terrainAboveHomeM(it))) }
            lineTo(x(pts.last().distanceM), y(lo))
            close()
        }
        drawPath(ground, TerrainColor.copy(alpha = 0.55f))
        drawPath(
            Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.distanceM), y(profile.terrainAboveHomeM(p))) else lineTo(x(p.distanceM), y(profile.terrainAboveHomeM(p))) } },
            TerrainColor, style = Stroke(width = 2.dp.toPx()),
        )

        // the lowest allowed flight height: terrain plus the minimum
        drawPath(
            Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.distanceM), y(profile.terrainAboveHomeM(p) + profile.minAglM)) else lineTo(x(p.distanceM), y(profile.terrainAboveHomeM(p) + profile.minAglM)) } },
            LimitColor, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
        )

        // planned flight path, red where it is lower than the limit
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val low = minOf(a.aglM, b.aglM) < profile.minAglM
            drawLine(
                if (low) WarnColor else FlightColor,
                Offset(x(a.distanceM), y(profile.flightAboveHomeM(a))), Offset(x(b.distanceM), y(profile.flightAboveHomeM(b))),
                strokeWidth = 3.dp.toPx(),
            )
        }

        // waypoint markers
        pts.forEach { p ->
            val v = p.vertex ?: return@forEach
            val c = Offset(x(p.distanceM), y(profile.flightAboveHomeM(p)))
            val bad = p.aglM < profile.minAglM
            drawCircle(Color.White, 5.dp.toPx(), c)
            drawCircle(if (bad) WarnColor else FlightColor, 5.dp.toPx(), c, style = Stroke(width = 2.dp.toPx()))
            val t = measurer.measure("${profile.vertexNumbers[v]}", vertexLabel)
            drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height - 6.dp.toPx()))
        }
    }
}

private fun niceStep(raw: Double): Double {
    val steps = doubleArrayOf(
        10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 250.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0,
        20_000.0, 50_000.0, 100_000.0, 200_000.0, 500_000.0, 1_000_000.0,
    )
    return steps.firstOrNull { it >= raw } ?: steps.last()
}

private fun formatM(m: Double) = "${m.roundToInt()} m"

private fun formatDistance(m: Double) =
    if (m >= 1000) String.format(Locale.US, "%.2f km", m / 1000) else "${m.roundToInt()} m"
