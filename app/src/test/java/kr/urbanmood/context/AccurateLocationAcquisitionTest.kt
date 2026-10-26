package kr.urbanmood.context

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import kr.urbanmood.contracts.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Real Android location adapter with simulated providers; no claim of physical GPS accuracy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccurateLocationAcquisitionTest {
    private lateinit var app: Application
    private lateinit var manager: LocationManager
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
    }
    private fun location(provider: String, accuracy: Float, age: Long = 0) = Location(provider).apply {
        latitude = 37.5; longitude = 127.0; this.accuracy = accuracy
        time = System.currentTimeMillis() - age
        elapsedRealtimeNanos = (SystemClock.elapsedRealtime() - age) * 1_000_000
    }
    private class Capture(app: Application) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var result: LocationSnapshot? = null
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { result = AndroidOneShotLocation(app).acquire() }
        override fun close() { scope.cancel() }
    }
    private fun deliver(location: Location) {
        shadowOf(manager).simulateLocation(location)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun accurateGpsCacheBeatsNewerCoarseNetworkCacheWithFinePermission() {
        val gps = location(LocationManager.GPS_PROVIDER, 10f, 5_000)
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, location(LocationManager.NETWORK_PROVIDER, 500f))
        Capture(app).use { capture ->
            assertEquals(10f, capture.result?.accuracyMeters)
            assertEquals(gps.time, capture.result?.source?.observedAtEpochMs)
            assertTrue(capture.result!!.source.cached)
            assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        }
    }
    @Test fun coarseLiveFixDoesNotStopGpsBeforeAccurateFixArrives() {
        Capture(app).use { capture ->
            deliver(location(LocationManager.NETWORK_PROVIDER, 500f))
            assertNull("Keep GPS acquisition active after coarse network fix", capture.result)
            assertFalse(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
            val gps = location(LocationManager.GPS_PROVIDER, 10f)
            deliver(gps)
            assertEquals(10f, capture.result?.accuracyMeters)
            assertEquals(gps.time, capture.result?.source?.observedAtEpochMs)
            assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        }
    }
    @Test fun fineWaitReturnsOriginalCoarseFixAt18SecondsWithoutRefreshingItsTimestamp() {
        val coarse = location(LocationManager.NETWORK_PROVIDER, 500f)
        Capture(app).use { capture ->
            deliver(coarse)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17_999))
            assertNull(capture.result)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(Availability.AVAILABLE, capture.result?.availability)
            assertEquals(coarse.time, capture.result?.source?.observedAtEpochMs)
            assertEquals(500f, capture.result?.accuracyMeters)
            assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        }
    }
    @Test fun coarseOnlyPermissionReturnsNetworkFixPromptly() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Capture(app).use { capture ->
            deliver(location(LocationManager.NETWORK_PROVIDER, 500f))
            assertEquals(Availability.AVAILABLE, capture.result?.availability)
            assertEquals(500f, capture.result?.accuracyMeters)
            assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        }
    }
    @Test fun acceptedCoarseCacheRetainsOriginalTimestampAfterCrossingAcquisitionAgeDuringWait() {
        val cached = location(LocationManager.NETWORK_PROVIDER, 500f, 25_000)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, cached)
        Capture(app).use { capture ->
            assertNull(capture.result)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(18))
            assertEquals(Availability.AVAILABLE, capture.result?.availability)
            assertEquals(cached.time, capture.result?.source?.observedAtEpochMs)
            assertTrue(EnvironmentPolicy.usableLocation(capture.result!!,System.currentTimeMillis()))
            assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        }
    }
    @Test fun futureDatedCacheNeverBecomesWeatherFallback() {
        val future = location(LocationManager.NETWORK_PROVIDER, 500f).apply { time += 60_000 }
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER,future)
        Capture(app).use { capture ->
            assertNull(capture.result)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(18))
            assertEquals(Availability.UNAVAILABLE, capture.result?.availability)
            assertNull(capture.result?.source?.observedAtEpochMs)
            assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        }
    }
    @Test fun cancellationRemovesBothListenersAndDeadlineCannotPublishLater() {
        Capture(app).use { capture ->
            deliver(location(LocationManager.NETWORK_PROVIDER, 500f))
            assertNull(capture.result)
            capture.job.cancel()
            assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            deliver(location(LocationManager.GPS_PROVIDER, 10f))
            assertNull(capture.result)
            assertTrue(capture.job.isCancelled)
        }
    }
}
