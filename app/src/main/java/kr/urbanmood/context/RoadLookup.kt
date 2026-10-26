package kr.urbanmood.context

import kr.urbanmood.contracts.*
import kotlin.math.*

internal data class RoadPoint(val latitude: Double, val longitude: Double)
internal data class RoadWay(val id: Long, val highway: String, val name: String?, val geometry: List<RoadPoint>)

/** Nearest mapped way estimate, deliberately not navigation map matching. */
internal object RoadMatcher {
    const val ATTRIBUTION = "© OpenStreetMap contributors (ODbL) · Overpass"
    val DRIVING_HIGHWAYS = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
        "secondary", "secondary_link", "tertiary", "tertiary_link", "residential", "unclassified", "living_street", "service", "road", "track")
    fun eligible(location: LocationSnapshot, now: Long): Boolean = usableLocation(location, now, 30_000)

    private fun usableLocation(location: LocationSnapshot, now: Long, maxAge: Long): Boolean =
        location.availability == Availability.AVAILABLE &&
            location.latitude?.let { it.isFinite() && it in -85.0..85.0 } == true &&
            location.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
            location.accuracyMeters?.let { it.isFinite() && it in 0f..50f } == true &&
            EnvironmentPolicy.fresh(location.source.observedAtEpochMs, now, maxAge)

    fun match(location: LocationSnapshot, ways: List<RoadWay>, now: Long, cached: Boolean = false): RoadContext {
        fun unknown(reason: String) = RoadContext(source = SourceInfo(ATTRIBUTION,
            fetchedAtEpochMs = now, cached = cached, detail = reason))
        // Acquisition must be <=30s, but a bounded network request may finish after that threshold.
        if (!usableLocation(location, now, EnvironmentPolicy.LOCATION_MAX_AGE_MS)) return unknown("도로를 판정할 최신 실제 위치가 없거나 위치 오차가 50m보다 큽니다.")
        val point = RoadPoint(location.latitude!!, location.longitude!!)
        val ranked = ways.filter { it.highway in DRIVING_HIGHWAYS }.map { it to distanceToWay(point, it) }.sortedBy { it.second }
        val nearest = ranked.firstOrNull() ?: return unknown("주변 지도 도로 정보가 없습니다.")
        val accuracy = location.accuracyMeters!!.toDouble()
        if (nearest.second > max(10.0, accuracy)) return unknown("위치 오차 범위에서 가까운 지도 도로를 확인하지 못했습니다.")
        // Even equal name/type ways remain separate: parallel carriageways and intersections are ambiguous.
        if (ranked.drop(1).any { it.second <= nearest.second + 2 * accuracy + 10 }) {
            return unknown("평행도로·교차로 등 여러 도로가 가까워 도로 종류를 확정하지 않습니다.")
        }
        val way = nearest.first
        val type = when (way.highway) {
            "motorway" -> RoadType.HIGHWAY
            "residential" -> RoadType.RESIDENTIAL
            else -> RoadType.OTHER
        }
        return RoadContext(Availability.AVAILABLE, type, way.name,
            SourceInfo(ATTRIBUTION, observedAtEpochMs = location.source.observedAtEpochMs, fetchedAtEpochMs = now, cached = cached,
                detail = "주변 지도 도로 추정 · OSM highway=${way.highway} · 지도 선분까지 약 ${nearest.second.roundToInt()}m. " +
                    "실제 주행 도로를 확인하는 내비게이션 경로 매칭이 아닙니다. 관측 시각은 GPS 위치 시각이며 지도 갱신 시각이 아닙니다. https://www.openstreetmap.org/copyright"))
    }

    fun distance(a: RoadPoint, b: RoadPoint): Double {
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val h = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) *
            sin(Math.toRadians(b.longitude - a.longitude) / 2).pow(2)
        return 6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun distanceToWay(point: RoadPoint, way: RoadWay): Double {
        val scale = 6_371_008.8 * PI / 180
        fun project(p: RoadPoint): Pair<Double, Double> {
            val deltaLongitude = ((p.longitude - point.longitude + 540) % 360) - 180
            return deltaLongitude * scale * cos(Math.toRadians(point.latitude)) to (p.latitude - point.latitude) * scale
        }
        return way.geometry.zipWithNext().minOfOrNull { (start, end) ->
            val (ax, ay) = project(start); val (bx, by) = project(end)
            val dx = bx - ax; val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            // Conservative local projection: never infer a match from a distant, huge segment.
            if (lengthSquared > 100_000_000 || hypot(ax, ay) > 50_000 || hypot(bx, by) > 50_000) Double.POSITIVE_INFINITY
            else {
                val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
                hypot(ax + t * dx, ay + t * dy)
            }
        } ?: Double.POSITIVE_INFINITY
    }
}

internal interface RoadCall {
    fun fetch(location: LocationSnapshot): List<RoadWay>
    fun cancel()
}

/** Memory-only geometry cache; one attempt per analysis, no automatic retry or background polling. */
internal class RoadRequests(private val clock: EnvironmentClock, private val factory: () -> RoadCall) {
    private data class Cache(val center: RoadPoint, val fetchedAt: Long, val elapsedAt: Long, val ways: List<RoadWay>)
    private data class Active(val call: RoadCall, var cancelled: Boolean = false)
    private val lock = Any()
    private var cache: Cache? = null
    private var active: Active? = null
    private var cancellationVersion = 0L
    private var nextAttemptAt = Long.MIN_VALUE
    private var failures = 0
    private var waitReason = "연속 도로 조회를 줄이기 위해 잠시 기다린 뒤 다시 분석해 주세요."

    fun version(): Long = synchronized(lock) { cancellationVersion }
    fun fetch(location: LocationSnapshot, version: Long = version()): RoadContext {
        val now = clock.wallTime()
        if (!RoadMatcher.eligible(location, now)) return unavailable("도로 조회에는 30초 이내의 실제 위치와 50m 이하의 위치 오차가 필요합니다.")
        val center = RoadPoint(location.latitude!!, location.longitude!!)
        val attempt: Active
        synchronized(lock) {
            if (version != cancellationVersion) return unavailable("도로 조회를 취소했습니다.")
            cache?.takeIf { clock.elapsedTime() - it.elapsedAt in 0..120_000 && now - it.fetchedAt in 0..120_000 &&
                RoadMatcher.distance(center, it.center) <= 25 }?.let {
                return RoadMatcher.match(location, it.ways, now, cached = true).let { road ->
                    road.copy(source = road.source.copy(fetchedAtEpochMs = it.fetchedAt))
                }
            }
            if (active != null) return unavailable("도로 조회가 진행 중입니다.")
            if (clock.elapsedTime() < nextAttemptAt) return unavailable(waitReason)
            attempt = Active(factory())
            active = attempt
            nextAttemptAt = clock.elapsedTime() + 60_000
        }
        try {
            val ways = attempt.call.fetch(location)
            synchronized(lock) {
                if (attempt.cancelled) return unavailable("도로 조회를 취소했습니다.")
                val fetchedAt = clock.wallTime()
                cache = Cache(center, fetchedAt, clock.elapsedTime(), ways)
                failures = 0
                waitReason = "연속 도로 조회를 줄이기 위해 잠시 기다린 뒤 다시 분석해 주세요."
                return RoadMatcher.match(location, ways, fetchedAt)
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (attempt.cancelled) return unavailable("도로 조회를 취소했습니다.")
                failures = (failures + 1).coerceAtMost(5)
                val failure = error as? RoadFailure ?: RoadFailure("도로 연결 또는 응답 오류")
                val pause = if (failure.rateLimited) 30 * 60_000L else (60_000L shl (failures - 1)).coerceAtMost(15 * 60_000L)
                nextAttemptAt = clock.elapsedTime() + pause
                waitReason = "${failure.safeMessage} 자동 재시도하지 않습니다. 잠시 뒤 다시 분석해 주세요."
                return RoadContext(Availability.ERROR, source = SourceInfo(RoadMatcher.ATTRIBUTION,
                    fetchedAtEpochMs = clock.wallTime(), detail = waitReason))
            }
        } finally { synchronized(lock) { if (active === attempt) active = null } }
    }
    fun cancel() {
        val call = synchronized(lock) {
            cancellationVersion++
            active?.takeUnless { it.cancelled }?.also { it.cancelled = true }?.call
        }
        call?.cancel()
    }
    private fun unavailable(detail: String) = RoadContext(source = SourceInfo(RoadMatcher.ATTRIBUTION, detail = detail))
}

internal class RoadFailure(val safeMessage: String, val rateLimited: Boolean = false) : Exception(safeMessage)
