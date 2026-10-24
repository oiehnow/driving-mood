package kr.urbanmood.context

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class KmaTransportTest {
    private val now = Instant.parse("2026-10-24T01:12:00Z").toEpochMilli()
    private class Connection(url: URL, private val status: Int = 200) : HttpURLConnection(url) {
        var httpCalls = 0
        var disconnects = 0
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnects++ }
        override fun getResponseCode(): Int { httpCalls++; return status }
        override fun getInputStream() = "<response><resultCode>00</resultCode><item><nx>60</nx><ny>127</ny><baseDate>20261024</baseDate><baseTime>1000</baseTime><category>PTY</category><obsrValue>0</obsrValue></item></response>".byteInputStream()
    }
    @Test fun endpointAndEitherKeyFormAreEncodedOnceAndDeadlineIsDisposed() {
        val requests = mutableListOf<Connection>()
        var deadlinesClosed = 0
        listOf("fake+/=", "fake%2B%2F%3D").forEach { key ->
            KmaWeatherCall(openConnection = { Connection(it).also(requests::add) }, wallTime = { now },
                deadline = { AutoCloseable { deadlinesClosed++ } }).fetch(key,KmaGrid(60,127),now)
        }
        assertEquals(2,requests.sumOf { it.httpCalls })
        assertEquals(requests[0].url,requests[1].url)
        val url = requests[0].url
        assertEquals("apis.data.go.kr",url.host)
        assertTrue(url.path.endsWith("/getUltraSrtNcst"))
        assertTrue(url.query.contains("serviceKey=fake%2B%2F%3D"))
        assertFalse(url.query.contains("authKey"))
        assertEquals(2,deadlinesClosed)
        assertTrue(requests.all { it.disconnects == 1 && !it.instanceFollowRedirects })
    }
    @Test fun http429IsQuotaFailureAndAlwaysDisconnects() {
        lateinit var connection: Connection
        val error = runCatching {
            KmaWeatherCall(openConnection = { Connection(it,429).also { c -> connection=c } },
                deadline = { AutoCloseable {} }).fetch("fake",KmaGrid(60,127),now)
        }.exceptionOrNull() as WeatherFailure
        assertEquals(WeatherFailureKind.QUOTA,error.kind)
        assertEquals(1,connection.httpCalls)
        assertEquals(1,connection.disconnects)
    }
    @Test fun cancelledBeforeStartAndExpiredDeadlineDoNotConnect() {
        val connections = mutableListOf<Connection>()
        val call = KmaWeatherCall(openConnection = { Connection(it).also(connections::add) },deadline = { AutoCloseable {} })
        call.cancel()
        assertTrue(runCatching { call.fetch("fake",KmaGrid(60,127),now) }.isFailure)
        val timedOut = KmaWeatherCall(openConnection = { Connection(it).also(connections::add) },deadline = { expire -> expire(); AutoCloseable {} })
        val error = runCatching { timedOut.fetch("fake",KmaGrid(60,127),now) }.exceptionOrNull() as WeatherFailure
        assertTrue(error.safeMessage.contains("시간 초과"))
        assertEquals(0,connections.sumOf { it.httpCalls })
        assertTrue(connections.all { it.disconnects >= 1 })
    }
}
