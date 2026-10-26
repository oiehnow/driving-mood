package kr.urbanmood.context

import android.content.Context
import android.os.SystemClock
import kr.urbanmood.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal object DeviceEnvironmentClock : EnvironmentClock {
    override fun wallTime() = System.currentTimeMillis()
    override fun elapsedTime() = SystemClock.elapsedRealtime()
}

internal interface OneShotLocation { suspend fun acquire(): LocationSnapshot }

/** Main-thread commands. Only requestOnce starts collection; configuration never performs IO. */
class AndroidEnvironmentProvider internal constructor(
    private val apiKeyProvider: () -> String,
    private val clock: EnvironmentClock,
    private val locationSource: OneShotLocation,
    private val weatherRequests: WeatherRequests,
    mainDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val locationTimeoutMs: Long = 20_000L,
    private val roadRequests: RoadRequests? = null,
) : EnvironmentProvider {
    constructor(context: Context, apiKeyProvider: () -> String = { "" }) : this(
        apiKeyProvider, DeviceEnvironmentClock, AndroidOneShotLocation(context.applicationContext),
        WeatherRequests(DeviceEnvironmentClock) { KmaWeatherCall() }, Dispatchers.Main.immediate, Dispatchers.IO,
        roadRequests = RoadRequests(DeviceEnvironmentClock) { OverpassRoadCall() },
    )
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val mutableState = MutableStateFlow(EnvironmentSnapshot(notices = listOf(PRIVACY)))
    override val state: StateFlow<EnvironmentSnapshot> = mutableState.asStateFlow()
    private val mutableRequest = MutableStateFlow(EnvironmentRequestState())
    override val requestState: StateFlow<EnvironmentRequestState> = mutableRequest.asStateFlow()
    private var closed = false
    private var running = false
    private var generation = 0L
    private val acceptedRequestIds = mutableSetOf<Long>()
    private var job: Job? = null
    private var manualWeather: WeatherSnapshot? = null
    private var manualRoad = RoadContext()

    // Legacy lifecycle hooks intentionally do not acquire resources.
    override fun start() = Unit
    override fun refresh() = Unit

    override fun requestOnce(requestId: Long) {
        if (closed || running || requestId <= 0 || !acceptedRequestIds.add(requestId)) return
        val session = ++generation
        val started = clock.wallTime()
        val road = manualRoad
        val configuredWeather = manualWeather
        val weatherVersion = weatherRequests.version()
        val roadVersion = roadRequests?.version()
        val key = runCatching { apiKeyProvider().trim() }.getOrDefault("")
        running = true
        mutableRequest.value = EnvironmentRequestState(requestId, EnvironmentRequestPhase.LOCATING,
            snapshot = EnvironmentSnapshot(generatedAtEpochMs = started, road = road, weather = configuredWeather ?: WeatherSnapshot()), startedAtEpochMs = started)
        job = scope.launch {
            try {
                val location = withTimeoutOrNull(locationTimeoutMs) { locationSource.acquire() }
                    ?: LocationSnapshot(source = SourceInfo("위치 미수신", detail = "위치 확인 제한시간이 지났습니다. 실외에서 다시 분석해 주세요."))
                ensureActive()
                if (session != generation) return@launch
                mutableRequest.value = mutableRequest.value.copy(phase = EnvironmentRequestPhase.FETCHING_WEATHER,
                    snapshot = mutableRequest.value.snapshot?.copy(location = location))
                val (weather, resolvedRoad) = coroutineScope {
                    val weatherTask = async(ioDispatcher) { configuredWeather ?: weatherRequests.fetch(key, location, weatherVersion) }
                    val roadTask = async(ioDispatcher) {
                        if (road.availability == Availability.MANUAL) road
                        else roadRequests?.fetch(location, roadVersion!!) ?: road
                    }
                    weatherTask.await() to roadTask.await()
                }
                ensureActive()
                if (session != generation) return@launch
                val snapshot = EnvironmentSnapshot(generatedAtEpochMs = clock.wallTime(), location = location, road = resolvedRoad,
                    weather = weather, notices = listOf(PRIVACY, "자동 도로는 주변 지도 도로 추정이며 실제 주행 도로를 확정하지 않습니다. 평행도로·교차로 등 모호한 곳은 미확인으로 표시합니다. 직접 입력한 값은 '수동 입력'입니다."))
                mutableState.value = snapshot
                mutableRequest.value = EnvironmentRequestState(requestId, EnvironmentRequestPhase.COMPLETED, snapshot, started, clock.wallTime())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (session == generation) {
                    val snapshot = EnvironmentSnapshot(generatedAtEpochMs = clock.wallTime(), road = road,
                        location = LocationSnapshot(Availability.ERROR, source = SourceInfo("위치 확인 실패")),
                        weather = configuredWeather ?: WeatherSnapshot(), notices = listOf(PRIVACY))
                    mutableState.value = snapshot
                    mutableRequest.value = EnvironmentRequestState(requestId, EnvironmentRequestPhase.FAILED, snapshot, started, clock.wallTime(), "위치와 날씨를 확인하지 못했습니다. 휴대폰의 위치 설정과 인터넷 연결을 확인한 뒤 다시 분석해 주세요.")
                }
            } finally {
                if (session == generation) running = false
            }
        }
    }

    override fun cancelRequest(requestId: Long) {
        if (!running || mutableRequest.value.requestId != requestId) return
        generation++
        running = false
        weatherRequests.cancel()
        roadRequests?.cancel()
        job?.cancel(); job = null
        mutableRequest.value = mutableRequest.value.copy(phase = EnvironmentRequestPhase.CANCELLED,
            completedAtEpochMs = clock.wallTime(), message = "위치·날씨 확인을 취소했습니다.")
    }
    override fun stop() { if (running) cancelRequest(mutableRequest.value.requestId) }
    override fun close() { if (!closed) { stop(); closed = true; scope.cancel() } }

    fun setManualWeather(temperatureC: Double? = null, condition: String? = null) {
        require(temperatureC == null || temperatureC.isFinite() && temperatureC in -80.0..60.0)
        require(condition == null || condition in setOf("비", "비/눈", "눈", "소나기", "빗방울", "눈날림", "빗방울눈날림", "강수 없음"))
        manualWeather = if (temperatureC == null && condition == null) null else WeatherSnapshot(
            availability = Availability.MANUAL, temperatureC = temperatureC, condition = condition,
            source = SourceInfo("수동 입력", fetchedAtEpochMs = clock.wallTime(), detail = "실제 관측값이 아닙니다. 표시된 시각은 입력 시각입니다."))
    }
    fun setManualRoad(type: RoadType?) {
        manualRoad = if (type == null || type == RoadType.UNKNOWN) RoadContext() else RoadContext(
            Availability.MANUAL, type, source = SourceInfo("수동 입력 도로", fetchedAtEpochMs = clock.wallTime(), detail = "자동으로 확인한 도로 정보가 아닙니다."))
    }
    companion object { const val PRIVACY = "날씨 조회 시 현재 위치를 5km 격자로 변환해 조회 시각과 함께 기상청에 전달합니다. 자동 도로 조회 시 정밀 위도·경도를 OpenStreetMap Overpass에 전달합니다. 지도 출처: © OpenStreetMap contributors (ODbL, https://www.openstreetmap.org/copyright). 영상·이동 이력은 전송하지 않습니다." }
}
