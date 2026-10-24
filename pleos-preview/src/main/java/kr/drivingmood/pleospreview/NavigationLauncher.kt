package kr.drivingmood.pleospreview

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/**
 * Typed NaviHelper 3.0.5 integration. Requires the official Maven dependency and manifest
 * permissions declared in AndroidManifest.xml. Does not change window bounds or launch
 * a generic map app. All entry points and observer callbacks run on the main thread.
 *
 * SUBMITTED means the SDK call returned, NOT that a screen opened or a route was accepted.
 * Navigation events are global service observations; they have no per-request correlation ID.
 * This class does not attribute an event to a submitted destination or store location history.
 */
class NavigationLauncher(context: Context, private val observe: (Event) -> Unit) : AutoCloseable {
    enum class Connection { NEW, CONNECTING, CONNECTED, UNAVAILABLE, FAILED, CLOSED }
    enum class RequestResult { SUBMITTED, NOT_CONNECTED, PERMISSION_MISSING, INVALID_INPUT, FAILED }

    sealed interface Event {
        data class ConnectionChanged(val connection: Connection) : Event
        data class DestinationReceived(val value: DestinationInfo, val receivedAtElapsedMs: Long) : Event
        data class RouteStateReceived(val value: RouteStateInfo, val receivedAtElapsedMs: Long) : Event
        data class LocationReceived(val value: CurrentLocationInfo, val receivedAtElapsedMs: Long) : Event
        /** The navigation app reported completion; not proof that our requested destination is active. */
        data object RoutingCompleted : Event
        data class NavigationError(val code: NaviErrorCode) : Event
    }

    /** Caller-supplied destination. Never fabricate provider IDs from names or coordinates. */
    data class Destination(
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val address: String = "",
        val poiId: String = "",
        val poiSubId: String = "",
        val poiProvider: String = "",
    ) {
        fun isValid() = latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 && name.isNotBlank() &&
            name.length <= 200 && address.length <= 500 && poiId.length <= 200 &&
            poiSubId.length <= 200 && poiProvider.length <= 100
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sdk by lazy { NaviHelper(app) }
    var connection: Connection = Connection.NEW
        private set
    private val connectionTimeout = Runnable {
        if (connection == Connection.CONNECTING) failConnection()
    }
    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = emit(
            Event.DestinationReceived(destinationInfo, SystemClock.elapsedRealtime()))
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = emit(
            Event.RouteStateReceived(routeStateInfo, SystemClock.elapsedRealtime()))
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = emit(
            Event.LocationReceived(currentLocationInfo, SystemClock.elapsedRealtime()))
        override fun onRoutingCompleted() = emit(Event.RoutingCompleted)
        override fun onError(errorCode: NaviErrorCode) = emit(Event.NavigationError(errorCode))
    }

    /** Observe the service installed on this device before using the SDK's documented binding. */
    @Suppress("DEPRECATION")
    fun hasNavigationService(): Boolean = try {
        val info = app.packageManager.getServiceInfo(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS), 0)
        info.enabled && info.exported && info.applicationInfo.enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun connect() {
        requireMainThread()
        if (connection != Connection.NEW) return
        if (!hasNavigationService()) {
            changeConnection(Connection.UNAVAILABLE)
            return
        }
        changeConnection(Connection.CONNECTING)
        main.postDelayed(connectionTimeout, 20_000)
        try {
            sdk.initialize(onConnected = {
                main.post {
                    if (connection == Connection.CONNECTING) {
                        main.removeCallbacks(connectionTimeout)
                        try {
                            sdk.addListener(listener)
                            changeConnection(Connection.CONNECTED)
                        } catch (_: Exception) { failConnection() }
                    }
                }
            }, onFailed = {
                main.post { if (connection == Connection.CONNECTING) failConnection() }
            })
        } catch (_: Exception) { failConnection() }
    }

    /** Ask the configured navigation app to show itself; placement remains controlled by Pleos. */
    fun openNavigation(): RequestResult = request(PERMISSION_SCREEN) {
        sdk.requestNavigationScreen(NavigationScreenRequest.Foreground)
    }

    /** Search UI only. It does not return a POI list or choose a destination for the user. */
    fun searchPlace(keyword: String): RequestResult {
        requireMainThread()
        val query = keyword.trim()
        if (query.isEmpty() || query.length > 200) return RequestResult.INVALID_INPUT
        return request(PERMISSION_SCREEN) {
            sdk.requestNavigationScreen(NavigationScreenRequest.SearchPlace(query))
        }
    }

    /** Only call after the user selected this real destination. No automatic example route. */
    fun requestDestination(destination: Destination): RequestResult {
        requireMainThread()
        if (!destination.isValid()) return RequestResult.INVALID_INPUT
        return request(PERMISSION_SEARCH) {
            sdk.requestRoute(RouteInfo(latitude = destination.latitude, longitude = destination.longitude,
                poiName = destination.name.trim(), poiId = destination.poiId, address = destination.address,
                poiSubId = destination.poiSubId, poiProvider = destination.poiProvider))
        }
    }

    /** One read per explicit UI action. Results arrive separately and may never arrive. */
    fun readNavigationContext(): RequestResult = request(PERMISSION_READ) {
        sdk.getDestinationInfo()
        sdk.getRouteStateInfo()
        sdk.getCurrentLocationInfo()
    }

    private fun request(permission: String, call: () -> Unit): RequestResult {
        requireMainThread()
        if (connection != Connection.CONNECTED) return RequestResult.NOT_CONNECTED
        if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return RequestResult.PERMISSION_MISSING
        return try {
            call()
            RequestResult.SUBMITTED
        } catch (_: SecurityException) {
            RequestResult.PERMISSION_MISSING
        } catch (_: Exception) {
            RequestResult.FAILED
        }
    }

    private fun emit(event: Event) {
        main.post { if (connection == Connection.CONNECTED) observe(event) }
    }

    private fun changeConnection(value: Connection) {
        connection = value
        observe(Event.ConnectionChanged(value))
    }

    private fun failConnection() {
        main.removeCallbacks(connectionTimeout)
        runCatching { sdk.removeListener(listener) }
        runCatching { sdk.release() }
        changeConnection(Connection.FAILED)
    }

    override fun close() {
        requireMainThread()
        if (connection == Connection.CLOSED) return
        main.removeCallbacksAndMessages(null)
        if (connection in setOf(Connection.CONNECTING, Connection.CONNECTED)) {
            runCatching { sdk.removeListener(listener) }
            runCatching { sdk.release() }
        }
        changeConnection(Connection.CLOSED)
    }

    private fun requireMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        // From the public SDK's ServiceConstants; no vendor activity/deeplink guessed here.
        const val SERVICE_PACKAGE = "ai.pleos.playground.service.navi"
        const val SERVICE_CLASS = "ai.pleos.playground.service.navi.NaviService"
        const val PERMISSION_SCREEN = "pleos.car.permission.NAVI_CUSTOM_MAP"
        const val PERMISSION_SEARCH = "pleos.car.permission.NAVI_ROUTE_SEARCH"
        const val PERMISSION_READ = "pleos.car.permission.NAVI_ROUTE"
    }
}
