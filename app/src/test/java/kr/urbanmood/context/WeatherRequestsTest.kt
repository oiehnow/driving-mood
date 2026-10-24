package kr.urbanmood.context

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WeatherRequestsTest {
    private class Clock : EnvironmentClock {
        var wall = Instant.parse("2026-10-24T01:12:00Z").toEpochMilli()
        var elapsed = 1_000L
        override fun wallTime() = wall
        override fun elapsedTime() = elapsed
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }
    private class Fixture {
        val clock = Clock()
        var calls = 0
        var cancellations = 0
        var failure: WeatherFailure? = null
        var entered: CountDownLatch? = null
        var release: CountDownLatch? = null
        val requests = WeatherRequests(clock) { object : WeatherCall {
            override fun cancel() { cancellations++ }
            override fun fetch(key: String, grid: KmaGrid, now: Long): WeatherSnapshot {
                calls++
                entered?.countDown()
                check(release?.await(3, TimeUnit.SECONDS) != false)
                failure?.let { throw it }
                return WeatherSnapshot(Availability.AVAILABLE, condition = "비",
                    source = SourceInfo("fixture", EnvironmentPolicy.baseTime(now).toInstant().toEpochMilli(), clock.wall))
            }
        } }
        fun location(lat: Double = 37.5665, lon: Double = 126.978) = LocationSnapshot(
            Availability.AVAILABLE, lat, lon, 10f, source = SourceInfo("fixture", clock.wall))
        fun fetch(key: String = "fake-test-key", loc: LocationSnapshot = location()) = requests.fetch(key, loc)
    }
    @Test fun noKeyOrInvalidLocationMakesZeroCalls() {
        val f = Fixture()
        f.fetch(""); f.fetch(loc = LocationSnapshot()); f.fetch(loc = f.location().copy(availability = Availability.MANUAL))
        assertEquals(0, f.calls)
    }
    @Test fun repeatedTapsReuseCacheWithoutChangingObservationOrFetchTimes() {
        val f = Fixture()
        val first = f.fetch()
        f.clock.advance(60_000)
        repeat(20) {
            val cached = f.fetch()
            assertTrue(cached.source.cached)
            assertEquals(first.source.observedAtEpochMs, cached.source.observedAtEpochMs)
            assertEquals(first.source.fetchedAtEpochMs, cached.source.fetchedAtEpochMs)
        }
        assertEquals(1, f.calls)
    }
    @Test fun gridAndTenMinutePublicationWindowAndHourlyBaseInvalidateCache() {
        val f = Fixture()
        f.fetch()
        f.clock.advance(60_000)
        f.fetch(loc = f.location(35.1796,129.0756))
        assertEquals(2, f.calls)
        f.clock.advance(8*60_000) // :21, new update window, same hourly base
        f.fetch(loc = f.location(35.1796,129.0756))
        assertEquals(3, f.calls)
        f.clock.advance(50*60_000) // next hourly base
        f.fetch(loc = f.location(35.1796,129.0756))
        assertEquals(4, f.calls)
    }
    @Test fun rapidGridChangesCannotBypassMinimumInterval() {
        val f = Fixture()
        f.fetch()
        repeat(10) { f.fetch(loc = f.location(35.1796,129.0756)) }
        assertEquals(1, f.calls)
    }
    @Test fun onlyOneTransportRunsAndCancellationCannotCacheLateSuccess() {
        val f = Fixture()
        f.entered = CountDownLatch(1); f.release = CountDownLatch(1)
        val worker = thread { f.fetch() }
        assertTrue(f.entered!!.await(3, TimeUnit.SECONDS))
        repeat(10) { f.fetch() }
        assertEquals(1, f.calls)
        f.requests.cancel()
        f.clock.advance(60_000)
        f.fetch()
        assertEquals(1, f.calls) // cancelled transport has not returned yet
        f.release!!.countDown(); worker.join(3_000)
        assertFalse(worker.isAlive)
        assertEquals(1, f.cancellations)
        val result = f.fetch()
        assertEquals(2, f.calls)
        assertFalse(result.source.cached)
    }
    @Test fun transientFailureBackoffIsGlobalAndGrowsWithoutAutomaticRetry() {
        val f = Fixture()
        f.failure = WeatherFailure("연결 실패")
        assertEquals(Availability.ERROR, f.fetch().availability)
        repeat(10) { f.fetch(loc = f.location(35.1796,129.0756)) }
        assertEquals(1, f.calls)
        f.clock.advance(60_000); f.fetch()
        assertEquals(2, f.calls)
        f.clock.advance(60_000); f.fetch()
        assertEquals(2, f.calls)
        f.clock.advance(60_000); f.failure = null; f.fetch()
        assertEquals(3, f.calls)
    }
    @Test fun quotaWaitSurvivesGridSlotAndKeyChanges() {
        val f = Fixture()
        f.failure = WeatherFailure("기상청 요청 한도 초과", WeatherFailureKind.QUOTA)
        f.fetch()
        f.clock.advance(20*60_000)
        f.fetch(key = "different-fake-key", loc = f.location(35.1796,129.0756))
        assertEquals(1, f.calls)
        f.clock.advance(10*60_000); f.fetch()
        assertEquals(2, f.calls)
    }
    @Test fun clockRollbackNeverMakesOldCacheCurrent() {
        val f = Fixture()
        f.fetch()
        f.clock.wall -= 60_000
        val result = f.fetch()
        assertNotEquals(Availability.AVAILABLE, result.availability)
        assertEquals(1, f.calls)
    }
    @Test fun cancellationBeforeIoDispatchCannotStartTransport() {
        val f = Fixture()
        val version = f.requests.version()
        f.requests.cancel()
        f.requests.fetch("fake-key",f.location(),version)
        assertEquals(0,f.calls)
    }
}
