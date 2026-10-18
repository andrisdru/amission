package org.amission.msp

/** A problem found in a mission. [pointNumber] is the 1-based list number the message is about, if there is one. */
data class MissionIssue(val pointNumber: Int?, val message: String)

/**
 * Looks for missions that the FC will accept but that do not do what the planner probably meant.
 * The FC checks almost nothing: it only needs the end flag on the last entry.
 */
object MissionCheck {

    fun check(waypoints: List<Waypoint>): List<MissionIssue> {
        val list = MissionClient.normalize(waypoints.ifEmpty { return emptyList() })
        val issues = ArrayList<MissionIssue>()
        val n = list.size

        // A Jump's p1 is a 1-based list number; it must point at a real entry.
        val badJump = HashSet<Int>()
        list.forEachIndexed { i, w ->
            if (w.action == WaypointAction.JUMP && w.p1 !in 1..n) {
                badJump += i
                issues += MissionIssue(w.number, "Point ${w.number} jumps to point ${w.p1}, which does not exist.")
            }
        }

        // Which entries can the flight reach? Walk the list the way the FC does: RTH and Land end the mission,
        // the last entry ends it, and a Jump may either loop to its target or fall through once its repeats run out.
        val reached = BooleanArray(n)
        val stack = ArrayDeque<Int>().apply { addLast(0) }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (i !in 0 until n || reached[i]) continue
            reached[i] = true
            val w = list[i]
            when (w.action) {
                WaypointAction.RTH, WaypointAction.LAND -> {}
                WaypointAction.JUMP -> {
                    if (i !in badJump) stack.addLast(w.p1 - 1)
                    if (i + 1 < n) stack.addLast(i + 1)
                }
                else -> if (i + 1 < n) stack.addLast(i + 1)
            }
        }

        val unreached = list.indices.filter { !reached[it] }
        if (unreached.isNotEmpty()) {
            val first = unreached.first()
            val ender = (first - 1 downTo 0).firstOrNull { list[it].action == WaypointAction.RTH || list[it].action == WaypointAction.LAND }
            val numbers = unreached.joinToString(", ") { "${list[it].number}" }
            val why = if (ender != null) {
                "Point ${list[ender].number} (${list[ender].action.label}) ends the mission, so"
            } else {
                "Nothing leads to them, so"
            }
            issues += MissionIssue(
                list[first].number,
                (if (unreached.size == 1) "Point $numbers is never flown. " else "Points $numbers are never flown. ") +
                    "$why everything after it is skipped. Delete ${if (unreached.size == 1) "it" else "them"} or move the end.",
            )
        }

        // A Set Head or Set POI as the final entry has nothing to apply to.
        val last = list.last()
        if (last.action == WaypointAction.SET_HEAD || last.action == WaypointAction.SET_POI) {
            issues += MissionIssue(last.number, "The mission ends with a ${last.action.label}, which has no waypoint after it to affect.")
        }
        return issues
    }
}
