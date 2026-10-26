package kr.urbanmood.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kr.urbanmood.contracts.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Main-thread foreground acquisition. Fine permission allows an 18s accurate-fix wait inside the caller's 20s guard. */
internal class AndroidOneShotLocation(private val context: Context) : OneShotLocation {
    private val manager = context.getSystemService(LocationManager::class.java)
    private fun permitted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun acquire(): LocationSnapshot {
        val fine = permitted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !permitted(Manifest.permission.ACCESS_COARSE_LOCATION)) return missing(Availability.PERMISSION_DENIED, "위치 권한이 없습니다.")
        val manager = manager ?: return missing(Availability.UNAVAILABLE, "위치 서비스를 사용할 수 없습니다.")
        return suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var deadline: Runnable? = null
            // Keep one latest accepted fallback per provider, so an older better fix cannot hide a newer usable one.
            val fallbacks = mutableMapOf<String, LocationSnapshot>()
            lateinit var callback: LocationListener
            fun detach() {
                deadline?.let { handler.removeCallbacks(it) }
                runCatching { manager.removeUpdates(callback) }
            }
            fun finish(result: LocationSnapshot) {
                if (!finished.compareAndSet(false, true)) return
                detach()
                if (continuation.isActive) continuation.resume(result)
            }
            fun remember(result: LocationSnapshot) {
                val previous = fallbacks[result.source.name]
                if (previous == null || result.source.observedAtEpochMs!! >= previous.source.observedAtEpochMs!!) {
                    fallbacks[result.source.name] = result
                }
            }
            fun fallback(): LocationSnapshot? = fallbacks.values.filter {
                val actual = if (it.availability == Availability.MANUAL) it.copy(availability = Availability.AVAILABLE) else it
                // The 30s acquisition gate was already checked on arrival. Keep valid weather data through this wait.
                EnvironmentPolicy.usableLocation(actual, DeviceEnvironmentClock.wallTime())
            }.minWithOrNull(compareBy<LocationSnapshot> { it.accuracyMeters }.thenByDescending { it.source.observedAtEpochMs })
            callback = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (finished.get()) return
                    snapshot(location, false)?.takeIf { LocationFixPolicy.acceptLive(it, DeviceEnvironmentClock.wallTime()) }?.let {
                        if (!fine || RoadMatcher.eligible(it, DeviceEnvironmentClock.wallTime())) finish(it)
                        else remember(it)
                    }
                }
                override fun onProviderDisabled(provider: String) {
                    if (runCatching { manager.getProviders(true).none { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER } }.getOrDefault(true)) {
                        finish(fallback() ?: missing(Availability.UNAVAILABLE, "휴대폰 위치 설정을 확인하세요."))
                    }
                }
                @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            deadline = Runnable {
                finish(fallback() ?: missing(Availability.UNAVAILABLE, "정밀 위치를 확인하지 못했습니다. 실외에서 다시 분석해 주세요."))
            }
            continuation.invokeOnCancellation { if (finished.compareAndSet(false, true)) detach() }
            try {
                val providers = listOfNotNull(if (fine) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
                    .filter { manager.allProviders.contains(it) && manager.isProviderEnabled(it) }
                if (providers.isEmpty()) {
                    finish(missing(Availability.UNAVAILABLE, "위치를 사용할 수 없습니다. 휴대폰의 위치 설정을 확인하세요."))
                } else {
                    val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }
                        .sortedByDescending { it.elapsedRealtimeNanos }
                        .mapNotNull { snapshot(it, true) }
                        .filter { it.availability == Availability.AVAILABLE &&
                            LocationFixPolicy.acceptLive(it, DeviceEnvironmentClock.wallTime()) }
                    val preferred = cached.firstOrNull { !fine || RoadMatcher.eligible(it, DeviceEnvironmentClock.wallTime()) }
                    if (preferred != null) finish(preferred)
                    else {
                        cached.forEach { remember(it) }
                        if (fine && !finished.get()) deadline?.let { handler.postDelayed(it, 18_000) }
                        providers.forEach {
                            if (!finished.get() && continuation.isActive) manager.requestLocationUpdates(it, 0L, 0f, callback, Looper.getMainLooper())
                        }
                    }
                }
            } catch (_: SecurityException) {
                finish(missing(Availability.PERMISSION_DENIED, "위치 권한이 없거나 취소되었습니다."))
            } catch (_: RuntimeException) {
                finish(missing(Availability.ERROR, "위치를 확인하지 못했습니다."))
            }
        }
    }

    private fun missing(status: Availability, detail: String) = LocationSnapshot(status, source = SourceInfo("휴대폰 위치", detail = detail))
    private fun snapshot(location: Location, cached: Boolean): LocationSnapshot? {
        if (!location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return null
        val age = DeviceEnvironmentClock.elapsedTime() - location.elapsedRealtimeNanos / 1_000_000
        val mock = if (android.os.Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
        val status = when {
            age !in 0..EnvironmentPolicy.LOCATION_MAX_AGE_MS || !EnvironmentPolicy.fresh(location.time, DeviceEnvironmentClock.wallTime(), EnvironmentPolicy.LOCATION_MAX_AGE_MS) -> Availability.STALE
            mock -> Availability.MANUAL
            else -> Availability.AVAILABLE
        }
        return LocationSnapshot(status, location.latitude, location.longitude,
            location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() && it >= 0f },
            location.bearing.takeIf { location.hasBearing() && it.isFinite() },
            SourceInfo(if (mock) "모의 위치 (수동 입력)" else when (location.provider) {
                LocationManager.GPS_PROVIDER -> "휴대폰 GPS"
                LocationManager.NETWORK_PROVIDER -> "휴대폰 네트워크 위치"
                else -> "휴대폰 위치"
            }, location.time, DeviceEnvironmentClock.wallTime(), cached,
                "${if (cached) "이전에 확인한 위치입니다. " else ""}${if (mock) "실제 측정한 위치가 아닙니다. " else ""}이동 방향과 카메라 촬영 방향은 다를 수 있습니다."))
    }
}

internal object LocationFixPolicy {
    // Acquisition precedes a recording of up to 60s (plus a 30s camera bound).
    // A fix near the 120s evaluation limit would expire before the capture ends.
    private const val ACQUISITION_MAX_AGE_MS = 30_000L
    fun acceptLive(snapshot: LocationSnapshot, now: Long): Boolean =
        EnvironmentPolicy.fresh(snapshot.source.observedAtEpochMs, now, ACQUISITION_MAX_AGE_MS) &&
            (EnvironmentPolicy.usableLocation(snapshot, now) ||
                (snapshot.availability == Availability.MANUAL && EnvironmentPolicy.usableLocation(snapshot.copy(availability = Availability.AVAILABLE), now)))
}
