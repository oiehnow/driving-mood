package kr.urbanmood.context

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

class RoadLookupTest {
    private class Clock : EnvironmentClock {
        var now = 1_000_000L
        var elapsed = 1_000L
        override fun wallTime() = now
        override fun elapsedTime() = elapsed
        fun advance(ms: Long) { now += ms; elapsed += ms }
    }
    private fun fix(now: Long = 1_000_000L, longitude: Double = 0.0) = LocationSnapshot(
        Availability.AVAILABLE, 0.0, longitude, 5f, source = SourceInfo("GPS", now))
    private fun way(id: Long = 1, kind: String = "motorway", east: Double = 0.0) = RoadWay(
        id, kind, "시험 도로", listOf(RoadPoint(-0.001, east), RoadPoint(0.001, east)))
    private fun xml(kind: String = "motorway") = """<osm version="0.6"><note>ODbL</note><meta osm_base="2026-10-26T01:00:00Z"/><way id="1"><bounds minlat="0" minlon="0" maxlat="1" maxlon="1"/><nd ref="2" lat="-0.001" lon="0"/><nd ref="3" lat="0.001" lon="0"/><tag k="highway" v="$kind"/><tag k="name" v="테스트 도로"/></way></osm>"""

    @Test fun matchesSegmentInteriorNotWayCentroidOrEndpoint() {
        val road = RoadMatcher.match(fix(), listOf(way()), 1_000_000L)
        assertEquals(Availability.AVAILABLE, road.availability)
        assertEquals(RoadType.HIGHWAY, road.type)
        assertEquals("시험 도로", road.placeName)
        assertTrue(road.source.detail.orEmpty().contains("추정"))
    }
    @Test fun usesOnlyExplicitOsmClassification() {
        for ((tag, expected) in listOf("motorway" to RoadType.HIGHWAY, "trunk" to RoadType.OTHER,
            "motorway_link" to RoadType.OTHER, "residential" to RoadType.RESIDENTIAL,
            "primary" to RoadType.OTHER, "living_street" to RoadType.OTHER)) {
            assertEquals(tag, expected, RoadMatcher.match(fix(), listOf(way(kind = tag)), 1_000_000L).type)
        }
    }
    @Test fun rejectsParallelRoadEvenWhenBothHaveSameNameAndType() {
        val roads = listOf(way(), way(id = 2, east = 0.00009)) // ~10m parallel carriageway
        assertEquals(RoadType.UNKNOWN, RoadMatcher.match(fix(), roads, 1_000_000L).type)
    }
    @Test fun rejectsIntersectionAndFarRoad() {
        val crossing = RoadWay(2, "residential", "교차 도로", listOf(RoadPoint(0.0, -0.001), RoadPoint(0.0, 0.001)))
        assertEquals(RoadType.UNKNOWN, RoadMatcher.match(fix(), listOf(way(), crossing), 1_000_000L).type)
        assertEquals(RoadType.UNKNOWN, RoadMatcher.match(fix(), listOf(way(east = 0.003)), 1_000_000L).type)
    }
    @Test fun acquisitionAgeDoesNotExpireValidFixDuringBoundedNetworkCall() {
        val clock = Clock()
        val location = fix(now = clock.now - 30_000)
        val requests = RoadRequests(clock) { object : RoadCall {
            override fun fetch(location: LocationSnapshot): List<RoadWay> { clock.advance(12_000); return listOf(way()) }
            override fun cancel() = Unit
        } }
        val road = requests.fetch(location)
        assertEquals(RoadType.HIGHWAY, road.type)
        assertEquals(location.source.observedAtEpochMs, road.source.observedAtEpochMs)
        assertEquals(clock.now, road.source.fetchedAtEpochMs)
        assertEquals(RoadType.UNKNOWN, RoadMatcher.match(location, listOf(way()), clock.now + 120_000).type)
    }
    @Test fun nonDrivingPathsCannotBecomeRoadContextOrMaskMappedRoad() {
        for (tag in listOf("footway", "cycleway", "path", "steps", "construction", "proposed", "pedestrian")) {
            assertTrue(tag, OverpassRoadParser.parse(xml(tag)).isEmpty())
            assertEquals(tag, RoadType.UNKNOWN, RoadMatcher.match(fix(), listOf(way(kind = tag)), 1_000_000).type)
            assertEquals(tag, RoadType.HIGHWAY, RoadMatcher.match(fix(),
                listOf(way(), way(id = 2, kind = tag, east = 0.00001)), 1_000_000).type)
        }
    }
    @Test fun onlyFreshAccurateRealLocationMayBeSent() {
        val clock = Clock(); var calls = 0
        val requests = RoadRequests(clock) { object : RoadCall {
            override fun fetch(location: LocationSnapshot): List<RoadWay> { calls++; return listOf(way()) }
            override fun cancel() = Unit
        } }
        val invalid = listOf(fix().copy(accuracyMeters = 50.1f), fix().copy(accuracyMeters = null),
            fix().copy(availability = Availability.MANUAL), fix().copy(latitude = Double.NaN),
            fix(now = clock.now - 30_001), fix(now = clock.now + 1))
        invalid.forEach { assertEquals(RoadType.UNKNOWN, requests.fetch(it).type) }
        assertEquals(0, calls)
        assertEquals(RoadType.HIGHWAY, requests.fetch(fix()).type)
        assertEquals(1, calls)
    }
    @Test fun cacheRechecksGeometryAtNewPositionAndExpiresByTimeAndDistance() {
        val clock = Clock(); var calls = 0
        val requests = RoadRequests(clock) { object : RoadCall {
            override fun fetch(location: LocationSnapshot): List<RoadWay> { calls++; return listOf(way()) }
            override fun cancel() = Unit
        } }
        requests.fetch(fix())
        val moved = requests.fetch(fix(longitude = 0.00018)) // ~20m away, same cached map
        assertEquals(RoadType.UNKNOWN, moved.type) // Never reuse previous matched road blindly.
        assertTrue(moved.source.cached)
        assertEquals(1, calls)
        clock.advance(61_000)
        requests.fetch(fix(clock.now, longitude = 0.001))
        assertEquals(2, calls)
        clock.advance(121_000)
        requests.fetch(fix(clock.now, longitude = 0.001))
        assertEquals(3, calls)
    }
    @Test fun failureBackoffAndCancelledVersionPreventAdditionalNetworkCalls() {
        val clock = Clock(); var calls = 0
        val requests = RoadRequests(clock) { object : RoadCall {
            override fun fetch(location: LocationSnapshot): List<RoadWay> { calls++; throw IllegalStateException("private response") }
            override fun cancel() = Unit
        } }
        val result = requests.fetch(fix())
        assertEquals(Availability.ERROR, result.availability)
        assertFalse(result.source.detail.orEmpty().contains("private response"))
        requests.fetch(fix()); assertEquals(1, calls)
        clock.advance(60_000); requests.fetch(fix(clock.now)); assertEquals(2, calls)
        clock.advance(60_000); requests.fetch(fix(clock.now)); assertEquals(2, calls)
        val version = requests.version(); requests.cancel(); clock.advance(120_000)
        requests.fetch(fix(clock.now), version); assertEquals(2, calls)
    }
    @Test fun parsesGeometryAndRejectsUnsafeIncompleteOrPartialResponses() {
        assertEquals("테스트 도로", OverpassRoadParser.parse(xml()).single().name)
        assertEquals(2, OverpassRoadParser.parse(xml()).single().geometry.size)
        val invalid = listOf("<!DOCTYPE osm [<!ENTITY secret SYSTEM 'file:///private'>]>" + xml(),
            "<osm><remark>runtime error: timeout</remark></osm>",
            xml().replace("lat=\"-0.001\"", "lat=\"NaN\""),
            xml().replace("lat=\"-0.001\" lon=\"0\"", ""),
            "<html><body>error</body></html>", "x".repeat(524_289))
        invalid.forEach { body -> assertThrows(Exception::class.java) { OverpassRoadParser.parse(body) } }
    }
}
