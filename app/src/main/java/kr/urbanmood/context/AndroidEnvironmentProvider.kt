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
) : EnvironmentProvider {
    constructor(context: Context, apiKeyProvider: () -> String = { "" }) : this(
        apiKeyProvider, DeviceEnvironmentClock, AndroidOneShotLocation(context.applicationContext),
        WeatherRequests(DeviceEnvironmentClock) { KmaWeatherCall() }, Dispatchers.Main.immediate, Dispatchers.IO,
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
                val weather = configuredWeather ?: withContext(ioDispatcher) { weatherRequests.fetch(key, location, weatherVersion) }
                ensureActive()
                if (session != generation) return@launch
                val snapshot = EnvironmentSnapshot(generatedAtEpochMs = clock.wallTime(), location = location, road = road,
                    weather = weather, notices = listOf(PRIVACY, "도로 정보가 없으면 종류를 추정하지 않습니다. 직접 입력한 값은 '수동 입력'으로 표시합니다."))
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
                    mutableRequest.value = EnvironmentRequestState(requestId, EnvironmentRequestPhase.FAILED, snapshot, started, clock.wallTime(), "환경을 확인하지 못했습니다. 다시 분석해 주세요.")
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
        job?.cancel(); job = null
        mutableRequest.value = mutableRequest.value.copy(phase = EnvironmentRequestPhase.CANCELLED,
            completedAtEpochMs = clock.wallTime(), message = "환경 분석을 취소했습니다.")
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
    companion object { const val PRIVACY = "날씨 조회 시 현재 위치를 5km 격자로 변환해 조회 시각과 함께 기상청에 전달합니다. 영상·이동 이력은 전송하지 않습니다." }
}
