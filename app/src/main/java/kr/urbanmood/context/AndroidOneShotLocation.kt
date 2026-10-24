package kr.urbanmood.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kr.urbanmood.contracts.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A bounded foreground fix. The caller supplies the 20s deadline; cancellation removes all listeners. */
internal class AndroidOneShotLocation(private val context: Context) : OneShotLocation {
    private val manager = context.getSystemService(LocationManager::class.java)
    private fun permitted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun acquire(): LocationSnapshot {
        val fine = permitted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !permitted(Manifest.permission.ACCESS_COARSE_LOCATION)) return missing(Availability.PERMISSION_DENIED, "위치 권한이 없습니다.")
        val manager = manager ?: return missing(Availability.UNAVAILABLE, "위치 서비스를 사용할 수 없습니다.")
        return suspendCancellableCoroutine { continuation ->
            var finished = false
            lateinit var callback: LocationListener
            fun detach() { runCatching { manager.removeUpdates(callback) } }
            fun finish(result: LocationSnapshot) {
                if (finished) return
                finished = true
                detach()
                if (continuation.isActive) continuation.resume(result)
            }
            callback = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    snapshot(location, false)?.takeIf { LocationFixPolicy.acceptLive(it, DeviceEnvironmentClock.wallTime()) }?.let { finish(it) }
                }
                override fun onProviderDisabled(provider: String) {
                    if (runCatching { manager.getProviders(true).none { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER } }.getOrDefault(true)) {
                        finish(missing(Availability.UNAVAILABLE, "휴대폰 위치 설정을 확인하세요."))
                    }
                }
                @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation { detach() }
            try {
                val providers = listOfNotNull(if (fine) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
                    .filter { manager.allProviders.contains(it) && manager.isProviderEnabled(it) }
                if (providers.isEmpty()) {
                    finish(missing(Availability.UNAVAILABLE, "위치를 사용할 수 없습니다. 휴대폰의 위치 설정을 확인하세요."))
                } else {
                    val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }
                        .sortedByDescending { it.elapsedRealtimeNanos }
                        .mapNotNull { snapshot(it, true) }
                        .firstOrNull { EnvironmentPolicy.usableLocation(it, DeviceEnvironmentClock.wallTime()) }
                    if (cached != null) finish(cached)
                    else providers.forEach {
                        if (!finished && continuation.isActive) manager.requestLocationUpdates(it, 0L, 0f, callback, Looper.getMainLooper())
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
    fun acceptLive(snapshot: LocationSnapshot, now: Long): Boolean =
        EnvironmentPolicy.usableLocation(snapshot, now) ||
            (snapshot.availability == Availability.MANUAL && EnvironmentPolicy.usableLocation(snapshot.copy(availability = Availability.AVAILABLE), now))
}
