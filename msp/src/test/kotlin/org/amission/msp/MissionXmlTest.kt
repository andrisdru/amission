package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissionXmlTest {
    private val mission = listOf(
        Waypoint(1, WaypointAction.WAYPOINT, 480_053_612, 110_308_990, 10_000, p1 = 500, p3 = 0b10_001),
        Waypoint(2, WaypointAction.POSHOLD_TIME, 480_060_000, 110_320_000, 3_750, p1 = 10, p2 = 300),
        Waypoint(3, WaypointAction.JUMP, 0, 0, 0, p1 = 1, p2 = 2),
        Waypoint(4, WaypointAction.RTH, 0, 0, 3_000, p1 = 1),
    )

    @Test
    fun roundTripsEveryField() {
        val back = MissionXml.read(MissionXml.write(mission)).waypoints
        assertEquals(MissionClient.normalize(mission), back)
    }

    @Test
    fun writesDegreesAndMetresLikeTheConfigurator() {
        val xml = MissionXml.write(mission, centerLat = 48.0, centerLon = 11.03, zoom = 16)
        assertTrue("action=\"WAYPOINT\" lat=\"48.0053612\" lon=\"11.030899\" alt=\"100\"" in xml, xml)
        assertTrue("alt=\"37.5\"" in xml, "fractional metres")
        assertTrue("cx=\"11.03\" cy=\"48\"" in xml)
        assertTrue("flag=\"165\"" in xml)
        assertEquals(1, Regex("flag=\"165\"").findAll(xml).count(), "only the last item carries the end flag")
    }

    @Test
    fun readsAFileInTheConfiguratorsLayout() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<mission>
	<version value="2.3-pre8"/>
	<mwp cx="11.03" cy="48" home-x="0" home-y="0" zoom="16"/>
	<missionitem no="1" action="WAYPOINT" lat="48.0053612" lon="11.030899" alt="100" parameter1="0" parameter2="0" parameter3="0" flag="0"/>
	<missionitem no="2" action="PH_TIME" lat="48.006" lon="11.032" alt="50" parameter1="10" parameter2="0" parameter3="2" flag="0"/>
	<missionitem no="3" action="RTH" lat="0" lon="0" alt="30" parameter1="1" parameter2="0" parameter3="0" flag="165"/>
</mission>"""
        val r = MissionXml.read(xml)
        assertEquals(listOf(WaypointAction.WAYPOINT, WaypointAction.POSHOLD_TIME, WaypointAction.RTH), r.waypoints.map { it.action })
        assertEquals(10_000, r.waypoints[0].altitudeCm)
        assertTrue(r.waypoints[1].userAction(1), "parameter3 = 2 is user action 1")
        assertEquals(1, r.waypoints[2].p1)
        assertTrue(r.waypoints.last().isLast)
        assertEquals(0, r.ignoredItems)
    }

    @Test
    fun jumpTargetStaysAOneBasedListNumber() {
        val back = MissionXml.read(MissionXml.write(mission)).waypoints
        assertEquals(1, back.first { it.action == WaypointAction.JUMP }.p1)
    }

    @Test
    fun onlyTheFirstMissionOfAMultiMissionFileIsImported() {
        val xml = """<mission><version value="4.0.0"/>
<meta mission="1"/>
<missionitem no="1" action="WAYPOINT" lat="48.0" lon="11.0" alt="30" parameter1="0" parameter2="0" parameter3="0" flag="165"/>
<meta mission="2"/>
<missionitem no="1" action="WAYPOINT" lat="49.0" lon="12.0" alt="30" parameter1="0" parameter2="0" parameter3="0" flag="0"/>
<missionitem no="2" action="RTH" lat="0" lon="0" alt="30" parameter1="0" parameter2="0" parameter3="0" flag="165"/>
</mission>"""
        val r = MissionXml.read(xml)
        assertEquals(1, r.waypoints.size)
        assertEquals(2, r.ignoredItems)
    }

    @Test
    fun rejectsDoctypeAndEntities() {
        val bomb = """<?xml version="1.0"?><!DOCTYPE mission [<!ENTITY a "aaaa">]><mission><missionitem action="WAYPOINT" lat="1" lon="1" alt="1" flag="165"/></mission>"""
        assertFailsWith<MissionFileException> { MissionXml.read(bomb) }
    }

    @Test
    fun rejectsUnsupportedAndUnknownActions() {
        fun xml(action: String) = """<mission><missionitem action="$action" lat="1" lon="1" alt="1" parameter1="0" parameter2="0" parameter3="0" flag="165"/></mission>"""
        assertFailsWith<MissionFileException> { MissionXml.read(xml("PH_UNLIM")) }
        assertFailsWith<MissionFileException> { MissionXml.read(xml("TELEPORT")) }
    }

    @Test
    fun rejectsOutOfRangeValues() {
        fun xml(lat: String = "1", lon: String = "1", alt: String = "1", p1: String = "0") =
            """<mission><missionitem action="WAYPOINT" lat="$lat" lon="$lon" alt="$alt" parameter1="$p1" parameter2="0" parameter3="0" flag="165"/></mission>"""
        assertFailsWith<MissionFileException> { MissionXml.read(xml(lat = "91")) }
        assertFailsWith<MissionFileException> { MissionXml.read(xml(lon = "-181")) }
        assertFailsWith<MissionFileException> { MissionXml.read(xml(alt = "50000")) }
        assertFailsWith<MissionFileException> { MissionXml.read(xml(p1 = "99999")) }
        assertFailsWith<MissionFileException> { MissionXml.read(xml(lat = "NaN")) }
    }

    @Test
    fun rejectsEmptyMalformedAndForeignFiles() {
        assertFailsWith<MissionFileException> { MissionXml.read("<mission></mission>") }
        assertFailsWith<MissionFileException> { MissionXml.read("not xml at all") }
        assertFailsWith<MissionFileException> { MissionXml.read("<gpx><wpt/></gpx>") }
        assertFailsWith<MissionFileException> { MissionXml.read("x".repeat(MissionXml.MAX_CHARS + 1)) }
    }

    @Test
    fun rejectsAJumpToANonexistentPoint() {
        val xml = """<mission>
<missionitem action="WAYPOINT" lat="1" lon="1" alt="1" parameter1="0" parameter2="0" parameter3="0" flag="0"/>
<missionitem action="JUMP" lat="0" lon="0" alt="0" parameter1="9" parameter2="1" parameter3="0" flag="165"/></mission>"""
        assertFailsWith<MissionFileException> { MissionXml.read(xml) }
    }

    @Test
    fun missingEndFlagIsAddedOnImport() {
        val xml = """<mission><missionitem action="WAYPOINT" lat="1" lon="1" alt="10" parameter1="0" parameter2="0" parameter3="0" flag="0"/></mission>"""
        assertTrue(MissionXml.read(xml).waypoints.single().isLast)
    }
}

class MissionXmlHomeTest {
    private val one = listOf(Waypoint(1, WaypointAction.WAYPOINT, 480_000_000, 110_000_000, 8_000))

    @Test
    fun homeRoundTrips() {
        val xml = MissionXml.write(one, home = 47.99 to 10.99)
        assertTrue("home-x=\"10.99\" home-y=\"47.99\"" in xml, xml)
        assertEquals(47.99 to 10.99, MissionXml.read(xml).home)
    }

    @Test
    fun noHomeIsWrittenAsZeroAndReadBackAsNone() {
        val xml = MissionXml.write(one)
        assertTrue("home-x=\"0\" home-y=\"0\"" in xml)
        assertEquals(null, MissionXml.read(xml).home)
    }

    @Test
    fun badHomeIsIgnoredNotFatal() {
        val xml = """<mission><mwp home-x="999" home-y="NaN"/><missionitem action="WAYPOINT" lat="1" lon="1" alt="1" parameter1="0" parameter2="0" parameter3="0" flag="165"/></mission>"""
        assertEquals(null, MissionXml.read(xml).home)
    }
}
