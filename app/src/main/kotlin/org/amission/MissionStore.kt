package org.amission

import android.content.Context
import org.amission.msp.Waypoint
import org.amission.msp.WaypointAction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What is kept between launches: the waypoints and the planned take-off point (lat, lon), if set. */
data class StoredMission(val waypoints: List<Waypoint>, val home: Pair<Double, Double>?)

/** Persists the mission being edited so it survives app restarts and works with no FC attached. */
class MissionStore(context: Context) {
    private val file = File(context.filesDir, "current_mission.json")

    fun load(): StoredMission = try {
        if (!file.exists()) StoredMission(emptyList(), null) else fromJson(file.readText())
    } catch (e: Exception) {
        StoredMission(emptyList(), null)
    }

    fun save(waypoints: List<Waypoint>, home: Pair<Double, Double>?) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(waypoints, home))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        fun toJson(waypoints: List<Waypoint>, home: Pair<Double, Double>?): String = JSONObject().apply {
            put("waypoints", JSONArray().apply {
                waypoints.forEach { w ->
                    put(JSONObject()
                        .put("action", w.action.name)
                        .put("latE7", w.latE7).put("lonE7", w.lonE7).put("altCm", w.altitudeCm)
                        .put("p1", w.p1).put("p2", w.p2).put("p3", w.p3)
                        .apply {
                            w.groundHeightM?.let { put("groundM", it) }
                            w.groundBasis?.let { put("groundBasis", it) }
                        })
                }
            })
            if (home != null) put("home", JSONObject().put("lat", home.first).put("lon", home.second))
        }.toString()

        /** Accepts the current object format and the older bare-array one. */
        fun fromJson(text: String): StoredMission {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) return StoredMission(waypointsFrom(JSONArray(trimmed)), null)
            val obj = JSONObject(trimmed)
            val home = obj.optJSONObject("home")?.let { it.getDouble("lat") to it.getDouble("lon") }
            return StoredMission(waypointsFrom(obj.getJSONArray("waypoints")), home)
        }

        private fun waypointsFrom(arr: JSONArray): List<Waypoint> = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Waypoint(
                number = i + 1,
                action = WaypointAction.valueOf(o.getString("action")),
                latE7 = o.getInt("latE7"), lonE7 = o.getInt("lonE7"), altitudeCm = o.getInt("altCm"),
                p1 = o.optInt("p1"), p2 = o.optInt("p2"), p3 = o.optInt("p3"),
                isLast = i == arr.length() - 1,
                groundHeightM = if (o.has("groundM")) o.getInt("groundM") else null,
                groundBasis = o.optString("groundBasis").ifEmpty { null },
            )
        }
    }
}
