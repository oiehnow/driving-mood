package kr.urbanmood.context

import kr.urbanmood.contracts.*
import kr.urbanmood.integration.AnalysisSnapshot
import kr.urbanmood.recommendation.MoodRecommendationEngine
import kr.urbanmood.ui.EvidencePresenter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RoadEnvironmentIntegrationTest {
    private val clock = object : EnvironmentClock {
        override fun wallTime() = 1_000_000L
        override fun elapsedTime() = 1_000L
    }
    private val fix = LocationSnapshot(Availability.AVAILABLE,0.0,0.0,5f,source = SourceInfo("GPS",clock.wallTime()))
    private val ways = listOf(RoadWay(1,"motorway","테스트",listOf(RoadPoint(-0.001,0.0),RoadPoint(0.001,0.0))))
    private fun provider(roads: RoadRequests, weather: WeatherRequests = WeatherRequests(clock) {
        error("Blank key must not call weather")
    }, key: String = "") = AndroidEnvironmentProvider({ key },clock,object : OneShotLocation {
        override suspend fun acquire() = fix
    },weather,Dispatchers.Unconfined,Dispatchers.IO,roadRequests = roads)
    private suspend fun awaitTerminal(provider: AndroidEnvironmentProvider) {
        withTimeout(3_000) { while (!provider.requestState.value.isTerminal) delay(5) }
    }

    @Test fun privacyExplainsPreciseCoordinateTransferAndMapAttribution() {
        val notice = AndroidEnvironmentProvider.PRIVACY
        assertTrue("Road lookup must disclose precise coordinate transfer", notice.contains("정밀 위도·경도"))
        assertTrue(notice.contains("Overpass"))
        assertTrue(notice.contains("OpenStreetMap contributors"))
        assertTrue(notice.contains("ODbL"))
        assertTrue(notice.contains("영상"))
    }
    @Test fun automaticRoadCompletesWithoutWeatherKeyAndManualOverrideSkipsLookup() = runBlocking {
        var calls = 0
        val roads = RoadRequests(clock) { object : RoadCall {
            override fun fetch(location: LocationSnapshot): List<RoadWay> { calls++; return ways }
            override fun cancel() = Unit
        } }
        val p = provider(roads)
        try {
            p.requestOnce(1); awaitTerminal(p)
            assertEquals(RoadType.HIGHWAY,p.state.value.road.type)
            p.setManualRoad(RoadType.RESIDENTIAL)
            p.requestOnce(2); awaitTerminal(p)
            assertEquals(Availability.MANUAL,p.state.value.road.availability)
            assertEquals(RoadType.RESIDENTIAL,p.state.value.road.type)
            assertEquals(1,calls)
        } finally { p.close() }
    }
    @Test fun automaticRoadCarriesLocationObservationTimeAndIsUsedByEngineAndEvidence() {
        val road = RoadMatcher.match(fix,ways,clock.wallTime())
        val env = EnvironmentSnapshot(generatedAtEpochMs=clock.wallTime(),location=fix,road=road)
        val now = clock.wallTime() + 60_000 // Retains validity through a 60s camera recording.
        val vision = VisionResult(now,AnalysisStatus.OK)
        val result = MoodRecommendationEngine().recommend(RecommendationRequest(vision,env,nowEpochMs=now))
        assertTrue("Automatic mapped road must reach recommendation", result.moodKeywords.contains("고속도로"))
        val evidence = EvidencePresenter.present(result,AnalysisSnapshot(1,now,env,vision))
        assertTrue(evidence.context.single { it.label == "도로" }.value.contains("고속도로"))
        assertEquals(fix.source.observedAtEpochMs,road.source.observedAtEpochMs)
    }
    @Test fun weatherAndRoadStartConcurrentlyAndCancellationSuppressesLateRoadResult() = runBlocking {
        val enteredRoad = CountDownLatch(1); val enteredWeather = CountDownLatch(1)
        val release = CountDownLatch(1); var roadCancelled = false
        val roads = RoadRequests(clock) { object : RoadCall {
            override fun fetch(location: LocationSnapshot): List<RoadWay> {
                enteredRoad.countDown(); release.await(2,TimeUnit.SECONDS); return ways
            }
            override fun cancel() { roadCancelled = true; release.countDown() }
        } }
        // Korea coordinates are required by KMA; use a provider with the same road fixture for cancellation.
        val weather = WeatherRequests(clock) { object : WeatherCall {
            override fun fetch(key: String, grid: KmaGrid, now: Long): WeatherSnapshot {
                enteredWeather.countDown(); release.await(2,TimeUnit.SECONDS); return WeatherSnapshot()
            }
            override fun cancel() = Unit
        } }
        val p = AndroidEnvironmentProvider({ "test" },clock,object : OneShotLocation {
            override suspend fun acquire() = fix.copy(latitude=37.5,longitude=127.0)
        },weather,Dispatchers.Unconfined,Dispatchers.IO,roadRequests=roads)
        try {
            p.requestOnce(1)
            assertTrue(enteredRoad.await(1,TimeUnit.SECONDS))
            assertTrue(enteredWeather.await(1,TimeUnit.SECONDS))
            p.cancelRequest(1)
            assertTrue(roadCancelled)
            delay(50)
            assertEquals(EnvironmentRequestPhase.CANCELLED,p.requestState.value.phase)
            assertEquals(RoadType.UNKNOWN,p.state.value.road.type)
        } finally { release.countDown(); p.close() }
    }
}
