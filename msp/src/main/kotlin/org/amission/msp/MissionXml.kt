package org.amission.msp

import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

class MissionFileException(message: String) : Exception(message)

/** Result of reading a mission file. [ignoredItems] counts entries after the first mission's end marker. */
class MissionImport(val waypoints: List<Waypoint>, val ignoredItems: Int)

/**
 * Reads and writes the mission file format used by the INAV Configurator (and mwp):
 * a `<mission>` element with `<version>`, `<mwp>` and one `<missionitem>` per FC list entry.
 * Coordinates are degrees, altitude is metres, and a Jump's parameter1 is the 1-based list
 * number, exactly as on the MSP wire. Files may come from anywhere, so reading is defensive.
 */
object MissionXml {
    const val VERSION = "2.3-pre8"
    const val MAX_CHARS = 1_000_000
    const val MAX_ITEMS = 255

    fun write(waypoints: List<Waypoint>, centerLat: Double? = null, centerLon: Double? = null, zoom: Int? = null): String {
        val items = MissionClient.normalize(waypoints)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mission>\n")
        sb.append("\t<version value=\"").append(VERSION).append("\"/>\n")
        sb.append("\t<mwp")
        if (centerLon != null && centerLat != null) sb.append(" cx=\"").append(deg(centerLon)).append("\" cy=\"").append(deg(centerLat)).append('"')
        sb.append(" home-x=\"0\" home-y=\"0\"")
        if (zoom != null) sb.append(" zoom=\"").append(zoom).append('"')
        sb.append("/>\n")
        for (w in items) {
            sb.append("\t<missionitem no=\"").append(w.number)
                .append("\" action=\"").append(w.action.name)
                .append("\" lat=\"").append(deg(w.latitude))
                .append("\" lon=\"").append(deg(w.longitude))
                .append("\" alt=\"").append(meters(w.altitudeCm))
                .append("\" parameter1=\"").append(w.p1)
                .append("\" parameter2=\"").append(w.p2)
                .append("\" parameter3=\"").append(w.p3)
                .append("\" flag=\"").append(if (w.isLast) Waypoint.FLAG_LAST else 0)
                .append("\"/>\n")
        }
        sb.append("</mission>\n")
        return sb.toString()
    }

    fun read(text: String): MissionImport {
        if (text.length > MAX_CHARS) throw MissionFileException("File is too large to be a mission")
        // Untrusted input: refuse DTDs and entities outright instead of relying on parser settings.
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            throw MissionFileException("File uses features a mission file never needs")
        }
        val root = try {
            val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))).documentElement
        } catch (e: Exception) {
            throw MissionFileException("Not a valid mission file (${e.message?.lineSequence()?.firstOrNull() ?: "unreadable"})")
        }
        if (!root.tagName.equals("mission", ignoreCase = true)) throw MissionFileException("Not a mission file")

        val items = ArrayList<Waypoint>()
        var ended = false
        var ignored = 0
        var child: Node? = root.firstChild
        while (child != null) {
            if (child is Element && child.tagName.equals("missionitem", ignoreCase = true)) {
                if (child.attributes.length == 0) { child = child.nextSibling; continue }
                if (ended) {
                    ignored++
                } else {
                    val wp = parseItem(child, items.size + 1)
                    items += wp
                    if (wp.isLast) ended = true
                    if (items.size > MAX_ITEMS) throw MissionFileException("Too many waypoints in file")
                }
            }
            child = child.nextSibling
        }
        if (items.isEmpty()) throw MissionFileException("The file contains no waypoints")
        items.forEach { w ->
            if (w.action == WaypointAction.JUMP && w.p1 !in 1..items.size) {
                throw MissionFileException("Point ${w.number} jumps to a point that is not in the mission")
            }
        }
        return MissionImport(MissionClient.normalize(items), ignored)
    }

    private fun parseItem(e: Element, number: Int): Waypoint {
        fun attr(name: String): String? =
            (0 until e.attributes.length).map { e.attributes.item(it) }.firstOrNull { it.nodeName.equals(name, true) }?.nodeValue
        // The default is only for an attribute that is absent; a present but malformed one is an error,
        // otherwise a corrupt coordinate would quietly become 0.
        fun num(name: String, default: Double? = null): Double {
            val raw = attr(name) ?: return default ?: throw MissionFileException("Point $number: missing '$name'")
            val v = raw.trim().toDoubleOrNull()
            if (v == null || !v.isFinite()) throw MissionFileException("Point $number: invalid '$name'")
            return v
        }

        val actionName = attr("action")?.trim()?.uppercase(Locale.ROOT) ?: throw MissionFileException("Point $number: no action")
        val action = when (actionName) {
            "PH_TIME" -> WaypointAction.POSHOLD_TIME
            "PH_UNLIM", "POSHOLD_UNLIM" ->
                throw MissionFileException("Point $number: unlimited position hold is not supported by INAV 9")
            else -> WaypointAction.entries.firstOrNull { it.name == actionName }
                ?: throw MissionFileException("Point $number: unknown action '$actionName'")
        }
        val lat = num("lat", 0.0)
        val lon = num("lon", 0.0)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw MissionFileException("Point $number: coordinates out of range")
        val alt = num("alt", 0.0)
        if (alt !in -10_000.0..10_000.0) throw MissionFileException("Point $number: altitude out of range")
        fun param(name: String): Int {
            val v = num(name, 0.0)
            if (v != Math.floor(v) || v !in Short.MIN_VALUE.toDouble()..Short.MAX_VALUE.toDouble()) {
                throw MissionFileException("Point $number: '$name' out of range")
            }
            return v.toInt()
        }
        val flag = num("flag", 0.0).toInt()
        return Waypoint(
            number = number,
            action = action,
            latE7 = Math.round(lat * 1e7).toInt(),
            lonE7 = Math.round(lon * 1e7).toInt(),
            altitudeCm = Math.round(alt * 100).toInt(),
            p1 = param("parameter1"), p2 = param("parameter2"), p3 = param("parameter3"),
            isLast = flag == Waypoint.FLAG_LAST,
        )
    }

    private fun deg(v: Double) = String.format(Locale.US, "%.7f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun meters(cm: Int): String =
        if (cm % 100 == 0) (cm / 100).toString() else String.format(Locale.US, "%.2f", cm / 100.0).trimEnd('0').trimEnd('.')
}
