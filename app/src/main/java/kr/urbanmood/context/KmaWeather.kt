package kr.urbanmood.context

import kr.urbanmood.contracts.*
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.*
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import kotlin.math.*

data class KmaGrid(val x: Int, val y: Int)

/** KMA 2026-10-19 guide, Lambert projection; API grid is one based.
 * See docs/context-audio/SOURCES.md. Formula independently expressed in Kotlin.
 */
object KmaGridConverter {
    fun from(latitude: Double, longitude: Double): KmaGrid? {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -89.0..89.0 || longitude !in -180.0..180.0) return null
        val rad = PI / 180.0
        val a = 30.0 * rad
        val b = 60.0 * rad
        val n = ln(cos(a) / cos(b)) / ln(tan(PI / 4 + b / 2) / tan(PI / 4 + a / 2))
        val f = tan(PI / 4 + a / 2).pow(n) * cos(a) / n
        val radius = 6371.00877 / 5.0
        val rho0 = radius * f / tan(PI / 4 + 38.0 * rad / 2).pow(n)
        val rho = radius * f / tan(PI / 4 + latitude * rad / 2).pow(n)
        var theta = (longitude - 126.0) * rad
        if (theta > PI) theta -= 2 * PI
        if (theta < -PI) theta += 2 * PI
        theta *= n
        val grid = KmaGrid(floor(rho * sin(theta) + 43.5).toInt(), floor(rho0 - rho * cos(theta) + 136.5).toInt())
        return grid.takeIf { it.x in 1..149 && it.y in 1..253 }
    }
}

object EnvironmentPolicy {
    const val LOCATION_MAX_AGE_MS = 120_000L
    const val WEATHER_MAX_AGE_MS = 90 * 60_000L
    const val CACHE_TTL_MS = 10 * 60_000L
    val KOREA: ZoneId = ZoneId.of("Asia/Seoul")

    // Official guide: hourly base, available after HH:10. A minute buffer avoids boundary races.
    fun baseTime(now: Long): ZonedDateTime = Instant.ofEpochMilli(now).atZone(KOREA)
        .minusMinutes(11).withMinute(0).withSecond(0).withNano(0)

    fun fresh(observed: Long?, now: Long, maxAge: Long): Boolean =
        observed != null && now - observed in 0..maxAge

    fun usableLocation(location: LocationSnapshot, now: Long): Boolean =
        location.availability == Availability.AVAILABLE &&
            location.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            location.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
            location.accuracyMeters?.let { it.isFinite() && it in 0f..5000f } == true &&
            fresh(location.source.observedAtEpochMs, now, LOCATION_MAX_AGE_MS)

    fun cached(weather: WeatherSnapshot, now: Long, failure: String? = null): WeatherSnapshot {
        val fresh = fresh(weather.source.observedAtEpochMs, now, WEATHER_MAX_AGE_MS)
        return weather.copy(
            availability = if (fresh) Availability.AVAILABLE else Availability.STALE,
            source = weather.source.copy(cached = true, detail = listOfNotNull(
                weather.source.detail, "이전 조회 정보", failure,
                if (!fresh) "오래된 관측 — 현재 날씨 추천에서 제외" else null,
            ).joinToString(" · ")),
        )
    }
}

/** Bounded XML parsing; rejects DTDs/entities before the parser sees untrusted content. */
internal object KmaResponseParser {
    fun parse(xml: String, grid: KmaGrid, base: ZonedDateTime, fetchedAt: Long): WeatherSnapshot {
        require(xml.length <= 524_288 && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
        val builder = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        builder.setErrorHandler(DefaultHandler())
        val doc = builder.parse(InputSource(StringReader(xml)))
        fun text(tag: String) = doc.getElementsByTagName(tag).item(0)?.textContent?.trim()
        val code = text("resultCode") ?: text("returnReasonCode") ?: throw WeatherFailure("기상청 응답 형식 오류")
        if (code != "00") throw WeatherFailure.forCode(code)
        val items = doc.getElementsByTagName("item")
        val values = mutableMapOf<String, String>()
        for (i in 0 until items.length) {
            val item = items.item(i) as? org.w3c.dom.Element ?: continue
            fun field(name: String) = item.getElementsByTagName(name).item(0)?.textContent?.trim()
            if (field("nx")?.toIntOrNull() != grid.x || field("ny")?.toIntOrNull() != grid.y ||
                field("baseDate") != base.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE) ||
                field("baseTime") != base.format(DateTimeFormatter.ofPattern("HHmm"))) {
                throw WeatherFailure("관측 위치·시각 불일치")
            }
            val category = field("category") ?: continue
            val value = field("obsrValue") ?: continue
            if (values.put(category, value) != null) throw WeatherFailure("관측 항목 중복")
        }
        val temperature = values["T1H"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -80.0..60.0 }
        val rain = values["RN1"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1000.0 }
        val condition = when (values["PTY"]?.toIntOrNull()) {
            0 -> "강수 없음"; 1 -> "비"; 2 -> "비/눈"; 3 -> "눈"; 4 -> "소나기"
            5 -> "빗방울"; 6 -> "빗방울눈날림"; 7 -> "눈날림"; else -> null
        }
        if (temperature == null && rain == null && condition == null) throw WeatherFailure("사용 가능한 관측 항목 없음")
        val missing = buildList {
            if (temperature == null) add("기온")
            if (rain == null) add("강수량")
            if (condition == null) add("강수형태")
        }
        val observedAt = base.toInstant().toEpochMilli()
        return WeatherSnapshot(
            if (EnvironmentPolicy.fresh(observedAt, fetchedAt, EnvironmentPolicy.WEATHER_MAX_AGE_MS)) Availability.AVAILABLE else Availability.STALE,
            temperature, rain, condition,
            SourceInfo("기상청·공공데이터포털 초단기실황", observedAt, fetchedAt, false,
                "주변 5km 격자의 대표 관측값입니다. 강수량은 지난 1시간 기준입니다." +
                    if (missing.isEmpty()) "" else " · 부분 누락: ${missing.joinToString()}")
        )
    }
}

internal enum class WeatherFailureKind { TRANSIENT, QUOTA, AUTH }
internal class WeatherFailure(val safeMessage: String, val kind: WeatherFailureKind = WeatherFailureKind.TRANSIENT) : Exception(safeMessage) {
    companion object {
        fun forCode(code: String): WeatherFailure = when (code) {
            "03" -> WeatherFailure("해당 지역·시각의 관측 정보가 없습니다.")
            "22" -> WeatherFailure("기상청 요청 한도를 초과했습니다.", WeatherFailureKind.QUOTA)
            "20" -> WeatherFailure("기상청 서비스 접근이 거부되었습니다. 이용 신청 상태를 확인하세요.", WeatherFailureKind.AUTH)
            "21", "30", "31", "32", "33" -> WeatherFailure("기상청 인증·접근 설정을 확인하세요.", WeatherFailureKind.AUTH)
            else -> WeatherFailure("기상청 날씨를 조회하지 못했습니다. 잠시 후 다시 시도하세요.")
        }
        fun forHttp(status: Int): WeatherFailure = when (status) {
            429 -> WeatherFailure("기상청 요청 한도를 초과했습니다.", WeatherFailureKind.QUOTA)
            401, 403 -> WeatherFailure("기상청 인증·접근 설정을 확인하세요.", WeatherFailureKind.AUTH)
            else -> WeatherFailure("기상청에 연결하지 못했습니다. 잠시 후 다시 시도하세요.")
        }
    }
}

/** One instance per request: cancellation cannot disconnect a newer request. Never log a URL/key. */
internal class KmaWeatherCall(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val deadline: ((() -> Unit) -> AutoCloseable) = { expire ->
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "weather-timeout").apply { isDaemon = true }
        }
        val future = timer.schedule({ expire() }, 12_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        AutoCloseable { future.cancel(false); timer.shutdownNow() }
    },
) : WeatherCall {
    @Volatile private var cancelled = false
    @Volatile private var timedOut = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var deadlineHandle: AutoCloseable? = null
    override fun cancel() { cancelled = true; deadlineHandle?.close(); connection?.disconnect() }

    override fun fetch(key: String, grid: KmaGrid, now: Long): WeatherSnapshot {
        val base = EnvironmentPolicy.baseTime(now)
        val args = linkedMapOf("serviceKey" to normalizeKey(key), "pageNo" to "1", "numOfRows" to "100", "dataType" to "XML",
            "base_date" to base.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE), "base_time" to base.format(DateTimeFormatter.ofPattern("HHmm")),
            "nx" to grid.x.toString(), "ny" to grid.y.toString())
        val query = args.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = openConnection(URL("https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst?$query"))
        connection = conn
        val timeout = deadline { timedOut = true; cancel() }
        deadlineHandle = timeout
        try {
            if (cancelled) throw WeatherFailure("조회 취소")
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000
            conn.instanceFollowRedirects = false
            val status = conn.responseCode
            if (status != 200) throw WeatherFailure.forHttp(status)
            val bytes = conn.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    if (cancelled) throw WeatherFailure("조회 취소")
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 524_288) throw WeatherFailure("날씨 정보를 읽을 수 없습니다. 다시 조회해 주세요.")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            if (cancelled) throw WeatherFailure("조회 취소")
            val result = KmaResponseParser.parse(bytes.toString(Charsets.UTF_8), grid, base, wallTime())
            if (cancelled) throw WeatherFailure("조회 취소")
            return result
        } catch (e: WeatherFailure) { if (timedOut) throw WeatherFailure("기상청 통신 시간 초과") else throw e
        } catch (_: java.net.SocketTimeoutException) { throw WeatherFailure("기상청 통신 시간 초과")
        } catch (_: Exception) { throw WeatherFailure(if (timedOut) "기상청 통신 시간 초과" else "기상청 연결 또는 응답 오류")
        } finally { timeout.close(); deadlineHandle = null; conn.disconnect(); connection = null }
    }

    companion object {
        // Accept either portal key form, preserving literal '+' in decoded keys. Encode exactly once below.
        private fun normalizeKey(key: String): String = if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(key))
            runCatching { java.net.URLDecoder.decode(key.replace("+", "%2B"), "UTF-8") }.getOrDefault(key) else key
    }
}
