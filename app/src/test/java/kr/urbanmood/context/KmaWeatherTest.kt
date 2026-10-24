package kr.urbanmood.context

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class KmaWeatherTest {
    private val base = ZonedDateTime.parse("2026-10-24T01:00:00+09:00[Asia/Seoul]")
    private val fetched = base.plusMinutes(20).toInstant().toEpochMilli()
    private fun xml(vararg values: Pair<String, String>, x: Int = 60, time: String = "0100") =
        "<response><header><resultCode>00</resultCode></header><body><items>" + values.joinToString("") { (k,v) ->
            "<item><baseDate>20261024</baseDate><baseTime>$time</baseTime><nx>$x</nx><ny>127</ny><category>$k</category><obsrValue>$v</obsrValue></item>"
        } + "</items></body></response>"
    private fun parse(body: String) = KmaResponseParser.parse(body, KmaGrid(60,127), base, fetched)

    @Test fun seoulBusanJejuAndProjectionOriginMatchGrid() {
        assertEquals(KmaGrid(60,127), KmaGridConverter.from(37.5665,126.9780))
        assertEquals(KmaGrid(98,76), KmaGridConverter.from(35.1796,129.0756))
        assertEquals(KmaGrid(53,38), KmaGridConverter.from(33.4996,126.5312))
        assertEquals(KmaGrid(43,136), KmaGridConverter.from(38.0,126.0))
    }
    @Test fun invalidAndOutOfAreaAreNotClampedToKorea() {
        assertNull(KmaGridConverter.from(Double.NaN,127.0))
        assertNull(KmaGridConverter.from(95.0,127.0))
        assertNull(KmaGridConverter.from(48.85,2.35))
    }
    @Test fun publicationDelayCrossesDateAndYearSafely() {
        assertEquals("2025-12-31T23:00+09:00[Asia/Seoul]", EnvironmentPolicy.baseTime(ZonedDateTime.parse("2026-01-01T00:10:00+09:00").toInstant().toEpochMilli()).toString())
        assertEquals(0, EnvironmentPolicy.baseTime(ZonedDateTime.parse("2026-01-01T00:11:00+09:00").toInstant().toEpochMilli()).hour)
    }
    @Test fun parsesObservationAndDoesNotInventSky() {
        val w = parse(xml("T1H" to "16.5", "RN1" to "0", "PTY" to "0"))
        assertEquals(16.5, w.temperatureC!!, 0.001)
        assertEquals("강수 없음", w.condition)
        assertEquals(base.toInstant().toEpochMilli(), w.source.observedAtEpochMs)
        assertEquals(fetched, w.source.fetchedAtEpochMs)
        assertFalse(w.source.cached)
    }
    @Test fun unknownConditionAndMissingMeasurementsStayNull() {
        val w = parse(xml("T1H" to "-999", "RN1" to "1.5", "PTY" to "99"))
        assertNull(w.temperatureC); assertNull(w.condition)
        assertEquals(1.5, w.precipitationMm!!, 0.001)
        assertTrue(w.source.detail!!.contains("부분 누락"))
    }
    @Test fun allPrecipitationCodesInCurrentGuideAreSupported() {
        val names = listOf("강수 없음","비","비/눈","눈","소나기","빗방울","빗방울눈날림","눈날림")
        names.forEachIndexed { n, name -> assertEquals(name, parse(xml("PTY" to "$n")).condition) }
    }
    @Test(expected = WeatherFailure::class) fun wrongGridRejected() { parse(xml("PTY" to "1", x=61)) }
    @Test(expected = WeatherFailure::class) fun wrongObservationTimeRejected() { parse(xml("PTY" to "1", time="0000")) }
    @Test(expected = WeatherFailure::class) fun noObservationRejected() { parse(xml()) }
    @Test(expected = WeatherFailure::class) fun duplicateCategoryRejected() { parse(xml("PTY" to "1", "PTY" to "0")) }
    @Test fun apiErrorDoesNotEchoServerMessageOrSecret() {
        val error = runCatching { parse("<response><resultCode>30</resultCode><resultMsg>private-key-placeholder</resultMsg></response>") }.exceptionOrNull()
        assertTrue(error is WeatherFailure)
        assertFalse(error!!.message!!.contains("private-key-placeholder"))
    }
    @Test fun quotaAndAccessDeniedHaveDifferentRecoveryMessages() {
        fun failure(code: String) = runCatching {
            parse("<response><resultCode>$code</resultCode></response>")
        }.exceptionOrNull() as WeatherFailure
        assertTrue(failure("22").safeMessage.contains("한도"))
        assertTrue(failure("20").safeMessage.contains("접근"))
        assertFalse(failure("20").safeMessage.contains("한도"))
    }
    @Test fun portalCommonErrorEnvelopeIsNormalizedWithoutEchoingRawText() {
        val error = runCatching { parse("<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>secret-url</errMsg><returnAuthMsg>secret-key</returnAuthMsg><returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>") }.exceptionOrNull() as WeatherFailure
        assertEquals(WeatherFailureKind.QUOTA, error.kind)
        assertTrue(error.safeMessage.contains("한도"))
        assertFalse(error.safeMessage.contains("secret"))
        assertEquals(WeatherFailureKind.AUTH, WeatherFailure.forCode("20").kind)
        assertEquals(WeatherFailureKind.AUTH, WeatherFailure.forCode("21").kind)
        assertEquals(WeatherFailureKind.AUTH, WeatherFailure.forCode("33").kind)
        assertEquals(WeatherFailureKind.QUOTA, WeatherFailure.forHttp(429).kind)
        assertEquals(WeatherFailureKind.TRANSIENT, WeatherFailure.forHttp(503).kind)
    }
    @Test(expected = IllegalArgumentException::class) fun externalEntityRejected() {
        parse("<!DOCTYPE response [<!ENTITY leak SYSTEM 'file:///private'>]><response>&leak;</response>")
    }
    @Test fun cachedDataRetainsTimesAndExpires() {
        val w = parse(xml("PTY" to "1"))
        val cached = EnvironmentPolicy.cached(w, fetched + 1000, "통신 실패")
        assertTrue(cached.source.cached)
        assertEquals(w.source.observedAtEpochMs, cached.source.observedAtEpochMs)
        assertEquals(w.source.fetchedAtEpochMs, cached.source.fetchedAtEpochMs)
        assertEquals(Availability.STALE, EnvironmentPolicy.cached(w, fetched + 2*60*60_000).availability)
        assertEquals(Availability.STALE, EnvironmentPolicy.cached(w, base.toInstant().toEpochMilli()-1).availability)
    }
    @Test fun inaccurateMockMissingAndOldLocationsCannotDriveRealWeather() {
        val good = LocationSnapshot(Availability.AVAILABLE,37.5,127.0,10f,source=SourceInfo("fixture",fetched))
        assertTrue(EnvironmentPolicy.usableLocation(good,fetched))
        assertFalse(EnvironmentPolicy.usableLocation(good.copy(accuracyMeters=6000f),fetched))
        assertFalse(EnvironmentPolicy.usableLocation(good.copy(accuracyMeters=null),fetched))
        assertFalse(EnvironmentPolicy.usableLocation(good.copy(availability=Availability.MANUAL),fetched))
        assertFalse(EnvironmentPolicy.usableLocation(good.copy(latitude=null),fetched))
        assertFalse(EnvironmentPolicy.usableLocation(good,fetched+120_001))
    }
}
