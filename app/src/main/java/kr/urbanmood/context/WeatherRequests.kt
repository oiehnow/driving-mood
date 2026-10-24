package kr.urbanmood.context

import kr.urbanmood.contracts.*

internal interface EnvironmentClock {
    fun wallTime(): Long
    fun elapsedTime(): Long
}
internal interface WeatherCall {
    fun fetch(key: String, grid: KmaGrid, now: Long): WeatherSnapshot
    fun cancel()
}
internal class WeatherRequests(
    private val clock: EnvironmentClock,
    private val factory: () -> WeatherCall,
) {
    private data class CacheKey(val grid: KmaGrid, val base: Long, val updateSlot: Long)
    private data class Active(val call: WeatherCall, var cancelled: Boolean = false)
    private val lock = Any()
    private var active: Active? = null
    private var cache: Pair<CacheKey, WeatherSnapshot>? = null
    private var nextAttemptAt = Long.MIN_VALUE
    private var failures = 0
    private var cancellationVersion = 0L
    private var waitReason = "연속 조회를 줄이기 위해 잠시 기다린 뒤 다시 분석해 주세요."

    /** Blocking IO, one attempt only. No key retention, force option, polling or automatic retry. */
    fun version(): Long = synchronized(lock) { cancellationVersion }
    fun fetch(key: String, location: LocationSnapshot, version: Long = version()): WeatherSnapshot {
        val now = clock.wallTime()
        if (key.isBlank()) return unavailable("기상청 인증키가 설정되지 않았습니다.")
        if (!EnvironmentPolicy.usableLocation(location, now)) return unavailable("최근 실제 위치가 없거나 위치 오차가 5km보다 큽니다. 이전 지역의 날씨는 사용하지 않습니다.")
        val grid = KmaGridConverter.from(location.latitude!!, location.longitude!!)
            ?: return unavailable("기상청 날씨 제공 지역 밖입니다.")
        // KMA updates each ten minutes; use one minute publication-boundary buffer.
        val identity = CacheKey(grid, EnvironmentPolicy.baseTime(now).toInstant().toEpochMilli(), (now - 60_000) / 600_000)
        val attempt: Active
        synchronized(lock) {
            if (version != cancellationVersion) return unavailable("날씨 조회를 취소했습니다.")
            cache?.takeIf { (k, w) -> k == identity &&
                EnvironmentPolicy.fresh(w.source.fetchedAtEpochMs, now, EnvironmentPolicy.CACHE_TTL_MS) &&
                EnvironmentPolicy.fresh(w.source.observedAtEpochMs, now, EnvironmentPolicy.WEATHER_MAX_AGE_MS)
            }?.let { return EnvironmentPolicy.cached(it.second, now) }
            if (active != null) return unavailable("날씨 조회가 진행 중입니다. 완료 후 다시 분석해 주세요.")
            if (clock.elapsedTime() < nextAttemptAt) return unavailable(waitReason)
            attempt = Active(factory())
            active = attempt
            nextAttemptAt = clock.elapsedTime() + MIN_INTERVAL_MS
        }
        try {
            val result = attempt.call.fetch(key.trim(), grid, now)
            synchronized(lock) {
                if (attempt.cancelled) return unavailable("날씨 조회를 취소했습니다.")
                if (result.availability == Availability.AVAILABLE) cache = identity to result
                failures = 0
                waitReason = "연속 조회를 줄이기 위해 잠시 기다린 뒤 다시 분석해 주세요."
                return result
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (attempt.cancelled) return unavailable("날씨 조회를 취소했습니다.")
                val failure = error as? WeatherFailure ?: WeatherFailure("기상청 연결 또는 응답 오류")
                failures = (failures + 1).coerceAtMost(5)
                val pause = when (failure.kind) {
                    WeatherFailureKind.QUOTA -> 30 * 60_000L
                    WeatherFailureKind.AUTH -> 15 * 60_000L
                    WeatherFailureKind.TRANSIENT -> (MIN_INTERVAL_MS * (1L shl (failures - 1))).coerceAtMost(15 * 60_000L)
                }
                nextAttemptAt = clock.elapsedTime() + pause
                waitReason = "${failure.safeMessage} 자동으로 다시 요청하지 않습니다. 잠시 기다린 뒤 다시 분석해 주세요."
                return WeatherSnapshot(Availability.ERROR, source = SourceInfo("기상청 조회 실패", fetchedAtEpochMs = clock.wallTime(), detail = waitReason))
            }
        } finally {
            synchronized(lock) { if (active === attempt) active = null }
        }
    }

    fun cancel() {
        val call = synchronized(lock) {
            cancellationVersion++
            active?.takeUnless { it.cancelled }?.also { it.cancelled = true }?.call
        }
        call?.cancel() // Retain active reservation until the cancelled IO actually returns.
    }
    private fun unavailable(detail: String) = WeatherSnapshot(source = SourceInfo("날씨 미연결", detail = detail))
    companion object { private const val MIN_INTERVAL_MS = 60_000L }
}
