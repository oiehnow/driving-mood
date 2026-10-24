package kr.urbanmood.context

import kr.urbanmood.contracts.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OneShotEnvironmentTest {
    private class Clock : EnvironmentClock {
        override fun wallTime() = Instant.parse("2026-10-24T01:12:00Z").toEpochMilli()
        override fun elapsedTime() = 1_000L
    }
    private class Fixture {
        val clock = Clock()
        var locationCalls = 0
        var httpCalls = 0
        var key = ""
        var pending: CompletableDeferred<LocationSnapshot>? = null
        val fix = LocationSnapshot(Availability.AVAILABLE,37.5665,126.978,10f,source=SourceInfo("fixture",clock.wallTime()))
        val weather = WeatherRequests(clock) { object : WeatherCall {
            override fun cancel() = Unit
            override fun fetch(key: String, grid: KmaGrid, now: Long): WeatherSnapshot {
                httpCalls++
                return WeatherSnapshot(Availability.AVAILABLE, condition="비",source=SourceInfo("fixture",EnvironmentPolicy.baseTime(now).toInstant().toEpochMilli(),now))
            }
        } }
        val provider = AndroidEnvironmentProvider({ key }, clock, object : OneShotLocation {
            override suspend fun acquire(): LocationSnapshot { locationCalls++; return pending?.await() ?: fix }
        }, weather, Dispatchers.Unconfined, Dispatchers.Unconfined, locationTimeoutMs=30)
    }
    @Test fun constructionKeyChangeAndManualSettingsDoNotCollect() {
        val f = Fixture()
        f.key = "fake-key"
        f.provider.setManualWeather(condition="눈")
        f.provider.setManualWeather()
        f.provider.setManualRoad(RoadType.HIGHWAY)
        f.provider.start(); f.provider.refresh()
        assertEquals(0,f.locationCalls); assertEquals(0,f.httpCalls)
        f.provider.close()
    }
    @Test fun explicitAnalysisCompletesOnceAndStopKeepsSnapshot() {
        val f = Fixture(); f.key="fake-key"
        f.provider.requestOnce(1)
        assertEquals(1,f.locationCalls); assertEquals(1,f.httpCalls)
        val result = f.provider.state.value
        val terminal = f.provider.requestState.value
        assertEquals(EnvironmentRequestPhase.COMPLETED,terminal.phase)
        assertEquals(1L,terminal.requestId)
        assertEquals(result,terminal.snapshot)
        assertNotNull(terminal.completedAtEpochMs)
        assertEquals(Availability.AVAILABLE,result.location.availability)
        assertEquals(Availability.AVAILABLE,result.weather.availability)
        f.provider.stop()
        assertEquals(result,f.provider.state.value)
        assertEquals(terminal,f.provider.requestState.value)
        f.provider.requestOnce(1)
        assertEquals(1,f.locationCalls)
        f.provider.requestOnce(2)
        assertEquals(2,f.locationCalls); assertEquals(1,f.httpCalls)
        assertTrue(f.provider.state.value.weather.source.cached)
        f.provider.close()
    }
    @Test fun inFlightTapIsIgnoredAndCancelledOldFixCannotPublish() {
        val f = Fixture(); f.key="fake-key"; f.pending=CompletableDeferred()
        f.provider.requestOnce(1); repeat(10) { f.provider.requestOnce(1); f.provider.requestOnce(2) }
        assertEquals(1,f.locationCalls); assertEquals(0,f.httpCalls)
        val old = f.pending!!
        f.provider.cancelRequest(99)
        assertEquals(EnvironmentRequestPhase.LOCATING,f.provider.requestState.value.phase)
        f.provider.stop(); f.pending=null
        assertEquals(EnvironmentRequestPhase.CANCELLED,f.provider.requestState.value.phase)
        f.provider.requestOnce(2)
        val latest = f.provider.state.value
        old.complete(f.fix.copy(latitude=35.1796,longitude=129.0756))
        assertEquals(latest,f.provider.state.value)
        assertEquals(2,f.locationCalls); assertEquals(1,f.httpCalls)
        f.provider.close()
    }
    @Test fun noKeyStillCompletesLocationWithoutHttp() {
        val f = Fixture(); f.provider.requestOnce(1)
        assertEquals(1,f.locationCalls); assertEquals(0,f.httpCalls)
        assertEquals(Availability.AVAILABLE,f.provider.state.value.location.availability)
        assertEquals(Availability.UNAVAILABLE,f.provider.state.value.weather.availability)
        assertTrue(f.provider.requestState.value.isTerminal)
        f.provider.close()
    }
    @Test fun locationTimeoutEndsWithoutWeatherOrPolling() = runBlocking {
        val f = Fixture(); f.key="fake-key"; f.pending=CompletableDeferred()
        f.provider.requestOnce(1)
        delay(100)
        assertEquals(1,f.locationCalls); assertEquals(0,f.httpCalls)
        assertTrue(f.provider.state.value.location.source.detail.orEmpty().contains("시간"))
        f.provider.close()
    }
    @Test fun inaccurateOrStaleFirstFixDoesNotFinishAcquisitionButFreshMockRemainsManual() {
        val f = Fixture()
        val now = f.clock.wallTime()
        assertTrue(LocationFixPolicy.acceptLive(f.fix,now))
        assertFalse(LocationFixPolicy.acceptLive(f.fix.copy(accuracyMeters=null),now))
        assertFalse(LocationFixPolicy.acceptLive(f.fix.copy(accuracyMeters=5_001f),now))
        assertFalse(LocationFixPolicy.acceptLive(f.fix.copy(availability=Availability.STALE),now))
        assertFalse(LocationFixPolicy.acceptLive(f.fix,now+120_001))
        val mock = f.fix.copy(availability=Availability.MANUAL)
        assertTrue(LocationFixPolicy.acceptLive(mock,now))
        assertFalse(EnvironmentPolicy.usableLocation(mock,now))
        f.provider.close()
    }
}
