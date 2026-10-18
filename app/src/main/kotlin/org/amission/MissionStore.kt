package org.amission

import android.content.Context
import org.amission.msp.Waypoint
import org.amission.msp.WaypointAction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists the mission being edited so it survives app restarts and works with no FC attached. */
class MissionStore(context: Context) {
    private val file = File(context.filesDir, "current_mission.json")

    fun load(): List<Waypoint> = try {
        if (!file.exists()) emptyList() else fromJson(file.readText())
    } catch (e: Exception) {
        emptyList()
    }

    fun save(waypoints: List<Waypoint>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(waypoints))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        fun toJson(waypoints: List<Waypoint>): String = JSONArray().apply {
            waypoints.forEach { w ->
                put(JSONObject()
                    .put("action", w.action.name)
                    .put("latE7", w.latE7).put("lonE7", w.lonE7).put("altCm", w.altitudeCm)
                    .put("p1", w.p1).put("p2", w.p2).put("p3", w.p3))
            }
        }.toString()

        fun fromJson(text: String): List<Waypoint> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Waypoint(
                    number = i + 1,
                    action = WaypointAction.valueOf(o.getString("action")),
                    latE7 = o.getInt("latE7"), lonE7 = o.getInt("lonE7"), altitudeCm = o.getInt("altCm"),
                    p1 = o.optInt("p1"), p2 = o.optInt("p2"), p3 = o.optInt("p3"),
                    isLast = i == arr.length() - 1,
                )
            }
        }
    }
}
